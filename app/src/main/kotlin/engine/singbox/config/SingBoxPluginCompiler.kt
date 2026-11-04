// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package engine.singbox.config

import engine.plugin.PluginFile
import engine.plugin.PluginOutboundPlan
import engine.plugin.PluginOutbounds
import engine.plugin.PluginRequirement
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URLEncoder
import java.util.Base64

internal data class PluginCompiledConfig(
    val root: JsonObject,
    val plans: List<PluginOutboundPlan>,
)

private const val PluginMappingInboundPrefix = "plugin-mapping-"
private const val PluginSocksHost = "127.0.0.1"


/**
 * Replaces every plugin-backed outbound with the stock pieces the core can run:
 * a SOCKS outbound to the plugin's local listener, and — when the plugin cannot
 * dial the server itself — a `direct` inbound plus destination override so the
 * core performs the real connection.
 *
 * [workingDir] must match the directory the core writes plugin files into: it is
 * baked into the plugin configs for file references that cannot use the
 * `${file:...}` placeholder (ShadowQUIC's `cert-path`).
 */
internal fun compilePluginOutbounds(
    root: JsonObject,
    useProtect: Boolean,
    protectPath: String,
    workingDir: String,
    validationOnly: Boolean = false,
    pluginBacked: (String) -> Boolean = { false },
): PluginCompiledConfig {
    val outbounds = root["outbounds"] as? JsonArray ?: return PluginCompiledConfig(root, emptyList())
    val plans = mutableListOf<PluginOutboundPlan>()
    val usedPorts = mutableSetOf<Int>()
    val seenTags = mutableSetOf<String>()
    val inbounds = (root["inbounds"] as? JsonArray).orEmptyArray().toMutableList()
    val mappingRules = mutableListOf<JsonObject>()

    val compiledOutbounds = buildJsonArray {
        var mappingIndex = 0
        outbounds.forEach { element ->
            val outbound = element as? JsonObject
            val type = (outbound?.get("type") as? JsonPrimitive)?.contentOrNull
            val requirement = type?.let { outboundType ->
                PluginOutbounds.requirementFor(outboundType, pluginBacked(outboundType))
            }
            if (outbound == null || requirement == null) {
                add(element)
                return@forEach
            }
            val tag = (outbound["tag"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            require(tag.isNotBlank()) { "Plugin outbound needs a tag" }
            require(seenTags.add(tag)) { "Duplicate outbound tag $tag" }
            val server = (outbound["server"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            val serverPort = (outbound["server_port"] as? JsonPrimitive)?.intOrNull ?: 0
            require(server.isNotBlank() && serverPort in 1..65535) {
                "Plugin outbound $tag needs a valid server and server_port"
            }
            mappingIndex += 1
            // A plugin that cannot hand sockets back must always be mapped, even
            // in VPN mode, or its dials loop into the tunnel.
            val protected = useProtect && requirement.canSelfProtect
            // Validation only checks that the core can parse the result: reuse one
            // dead port instead of reserving real listeners on every state save.
            val socksPort = if (validationOnly) 1 else allocateLoopbackPort(usedPorts)
            val mappingPort = when {
                validationOnly -> 1
                protected -> 0
                else -> allocateLoopbackPort(usedPorts)
            }
            plans += buildPluginOutboundPlan(
                requirement = requirement,
                outbound = outbound,
                tag = tag,
                server = server,
                serverPort = serverPort,
                socksPort = socksPort,
                mappingPort = mappingPort,
                workingDir = workingDir,
                useProtect = protected,
                protectPath = protectPath,
            )
            if (!protected && !validationOnly) {
                // The plugin only ever dials loopback; the core connects to the
                // real server on its own (already protected) sockets.
                inbounds += buildJsonObject {
                    put("type", "direct")
                    put("tag", "$PluginMappingInboundPrefix$mappingIndex")
                    put("listen", PluginSocksHost)
                    put("listen_port", mappingPort)
                }
                mappingRules += buildJsonObject {
                    put("action", "route-options")
                    putJsonArray("inbound") { add("$PluginMappingInboundPrefix$mappingIndex") }
                    put("override_address", server)
                    put("override_port", serverPort)
                }
                mappingRules += buildJsonObject {
                    put("inbound", JsonArray(listOf(JsonPrimitive("$PluginMappingInboundPrefix$mappingIndex"))))
                    // BOX names its direct outbound; a bare "direct" does not exist
                    // in the compiled config and the mapping inbound would fail.
                    put("outbound", APP_DIRECT_OUTBOUND)
                }
            }
            add(
                buildJsonObject {
                    put("type", "socks")
                    put("tag", tag)
                    put("server", PluginSocksHost)
                    put("server_port", socksPort)
                },
            )
        }
    }

    var compiled = root
        .updated("outbounds", compiledOutbounds)
        .updated("inbounds", JsonArray(inbounds))
    if (mappingRules.isNotEmpty()) {
        compiled = compiled.withLeadingRouteRules(mappingRules)
    }
    return PluginCompiledConfig(compiled, plans)
}

private fun buildPluginOutboundPlan(
    requirement: PluginRequirement,
    outbound: JsonObject,
    tag: String,
    server: String,
    serverPort: Int,
    socksPort: Int,
    mappingPort: Int,
    workingDir: String,
    useProtect: Boolean,
    protectPath: String,
): PluginOutboundPlan {
    val dialAddress = if (useProtect) joinAddress(server, serverPort) else joinAddress(PluginSocksHost, mappingPort)
    val protect = protectPath.takeIf { useProtect }.orEmpty()
    return when (requirement.outboundType) {
        PluginOutbounds.mieru.outboundType -> {
            val fileName = "mieru_$socksPort.json"
            PluginOutboundPlan(
                requirement = requirement,
                tag = tag,
                socksPort = socksPort,
                mappingPort = mappingPort,
                commandArguments = listOf("run"),
                files = listOf(PluginFile(fileName, buildMieruConfig(outbound, tag, dialAddress, socksPort))),
                environment = buildMap {
                    put("MIERU_CONFIG_JSON_FILE", "\${file:$fileName}")
                    if (protect.isNotEmpty()) {
                        put("MIERU_PROTECT_PATH", protect)
                    }
                },
            )
        }

        PluginOutbounds.juicity.outboundType -> {
            val fileName = "juicity_$socksPort.json"
            PluginOutboundPlan(
                requirement = requirement,
                tag = tag,
                socksPort = socksPort,
                mappingPort = mappingPort,
                commandArguments = listOf("run", "-c", "\${file:$fileName}"),
                files = listOf(
                    PluginFile(
                        fileName,
                        buildJuicityConfig(outbound, tag, dialAddress, socksPort, protect),
                    ),
                ),
                environment = buildMap { put("QUIC_GO_DISABLE_GSO", "1") },
            )
        }

        PluginOutbounds.hysteria2.outboundType -> {
            val fileName = "hysteria2_$socksPort.json"
            PluginOutboundPlan(
                requirement = requirement,
                tag = tag,
                socksPort = socksPort,
                mappingPort = mappingPort,
                commandArguments = listOf("client", "--config", "\${file:$fileName}"),
                files = listOf(
                    PluginFile(
                        fileName,
                        buildHysteria2Config(outbound, tag, dialAddress, socksPort, protect),
                    ),
                ),
                environment = buildMap {
                    put("HYSTERIA_DISABLE_UPDATE_CHECK", "1")
                },
            )
        }

        PluginOutbounds.naive.outboundType -> {
            val fileName = "naive_$socksPort.json"
            PluginOutboundPlan(
                requirement = requirement,
                tag = tag,
                socksPort = socksPort,
                mappingPort = mappingPort,
                commandArguments = listOf("\${file:$fileName}"),
                files = listOf(
                    PluginFile(
                        fileName,
                        buildNaiveConfig(outbound, dialAddress, socksPort, useProtect),
                    ),
                ),
                environment = emptyMap(),
            )
        }

        else -> {
            val fileName = "shadowquic_$socksPort.yaml"
            val files = mutableListOf<PluginFile>()
            val certificates = outbound.stringValue("certificates")
            val isSunny = outbound.stringValue("sub_protocol").equals("sunnyquic", ignoreCase = true)
            val certFileName = "shadowquic_${socksPort}_cert.pem"
            if (isSunny && certificates.isNotBlank()) {
                files += PluginFile(certFileName, certificates)
            }
            files += PluginFile(
                fileName,
                buildShadowQuicConfig(
                    outbound = outbound,
                    tag = tag,
                    dialAddress = dialAddress,
                    socksPort = socksPort,
                    protectPath = protect,
                    certPath = if (files.isEmpty()) "" else File(workingDir, certFileName).absolutePath,
                ),
            )
            PluginOutboundPlan(
                requirement = requirement,
                tag = tag,
                socksPort = socksPort,
                mappingPort = mappingPort,
                commandArguments = listOf("-c", "\${file:$fileName}"),
                files = files,
                environment = emptyMap(),
            )
        }
    }
}

private fun buildMieruConfig(
    outbound: JsonObject,
    tag: String,
    dialAddress: String,
    socksPort: Int,
): String {
    val username = outbound.stringValue("username")
    val password = outbound.stringValue("password")
    require(password.isNotBlank()) { "Mieru outbound $tag needs a password" }
    val protocol = outbound.stringValue("protocol").ifBlank { "TCP" }.uppercase()
    val mtu = outbound.intValue("mtu") ?: 1400
    val host = dialAddress.substringBeforeLast(':').removeSurrounding("[", "]")
    val port = dialAddress.substringAfterLast(':').toIntOrNull() ?: 0
    val profile = buildJsonObject {
        put("profileName", "default")
        putJsonObject("user") {
            put("name", username)
            put("password", password)
        }
        putJsonArray("servers") {
            addJsonObject {
                putJsonArray("portBindings") {
                    addJsonObject {
                        put("port", port)
                        put("protocol", protocol)
                    }
                }
                // mieru refuses a domain name in the ipAddress field.
                if (host.isIpLiteral()) {
                    put("ipAddress", host)
                } else {
                    put("domainName", host)
                }
            }
        }
        put("mtu", mtu)
        multiplexingLevel(outbound.stringValue("multiplexing"))?.let { level ->
            putJsonObject("multiplexing") { put("level", level) }
        }
        // Mieru TCP mux plus no-wait handshake is a known upstream bug.
        put("handshakeMode", "HANDSHAKE_STANDARD")
        outbound.stringValue("traffic_pattern").takeIf(String::isNotBlank)?.let { raw ->
            val pattern = mieruTrafficPattern(raw)
                ?: error("Mieru outbound $tag has an invalid traffic_pattern")
            put("trafficPattern", pattern)
        }
    }
    return encodeSingBoxJson(
        buildJsonObject {
            put("activeProfile", "default")
            put("socks5Port", socksPort)
            put("loggingLevel", "WARN")
            putJsonObject("advancedSettings") { put("noCheckUpdate", true) }
            putJsonArray("profiles") { add(profile) }
        },
    )
}

/**
 * Mieru share links carry the traffic pattern as base64-encoded JSON; the editor
 * also accepts raw JSON, so try both.
 */
private fun mieruTrafficPattern(raw: String): JsonElement? {
    if (raw.isBlank()) return null
    val direct = runCatching { SingBoxJson.parseToJsonElement(raw) }.getOrNull()
    if (direct is JsonObject) {
        return direct["trafficPattern"] ?: direct
    }
    val decoded = runCatching {
        val normalized = raw.replace('-', '+').replace('_', '/')
        val padded = normalized + "=".repeat((4 - normalized.length % 4) % 4)
        String(Base64.getDecoder().decode(padded), Charsets.UTF_8)
    }.getOrNull() ?: return null
    val parsed = runCatching { SingBoxJson.parseToJsonElement(decoded) }.getOrNull()
    return (parsed as? JsonObject)?.let { obj -> obj["trafficPattern"] ?: obj }
}

private fun buildJuicityConfig(
    outbound: JsonObject,
    tag: String,
    dialAddress: String,
    socksPort: Int,
    protectPath: String,
): String {
    val uuid = outbound.stringValue("uuid")
    val password = outbound.stringValue("password")
    require(password.isNotBlank()) { "Juicity outbound $tag needs a password" }
    val sni = outbound.stringValue("sni")
    return encodeSingBoxJson(
        buildJsonObject {
            put("listen", "$PluginSocksHost:$socksPort")
            put("server", dialAddress)
            put("uuid", uuid)
            put("password", password)
            put("sni", sni.ifBlank { outbound.stringValue("server") })
            if (outbound.booleanValue("allow_insecure") == true) {
                put("allow_insecure", true)
            }
            put("congestion_control", outbound.stringValue("congestion_control").ifBlank { "bbr" })
            outbound.stringValue("pin_sha256").takeIf(String::isNotBlank)?.let { pin ->
                put("pinned_certchain_sha256", pin)
            }
            if (protectPath.isNotBlank()) {
                put("protect_path", protectPath)
            }
            put("log_level", "error")
        },
    )
}

private fun buildShadowQuicConfig(
    outbound: JsonObject,
    tag: String,
    dialAddress: String,
    socksPort: Int,
    protectPath: String,
    certPath: String,
): String {
    val username = outbound.stringValue("username")
    val password = outbound.stringValue("password")
    require(password.isNotBlank()) { "ShadowQUIC outbound $tag needs a password" }
    val isSunny = outbound.stringValue("sub_protocol").equals("sunnyquic", ignoreCase = true)
    val serverName = outbound.stringValue("server_name").ifBlank { outbound.stringValue("sni") }
    val initialMtu = outbound.intValue("initial_mtu") ?: 1300
    val minimumMtu = outbound.intValue("min_mtu") ?: 0
    val keepAlive = outbound.intValue("keep_alive_interval") ?: 0
    val maxPaths = outbound.intValue("max_paths") ?: 0
    return encodeSingBoxJson(
        buildJsonObject {
            putJsonObject("inbound") {
                put("type", "socks")
                put("bind-addr", "$PluginSocksHost:$socksPort")
            }
            putJsonObject("outbound") {
                put("type", if (isSunny) "sunnyquic" else "shadowquic")
                put("addr", dialAddress)
                put("username", username)
                put("password", password)
                put("server-name", serverName.ifBlank { outbound.stringValue("server") })
                textList(outbound, "alpn").takeIf { it.isNotEmpty() }?.let { alpns ->
                    putJsonArray("alpn") { alpns.forEach { item -> add(item) } }
                }
                put("initial-mtu", initialMtu)
                if (minimumMtu > 0) put("min-mtu", minimumMtu)
                put("congestion-control", outbound.stringValue("congestion_control").ifBlank { "bbr" })
                if (keepAlive > 0) put("keep-alive-interval", keepAlive)
                if (isSunny) {
                    val paths = textList(outbound, "extra_paths")
                    if (paths.isNotEmpty()) {
                        putJsonArray("extra-paths") { paths.forEach { path -> add(path) } }
                        maxPaths.coerceIn(0, paths.size).takeIf { it > 0 }?.let { count ->
                            put("max-path-num", count)
                        }
                    }
                    if (certPath.isNotBlank()) put("cert-path", certPath)
                }
                put("gso", outbound.booleanValue("gso") ?: false)
                if (outbound.booleanValue("zero_rtt") == true) put("zero-rtt", true)
                if (outbound.booleanValue("udp_over_stream") == true) put("over-stream", true)
                if (outbound.booleanValue("mtu_discovery") == true) {
                    put("mtu-discovery", true)
                    if (!isSunny && outbound.booleanValue("blackhole_detection") == true) {
                        put("blackhole-detection", true)
                    }
                }
                if (protectPath.isNotBlank()) put("protect-path", protectPath)
            }
            put("log-level", "error")
        },
    )
}


/** Hysteria2 plugin config: sing-box shaped outbound JSON in, hysteria2 JSON out. */
private fun buildHysteria2Config(
    outbound: JsonObject,
    tag: String,
    dialAddress: String,
    socksPort: Int,
    protectPath: String,
): String {
    val password = outbound.stringValue("password")
    require(password.isNotBlank()) { "Hysteria2 outbound $tag needs a password" }
    val tls = outbound["tls"] as? JsonObject
    val obfs = outbound["obfs"] as? JsonObject
    val obfsType = obfs?.stringValue("type").orEmpty()
    val serverName = tls?.stringValue("server_name").orEmpty()
        .ifBlank { outbound.stringValue("server") }
    return encodeSingBoxJson(
        buildJsonObject {
            put("server", dialAddress)
            put("auth", password)
            put("fastOpen", true)
            put("lazy", true)
            putJsonObject("socks5") { put("listen", "$PluginSocksHost:$socksPort") }
            putJsonObject("tls") {
                put("sni", serverName)
                put("insecure", tls?.booleanValue("insecure") ?: false)
                textList(tls ?: JsonObject(emptyMap()), "alpn").takeIf { it.isNotEmpty() }?.let { alpns ->
                    putJsonArray("alpn") { alpns.forEach { item -> add(item) } }
                }
            }
            if (obfsType.equals("salamander", ignoreCase = true)) {
                val obfsPassword = obfs?.stringValue("password").orEmpty()
                require(obfsPassword.isNotBlank()) {
                    "Hysteria2 outbound $tag uses salamander obfs without a password"
                }
                putJsonObject("obfs") {
                    put("type", "salamander")
                    putJsonObject("salamander") {
                        put("password", obfsPassword)
                    }
                }
            }
            val upMbps = bandwidthValue(outbound, "up_mbps")
            val downMbps = bandwidthValue(outbound, "down_mbps")
            if (upMbps != null || downMbps != null) {
                putJsonObject("bandwidth") {
                    upMbps?.let { up -> put("up", "$up mbps") }
                    downMbps?.let { down -> put("down", "$down mbps") }
                }
            }
            if (protectPath.isNotBlank()) {
                putJsonObject("quic") { put("fdControlUnixSocket", protectPath) }
            }
            put("logLevel", "error")
        },
    )
}

/** NaiveProxy plugin config: Chromium-style JSON with a socks listener. */
private fun buildNaiveConfig(
    outbound: JsonObject,
    dialAddress: String,
    socksPort: Int,
    useProtect: Boolean,
): String {
    val tls = outbound["tls"] as? JsonObject
    val username = outbound.stringValue("username")
    val password = outbound.stringValue("password")
    val proxyHost = tls?.stringValue("server_name").orEmpty()
        .ifBlank { outbound.stringValue("server") }
    val dialHost = dialAddress.substringBeforeLast(':').removeSurrounding("[", "]")
    val dialPort = dialAddress.substringAfterLast(':').toIntOrNull() ?: 443
    val credentials = if (username.isBlank()) {
        ""
    } else {
        "${encodeUserInfo(username)}:${encodeUserInfo(password)}@"
    }
    return encodeSingBoxJson(
        buildJsonObject {
            put("listen", "socks://$PluginSocksHost:$socksPort")
            put("proxy", "https://$credentials$proxyHost:$dialPort")
            if (!useProtect) {
                // Keep TLS verification on the domain while dialing the mapping port.
                put("host-resolver-rules", "MAP $proxyHost $dialHost")
            }
        },
    )
}

private fun bandwidthValue(outbound: JsonObject, key: String): Int? =
    (outbound[key] as? JsonPrimitive)?.intOrNull
        ?: (outbound[key] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
        ?: outbound.intValue(key)


private fun multiplexingLevel(value: String): String? = when (value.trim().uppercase()) {
    "LOW" -> "MULTIPLEXING_LOW"
    "MEDIUM" -> "MULTIPLEXING_MEDIUM"
    "HIGH" -> "MULTIPLEXING_HIGH"
    else -> null
}

/** Accepts a JSON array (imported config) or a comma/space/newline separated string (editor). */
private fun textList(outbound: JsonObject, key: String): List<String> {
    val element = outbound[key]
    if (element is JsonArray) {
        return element.mapNotNull { item -> (item as? JsonPrimitive)?.contentOrNull }
            .filter(String::isNotBlank)
    }
    return (element as? JsonPrimitive)?.contentOrNull.orEmpty()
        .split(',', ' ', '\n', '\r', '\t')
        .map(String::trim)
        .filter(String::isNotEmpty)
}

private fun joinAddress(host: String, port: Int): String = when {
    host.startsWith('[') -> "$host:$port"
    host.contains(':') -> "[$host]:$port"
    else -> "$host:$port"
}

/** Percent-encode one URL userinfo component; '+' must not survive as a space. */
private fun encodeUserInfo(value: String): String =
    URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")

private fun JsonObject.withLeadingRouteRules(rules: List<JsonObject>): JsonObject {
    val route = (this["route"] as? JsonObject) ?: buildJsonObject { putJsonArray("rules") {} }
    val existing = (route["rules"] as? JsonArray) ?: JsonArray(emptyList())
    val updated = buildJsonArray {
        rules.forEach(::add)
        existing.forEach(::add)
    }
    return this.updated("route", JsonObject(route.toMutableMap().apply { put("rules", updated) }))
}

private fun JsonObject.stringValue(key: String): String =
    (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()

private fun JsonObject.intValue(key: String): Int? =
    (this[key] as? JsonPrimitive)?.intOrNull

private fun JsonObject.booleanValue(key: String): Boolean? =
    (this[key] as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull()

private fun JsonArray?.orEmptyArray(): JsonArray = this ?: JsonArray(emptyList())

private fun String.isIpLiteral(): Boolean =
    Regex("^[0-9.]+$").matches(this) || contains(':')

/** A loopback port the plugin (or core) can bind right now, unique per compile. */
internal fun allocateLoopbackPort(used: MutableSet<Int> = mutableSetOf()): Int {
    repeat(16) {
        val port = ServerSocket(0, 1, InetAddress.getByName(PluginSocksHost)).use { socket ->
            socket.localPort
        }
        if (used.add(port)) return port
    }
    error("No free loopback port for a plugin outbound")
}
