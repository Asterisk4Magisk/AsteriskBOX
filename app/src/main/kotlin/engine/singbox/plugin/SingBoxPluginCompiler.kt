// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package engine.singbox.plugin

import engine.singbox.config.APP_DIRECT_OUTBOUND
import engine.singbox.config.encodeSingBoxJson
import engine.singbox.config.updated
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

/**
 * Where the compiled plugins run. A plugin that can protect its sockets dials
 * the server itself through [protectPath]; without one (ROOT, or a plugin that
 * cannot hand sockets back) it dials a loopback mapping inbound and the core
 * makes the real connection.
 */
internal data class PluginTarget(
    val workingDir: String,
    val protectPath: String? = null,
)

internal data class PluginCompiledConfig(
    val root: JsonObject,
    val plans: List<PluginOutboundPlan>,
)

private const val PluginMappingInboundPrefix = "plugin-mapping-"

// Placeholder for core checks: the result is parsed, never run.
private const val ValidationPort = 1

/**
 * Replaces every plugin-backed outbound with stock pieces the core can run: a
 * SOCKS outbound to the plugin's loopback listener and, when the plugin cannot
 * dial the server itself, a `direct` inbound plus destination override.
 *
 * A null [target] compiles for a core check only: no port is reserved and no
 * plan is produced.
 */
internal fun compilePluginOutbounds(
    root: JsonObject,
    pluginProtocols: Collection<String>,
    target: PluginTarget?,
): PluginCompiledConfig {
    val outbounds = root["outbounds"] as? JsonArray ?: return PluginCompiledConfig(root, emptyList())
    val plans = mutableListOf<PluginOutboundPlan>()
    val usedPorts = mutableSetOf<Int>()
    val inbounds = (root["inbounds"] as? JsonArray).orEmptyArray().toMutableList()
    val mappingRules = mutableListOf<JsonObject>()

    val compiledOutbounds = buildJsonArray {
        outbounds.forEach { element ->
            val outbound = element as? JsonObject
            val type = (outbound?.get("type") as? JsonPrimitive)?.contentOrNull
            val requirement = type?.let { outboundType ->
                PluginOutbounds.requirementFor(outboundType, pluginProtocols)
            }
            if (outbound == null || requirement == null) {
                add(element)
                return@forEach
            }
            val tag = outbound.stringValue("tag")
            require(tag.isNotBlank()) { "Plugin outbound needs a tag" }
            outbound.requireSupportedByPlugin(requirement, tag)
            val server = outbound.stringValue("server")
            val serverPort = outbound.intValue("server_port") ?: 0
            require(server.isNotBlank() && serverPort in 1..65535) {
                "Plugin outbound $tag needs a valid server and server_port"
            }
            val socksPort = if (target == null) ValidationPort else allocateLoopbackPort(usedPorts)
            add(
                buildJsonObject {
                    put("type", "socks")
                    put("tag", tag)
                    put("server", PluginSocksHost)
                    put("server_port", socksPort)
                    // A tcp- or udp-only outbound stays that way behind the plugin.
                    outbound.stringValue("network").takeIf(String::isNotBlank)?.let { network ->
                        put("network", network)
                    }
                },
            )
            if (target == null) return@forEach
            // A plugin that cannot hand sockets back is always mapped, or its
            // dials would loop into the tunnel.
            val protectPath = target.protectPath?.takeIf { requirement.canSelfProtect }
            val mappingPort = if (protectPath == null) allocateLoopbackPort(usedPorts) else 0
            plans += buildPluginOutboundPlan(
                requirement = requirement,
                outbound = outbound,
                tag = tag,
                server = server,
                serverPort = serverPort,
                socksPort = socksPort,
                mappingPort = mappingPort,
                workingDir = target.workingDir,
                useProtect = protectPath != null,
                protectPath = protectPath.orEmpty(),
            )
            if (protectPath == null) {
                val mappingTag = "$PluginMappingInboundPrefix${plans.size}"
                inbounds += buildJsonObject {
                    put("type", "direct")
                    put("tag", mappingTag)
                    put("listen", PluginSocksHost)
                    put("listen_port", mappingPort)
                }
                mappingRules += buildJsonObject {
                    putJsonArray("inbound") { add(mappingTag) }
                    put("action", "route-options")
                    put("override_address", server)
                    put("override_port", serverPort)
                }
                mappingRules += buildJsonObject {
                    putJsonArray("inbound") { add(mappingTag) }
                    put("outbound", APP_DIRECT_OUTBOUND)
                }
            }
        }
    }

    var compiled = root.updated("outbounds", compiledOutbounds)
    if (mappingRules.isNotEmpty()) {
        compiled = compiled.updated("inbounds", JsonArray(inbounds)).withLeadingRouteRules(mappingRules)
    }
    return PluginCompiledConfig(compiled, plans)
}

/** Options the plugin binary cannot honour fail the compile instead of being dropped. */
private fun JsonObject.requireSupportedByPlugin(requirement: PluginRequirement, tag: String) {
    when (requirement) {
        PluginOutbounds.hysteria2 -> {
            require(this["server_ports"].isEmptyValue()) {
                "Hysteria2 plugin outbound $tag does not support server_ports"
            }
            val obfsType = (this["obfs"] as? JsonObject)?.stringValue("type").orEmpty()
            require(obfsType.isBlank() || obfsType.equals("salamander", ignoreCase = true)) {
                "Hysteria2 plugin outbound $tag does not support $obfsType obfs"
            }
            // Hysteria pins the whole certificate, not its public key.
            require((this["tls"] as? JsonObject)?.get("certificate_public_key_sha256").isEmptyValue()) {
                "Hysteria2 plugin outbound $tag does not support certificate_public_key_sha256"
            }
        }
        PluginOutbounds.naive -> require(booleanValue("quic") != true) {
            "NaiveProxy plugin outbound $tag does not support QUIC"
        }
    }
}

private fun JsonElement?.isEmptyValue(): Boolean = when (this) {
    null -> true
    is JsonArray -> isEmpty()
    is JsonPrimitive -> contentOrNull.isNullOrBlank()
    else -> false
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
                    put("MIERU_CONFIG_JSON_FILE", $$"${file:$$fileName}")
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
                commandArguments = listOf("run", "-c", $$"${file:$$fileName}"),
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
            val files = mutableListOf<PluginFile>()
            val tls = outbound["tls"] as? JsonObject ?: JsonObject(emptyMap())
            // Inline PEM goes into its own file; a path option is used as given.
            fun tlsFile(inlineKey: String, pathKey: String, suffix: String): String {
                val inline = tls.pemValue(inlineKey)
                if (inline.isBlank()) return tls.stringValue(pathKey)
                val name = "hysteria2_${socksPort}_$suffix"
                files += PluginFile(name, inline)
                return File(workingDir, name).absolutePath
            }
            val certificates = Hysteria2TlsFiles(
                ca = tlsFile("certificate", "certificate_path", "ca.pem"),
                clientCertificate = tlsFile("client_certificate", "client_certificate_path", "client.pem"),
                clientKey = tlsFile("client_key", "client_key_path", "client.key"),
            )
            files += PluginFile(
                fileName,
                buildHysteria2Config(outbound, tag, dialAddress, socksPort, protect, certificates),
            )
            PluginOutboundPlan(
                requirement = requirement,
                tag = tag,
                socksPort = socksPort,
                mappingPort = mappingPort,
                commandArguments = listOf("client", "--config", $$"${file:$$fileName}"),
                files = files,
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
                commandArguments = listOf($$"${file:$$fileName}"),
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
                commandArguments = listOf("-c", $$"${file:$$fileName}"),
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
    require(username.isNotBlank()) { "Mieru outbound $tag needs a username" }
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
        mieruMultiplexingLevel(outbound.stringValue("multiplexing"))?.let { level ->
            putJsonObject("multiplexing") { put("level", level) }
        }
        // Mieru TCP mux plus no-wait handshake is a known upstream bug.
        put("handshakeMode", "HANDSHAKE_STANDARD")
        outbound.stringValue("traffic_pattern").takeIf(String::isNotBlank)?.let { raw ->
            val pattern = decodeMieruTrafficPattern(raw)
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

private fun buildJuicityConfig(
    outbound: JsonObject,
    tag: String,
    dialAddress: String,
    socksPort: Int,
    protectPath: String,
): String {
    val uuid = outbound.stringValue("uuid")
    val password = outbound.stringValue("password")
    require(uuid.isNotBlank()) { "Juicity outbound $tag needs a uuid" }
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


/** Absolute paths of the TLS files a Hysteria2 plugin reads; blank when unset. */
private data class Hysteria2TlsFiles(
    val ca: String,
    val clientCertificate: String,
    val clientKey: String,
)

/** Hysteria2 plugin config: sing-box shaped outbound JSON in, hysteria2 JSON out. */
private fun buildHysteria2Config(
    outbound: JsonObject,
    tag: String,
    dialAddress: String,
    socksPort: Int,
    protectPath: String,
    tlsFiles: Hysteria2TlsFiles,
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
                if (tlsFiles.ca.isNotBlank()) put("ca", tlsFiles.ca)
                if (tlsFiles.clientCertificate.isNotBlank()) put("clientCertificate", tlsFiles.clientCertificate)
                if (tlsFiles.clientKey.isNotBlank()) put("clientKey", tlsFiles.clientKey)
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
                putJsonObject("quic") {
                    putJsonObject("sockopts") { put("fdControlUnixSocket", protectPath) }
                }
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
        .removeSurrounding("[", "]")
    val dialHost = dialAddress.substringBeforeLast(':').removeSurrounding("[", "]")
    val dialPort = dialAddress.substringAfterLast(':').toIntOrNull() ?: 443
    val credentials = if (username.isBlank() && password.isBlank()) {
        ""
    } else {
        "${encodeUserInfo(username)}:${encodeUserInfo(password)}@"
    }
    val urlHost = if (proxyHost.contains(':')) "[$proxyHost]" else proxyHost
    val extraHeaders = (outbound["extra_headers"] as? JsonObject).orEmpty()
        .mapNotNull { (name, value) ->
            val text = when (value) {
                // sing-box accepts a header value as a string or a list of strings.
                is JsonArray -> value.mapNotNull { item -> (item as? JsonPrimitive)?.contentOrNull }
                    .joinToString(", ")
                is JsonPrimitive -> value.contentOrNull.orEmpty()
                else -> ""
            }
            "$name: $text".takeIf { name.isNotBlank() }
        }
    return encodeSingBoxJson(
        buildJsonObject {
            put("listen", "socks://$PluginSocksHost:$socksPort")
            put("proxy", "https://$credentials$urlHost:$dialPort")
            if (!useProtect) {
                // Keep TLS verification on the domain while dialing the mapping port.
                put("host-resolver-rules", "MAP $proxyHost $dialHost")
            }
            outbound.intValue("insecure_concurrency")?.takeIf { it > 1 }?.let { concurrency ->
                put("insecure-concurrency", concurrency)
            }
            if (extraHeaders.isNotEmpty()) put("extra-headers", extraHeaders.joinToString("\r\n"))
        },
    )
}

private fun bandwidthValue(outbound: JsonObject, key: String): Int? =
    (outbound[key] as? JsonPrimitive)?.intOrNull
        ?: (outbound[key] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
        ?: outbound.intValue(key)


/**
 * Mieru's MultiplexingLevel enum names; its middle level is MIDDLE. husi links
 * write MULTIPLEXING_MEDIUM, which is accepted as the same level.
 */
internal fun mieruMultiplexingLevel(value: String): String? = when (value.trim().uppercase()) {
    "OFF", "MULTIPLEXING_OFF" -> "MULTIPLEXING_OFF"
    "LOW", "MULTIPLEXING_LOW" -> "MULTIPLEXING_LOW"
    "MEDIUM", "MIDDLE", "MULTIPLEXING_MIDDLE", "MULTIPLEXING_MEDIUM" -> "MULTIPLEXING_MIDDLE"
    "HIGH", "MULTIPLEXING_HIGH" -> "MULTIPLEXING_HIGH"
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

/** A sing-box listable PEM option: one string, or an array of its lines. */
private fun JsonObject.pemValue(key: String): String = when (val element = this[key]) {
    is JsonArray -> element.mapNotNull { line -> (line as? JsonPrimitive)?.contentOrNull }.joinToString("\n")
    is JsonPrimitive -> element.contentOrNull.orEmpty()
    else -> ""
}.trim()

private fun JsonObject.intValue(key: String): Int? =
    (this[key] as? JsonPrimitive)?.intOrNull

private fun JsonObject.booleanValue(key: String): Boolean? =
    (this[key] as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull()

private fun JsonArray?.orEmptyArray(): JsonArray = this ?: JsonArray(emptyList())

private fun String.isIpLiteral(): Boolean =
    Regex("^[0-9.]+$").matches(this) || contains(':')

/** A loopback port the plugin (or core) can bind right now, unique per compile. */
private fun allocateLoopbackPort(used: MutableSet<Int>): Int {
    repeat(16) {
        val port = ServerSocket(0, 1, InetAddress.getByName(PluginSocksHost)).use { socket ->
            socket.localPort
        }
        if (used.add(port)) return port
    }
    error("No free loopback port for a plugin outbound")
}
