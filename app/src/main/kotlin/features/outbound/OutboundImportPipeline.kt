// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.outbound

import engine.singbox.config.SingBoxJson
import features.importing.ImportFormat
import features.importing.ImportIssue
import features.importing.ImportIssueReason
import features.importing.ImportIssueSeverity
import features.importing.ImportLimitException
import features.importing.ImportOutcome
import features.importing.ImportStage
import features.importing.MaxImportBytes
import features.importing.requireImportCandidateCount
import features.importing.requireImportTextWithinLimit
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import kotlin.io.encoding.Base64

internal enum class OutboundImportFormat(
    override val id: String,
) : ImportFormat {
    JSON("json"),
    YAML("yaml"),
    URL("url"),
}

internal data class OutboundImportResult(
    val format: OutboundImportFormat,
    val outbounds: List<ImportedSingBoxOutbound>,
)

private fun canonicalV2RayTransport(transport: String): String? =
    when (transport.lowercase()) {
        "", "tcp", "raw" -> "tcp"
        "http", "h2", "http2" -> "http"
        "ws", "websocket" -> "ws"
        "quic" -> "quic"
        "grpc" -> "grpc"
        "httpupgrade", "http-upgrade", "http_upgrade" -> "httpupgrade"
        else -> null
    }

private fun canonicalMihomoOutboundType(sourceType: String): String =
    when (sourceType.lowercase()) {
        "socks", "socks5" -> "socks"
        "ss" -> "shadowsocks"
        "hy1" -> "hysteria"
        "hy2" -> "hysteria2"
        else -> sourceType.lowercase()
    }

private fun canonicalProxyUrlOutboundType(scheme: String): String =
    when (scheme.lowercase()) {
        "http", "https" -> "http"
        "socks", "socks4", "socks4a", "socks5", "socks5h" -> "socks"
        "hy1" -> "hysteria"
        "hy2" -> "hysteria2"
        "naive", "naive+https", "naive+quic" -> "naive"
        else -> scheme.lowercase()
    }

private val TlsCapableOutboundTypes =
    setOf(
        "http",
        "naive",
        "vmess",
        "vless",
        "trojan",
        "hysteria",
        "hysteria2",
        "shadowtls",
        "tuic",
        "anytls",
    )

private val NaiveQuicCongestionControls = setOf("", "bbr", "bbr2", "cubic", "reno")

private val NaiveUrlQueryParameters = setOf(
    "security",
    "tls",
    "sni",
    "peer",
    "servername",
    "ech",
    "insecure-concurrency",
    "insecure_concurrency",
    "extra-headers",
    "extra_headers",
    "udp-over-tcp",
    "udp_over_tcp",
    "udp-over-tcp-version",
    "udp_over_tcp_version",
    "quic",
    "quic-congestion-control",
    "quic_congestion_control",
    "padding",
)

/**
 * A single import entry point shared by QR code, clipboard, files and subscriptions.
 *
 * Payload decoding is intentionally performed before format detection. Detection order is stable:
 * sing-box JSON, Mihomo YAML, then conventional proxy URLs.
 */
internal object OutboundImportPipeline {
    fun parse(
        content: String,
        jsonFormatter: SingBoxOutboundConfigFormatter =
            LibboxSingBoxOutboundConfigFormatter,
    ): OutboundImportResult {
        val outcome = parseOutcome(content, jsonFormatter)
        if (outcome.accepted.isEmpty()) {
            throw IllegalArgumentException(
                outcome.issues.firstOrNull()?.message ?: "No supported proxy outbounds found",
            )
        }
        return OutboundImportResult(
            format = outcome.format as OutboundImportFormat,
            outbounds = outcome.accepted,
        )
    }

    fun parseOutcome(
        content: String,
        jsonFormatter: SingBoxOutboundConfigFormatter =
            LibboxSingBoxOutboundConfigFormatter,
    ): ImportOutcome<ImportedSingBoxOutbound> {
        require(content.isNotBlank()) { "Outbound import content is empty" }
        requireImportTextWithinLimit(content)
        val candidates = buildList {
            add(content.trim().removePrefix("\uFEFF"))
            decodeBase64Payload(content)?.let { decoded ->
                if (decoded !in this) add(decoded)
            }
        }
        candidates.forEach { candidate ->
            if (candidate.isJsonDocument()) {
                return runCatching {
                    SingBoxOutboundImporter.parseImportOutcome(candidate, jsonFormatter)
                }.getOrElse {
                    invalidRecognizedOutboundDocument(
                        format = OutboundImportFormat.JSON,
                        message = "Invalid sing-box JSON import document",
                    )
                }
            }
        }
        candidates.forEach { candidate ->
            MihomoYamlOutboundParser.parseOutcomeOrNull(candidate)?.let { return it }
        }
        candidates.forEach { candidate ->
            ProxyUrlOutboundParser.parseOutcomeOrNull(candidate)?.let { return it }
        }
        throw IllegalArgumentException("No supported proxy outbounds found")
    }
}

private fun String.isJsonDocument(): Boolean =
    runCatching { SingBoxJson.parseToJsonElement(this) }
        .getOrNull()
        .let { element -> element is JsonObject || element is JsonArray } ||
        looksLikeJsonContainer()

private fun String.looksLikeJsonContainer(): Boolean {
    val trimmed = trimStart()
    if (trimmed.firstOrNull() == '{') return true
    if (trimmed.firstOrNull() != '[') return false
    val nextToken = trimmed.drop(1).firstOrNull { character -> !character.isWhitespace() }
        ?: return true
    return nextToken in setOf('{', '[', '"', ']', '-', 't', 'f', 'n') ||
        nextToken.isDigit()
}

private fun invalidRecognizedOutboundDocument(
    format: OutboundImportFormat,
    message: String,
): ImportOutcome<ImportedSingBoxOutbound> = ImportOutcome(
    format = format,
    detectedCount = 0,
    accepted = emptyList(),
    issues = listOf(
        ImportIssue(
            reason = ImportIssueReason.INVALID_DOCUMENT,
            severity = ImportIssueSeverity.ERROR,
            stage = ImportStage.PARSE,
            message = message,
        ),
    ),
)

private object MihomoYamlOutboundParser {
    private val loader = Load(LoadSettings.builder().build())

    fun parse(content: String): List<ImportedSingBoxOutbound> {
        val outcome = parseOutcomeOrNull(content)
            ?: throw IllegalArgumentException("Mihomo YAML must contain proxies")
        if (outcome.accepted.isEmpty()) {
            throw IllegalArgumentException(
                outcome.issues.firstOrNull()?.message ?: "No supported proxy outbounds found",
            )
        }
        return outcome.accepted
    }

    fun parseOutcomeOrNull(content: String): ImportOutcome<ImportedSingBoxOutbound>? {
        val loaded = runCatching { loader.loadFromString(content) as? Map<*, *> }
        val root = loaded.getOrNull()
        if (root == null) {
            return if (MihomoProxiesHeader.containsMatchIn(content)) {
                invalidRecognizedOutboundDocument(
                    format = OutboundImportFormat.YAML,
                    message = "Invalid Mihomo YAML import document",
                )
            } else {
                null
            }
        }
        if ("proxies" !in root) return null
        val proxies = root["proxies"] as? List<*>
            ?: return invalidRecognizedOutboundDocument(
                format = OutboundImportFormat.YAML,
                message = "Mihomo YAML proxies must be a list",
            )
        try {
            requireImportCandidateCount(proxies.size)
        } catch (_: ImportLimitException) {
            return tooManyOutboundCandidates(
                format = OutboundImportFormat.YAML,
                detectedCount = proxies.size,
            )
        }
        val issues = mutableListOf<ImportIssue>()
        val accepted = proxies.mapIndexedNotNull { index, value ->
            val proxy = value as? Map<*, *>
            if (proxy == null) {
                issues += rejectedOutboundCandidate(
                    reason = ImportIssueReason.INVALID_ENTRY,
                    sourceIndex = index,
                    message = "Mihomo proxy entry must be a mapping",
                )
                return@mapIndexedNotNull null
            }
            val sourceType = proxy.string("type").lowercase()
            val type = canonicalMihomoOutboundType(sourceType)
            if (type !in SupportedSingBoxProxyOutboundTypes) {
                issues += rejectedOutboundCandidate(
                    reason = ImportIssueReason.UNSUPPORTED_TYPE,
                    sourceIndex = index,
                    detectedType = sourceType.ifBlank { null },
                    message = "Mihomo proxy type is not supported",
                )
                return@mapIndexedNotNull null
            }
            val converted = runCatching { proxy.toSingBoxOutbound() }
                .getOrElse {
                    issues += rejectedOutboundCandidate(
                        reason = ImportIssueReason.INVALID_FIELD,
                        sourceIndex = index,
                        detectedType = type,
                        message = "Mihomo proxy fields are invalid",
                    )
                    return@mapIndexedNotNull null
                }
            if (converted == null) {
                issues += rejectedOutboundCandidate(
                    reason = ImportIssueReason.UNSUPPORTED_OPTION,
                    sourceIndex = index,
                    detectedType = type,
                    message = "Mihomo proxy uses an unsupported option",
                )
                return@mapIndexedNotNull null
            }
            runCatching {
                SingBoxOutboundImporter.parsePreparedOutbounds(listOf(converted))
                    .single()
                    .copy(sourceIndex = index)
            }.getOrElse {
                issues += rejectedOutboundCandidate(
                    reason = ImportIssueReason.INVALID_ENTRY,
                    sourceIndex = index,
                    detectedType = type,
                    message = "Mihomo proxy could not be normalized",
                )
                null
            }
        }
        return ImportOutcome(
            format = OutboundImportFormat.YAML,
            detectedCount = proxies.size,
            accepted = accepted,
            issues = issues,
        )
    }

    private fun Map<*, *>.toSingBoxOutbound(): JsonObject? {
        val sourceType = string("type").lowercase()
        val type = canonicalMihomoOutboundType(sourceType)
        if (type !in SupportedSingBoxProxyOutboundTypes) return null
        val server = string("server")
        val port = int("port")
        require(server.isNotBlank()) { "Mihomo proxy server is required" }
        require(port in 1..65535) { "Mihomo proxy port is invalid" }
        if (type == "shadowsocks" && !isSupportedShadowsocksPlugin(string("plugin"))) return null
        val network = string("network").ifBlank { string("transport") }.lowercase()
        if (type == "naive" && network.isNotBlank()) return null
        if (type in setOf("vmess", "vless", "trojan") &&
            network.isNotBlank() &&
            canonicalV2RayTransport(network) == null
        ) return null
        val vlessEncryption = string("encryption")
        if (type == "vless" &&
            vlessEncryption.isNotBlank() &&
            !vlessEncryption.equals("none", ignoreCase = true)
        ) return null
        if (type == "trojan" && map("ss-opts").bool("enabled")) return null
        if (type == "tuic" && string("token").isNotBlank()) return null
        if (string("fingerprint").isNotBlank()) return null
        if (
            listOf("shadow-tls-opts", "restls-opts", "jls-opts", "tlsmirror-opts")
                .any { option -> map(option).isNotEmpty() }
        ) return null
        val realityOptions = map("reality-opts")
        if (realityOptions.bool("support-x25519mlkem768")) return null
        if (realityOptions.isNotEmpty() && realityOptions.string("public-key").isBlank()) return null
        val ipVersion = string("ip-version").lowercase()
        if (ipVersion.isNotBlank() && ipVersion != "dual") return null
        val nameCertVerify = string("name-cert-verify")
        val configuredServerName = string("servername").ifBlank { string("sni") }
        if (
            nameCertVerify.isNotBlank() &&
            !nameCertVerify.equals(
                configuredServerName.ifBlank { server },
                ignoreCase = true,
            )
        ) return null
        if (type == "naive" && hasUnsupportedNaiveTlsOptions()) return null
        val certificate = string("certificate")
        val privateKey = string("private-key")
        if (
            type in TlsCapableOutboundTypes &&
            certificate.isBlank() != privateKey.isBlank()
        ) return null
        val tlsRequested = requestsTls(type)
        val tlsEnabled = bool("tls") ||
            string("security").let { security ->
                security.equals("tls", ignoreCase = true) ||
                    security.equals("reality", ignoreCase = true)
            } ||
            type in setOf(
                "naive",
                "trojan",
                "hysteria",
                "hysteria2",
                "tuic",
                "anytls",
                "shadowtls",
            )
        if (type !in TlsCapableOutboundTypes && tlsRequested) return null
        if (type in TlsCapableOutboundTypes && tlsRequested && !tlsEnabled) return null
        if (hasUnsupportedTransportOptions(network)) return null
        val name = string("name").ifBlank { "$type-$server:$port" }
        val snellVersion = int("version").takeIf { it > 0 } ?: 4
        if (type == "snell" && snellVersion != 4) return null
        if (type == "hysteria" &&
            string("protocol").isNotBlank() &&
            !string("protocol").equals("udp", ignoreCase = true)
        ) return null
        if (type == "hysteria2" &&
            string("obfs").isNotBlank() &&
            string("obfs").lowercase() !in setOf("salamander", "gecko")
        ) return null
        val serverPorts = if (type == "hysteria" || type == "hysteria2") {
            portRanges("ports")
        } else {
            emptyList()
        }
        if (stringList("ports").isNotEmpty() && serverPorts.isEmpty()) return null
        return buildJsonObject {
            put("type", type)
            put("tag", name)
            put("server", server)
            if (serverPorts.isEmpty()) {
                put("server_port", port)
            } else {
                put("server_ports", JsonArray(serverPorts.map(::JsonPrimitive)))
            }
            putIfTrue("tcp_fast_open", bool("tfo"))
            putIfTrue("tcp_multi_path", bool("mptcp"))
            putNotBlank("bind_interface", string("interface-name"))
            putPositive("routing_mark", int("routing-mark"))
            putNotBlank("detour", string("dialer-proxy"))
            when (type) {
                "socks" -> {
                    putNotBlank("version", if (sourceType == "socks") string("version") else "5")
                    putNotBlank("username", string("username"))
                    putNotBlank("password", string("password"))
                    putNotBlank("network", string("network"))
                    putUdpOverTcp(this@toSingBoxOutbound)
                }
                "http" -> {
                    putNotBlank("username", string("username"))
                    putNotBlank("password", string("password"))
                    putNotBlank("path", string("path"))
                    headers()?.let { put("headers", it) }
                }
                "naive" -> {
                    putNotBlank("username", string("username"))
                    putNotBlank("password", string("password"))
                    val rawConcurrency = firstValue(
                        "insecure-concurrency",
                        "insecure_concurrency",
                    )
                    val concurrency = rawConcurrency?.toString()?.toIntOrNull()
                    require(rawConcurrency == null || concurrency != null && concurrency >= 0) {
                        "Mihomo Naive insecure concurrency is invalid"
                    }
                    concurrency?.takeIf { it > 0 }?.let {
                        put("insecure_concurrency", it)
                    }
                    naiveExtraHeaders()?.let { put("extra_headers", it) }
                    val udpOverTcp = bool("udp-over-tcp") || bool("udp_over_tcp")
                    val udpOverTcpVersion = naiveYamlUdpOverTcpVersion()
                    require(udpOverTcpVersion == null || udpOverTcp) {
                        "Mihomo Naive UDP over TCP version requires UDP over TCP"
                    }
                    if (udpOverTcp) {
                        put("udp_over_tcp", buildJsonObject {
                            put("enabled", true)
                            udpOverTcpVersion?.let { put("version", it) }
                        })
                    }
                    putIfTrue("quic", bool("quic"))
                    val congestionControl = string("quic-congestion-control")
                        .ifBlank { string("quic_congestion_control") }
                        .lowercase()
                    require(congestionControl in NaiveQuicCongestionControls) {
                        "Unsupported Naive QUIC congestion control"
                    }
                    putNotBlank("quic_congestion_control", congestionControl)
                }
                "shadowsocks" -> {
                    putNotBlank("method", string("cipher").ifBlank { string("method") })
                    putNotBlank("password", string("password"))
                    val plugin = string("plugin")
                    val pluginOptions = map("plugin-opts")
                    if (plugin.isNotBlank()) {
                        val normalizedPlugin = normalizeShadowsocksPlugin(
                            plugin,
                            pluginOptions.entries.joinToString(";") { (key, value) ->
                                "$key=$value"
                            },
                        )
                        checkNotNull(normalizedPlugin)
                        put("plugin", normalizedPlugin.first)
                        putNotBlank("plugin_opts", normalizedPlugin.second)
                    }
                    putNotBlank("network", string("network"))
                    putUdpOverTcp(this@toSingBoxOutbound)
                }
                "vmess" -> {
                    putNotBlank("uuid", string("uuid"))
                    putNotBlank("security", string("cipher").ifBlank { string("security") })
                    putPositive(
                        "alter_id",
                        get("alterId")?.toString()?.toIntOrNull() ?: int("alter-id"),
                    )
                    putIfTrue("global_padding", bool("global-padding"))
                    putIfTrue("authenticated_length", bool("authenticated-length"))
                    putNotBlank("packet_encoding", string("packet-encoding"))
                }
                "trojan" -> putNotBlank("password", string("password"))
                "hysteria" -> {
                    putNotBlank("auth_str", string("auth-str").ifBlank { string("auth") })
                    putNotBlank("obfs", string("obfs"))
                    putHysteriaBandwidth("up", get("up") ?: get("upmbps"))
                    putHysteriaBandwidth("down", get("down") ?: get("downmbps"))
                    hopIntervals("hop-interval").let { (minimum, _) ->
                        putNotBlank("hop_interval", minimum)
                    }
                    putModernQuicFields(this@toSingBoxOutbound)
                }
                "vless" -> {
                    putNotBlank("uuid", string("uuid"))
                    putNotBlank("flow", string("flow"))
                    putNotBlank("packet_encoding", string("packet-encoding"))
                }
                "shadowtls" -> {
                    putPositive("version", int("version"))
                    putNotBlank("password", string("password"))
                }
                "tuic" -> {
                    putNotBlank("uuid", string("uuid"))
                    putNotBlank("password", string("password"))
                    putNotBlank("congestion_control", string("congestion-controller"))
                    putNotBlank("udp_relay_mode", string("udp-relay-mode"))
                    putIfTrue("udp_over_stream", bool("udp-over-stream"))
                    putIfTrue("zero_rtt_handshake", bool("reduce-rtt"))
                    putNotBlank("heartbeat", millisecondsDuration("heartbeat-interval"))
                    putModernQuicFields(this@toSingBoxOutbound)
                }
                "hysteria2" -> {
                    putNotBlank("password", string("password").ifBlank { string("auth") })
                    val obfs = string("obfs")
                    val obfsPassword = string("obfs-password")
                    if (obfs.isNotBlank() || obfsPassword.isNotBlank()) {
                        put("obfs", buildJsonObject {
                            putNotBlank("type", obfs)
                            putNotBlank("password", obfsPassword)
                            putPositive(
                                "min_packet_size",
                                this@toSingBoxOutbound.int("obfs-min-packet-size"),
                            )
                            putPositive(
                                "max_packet_size",
                                this@toSingBoxOutbound.int("obfs-max-packet-size"),
                            )
                        })
                    }
                    putPositive("up_mbps", bandwidthMbps(get("up") ?: get("upmbps")))
                    putPositive("down_mbps", bandwidthMbps(get("down") ?: get("downmbps")))
                    hopIntervals("hop-interval").let { (minimum, maximum) ->
                        putNotBlank("hop_interval", minimum)
                        putNotBlank("hop_interval_max", maximum)
                    }
                    putNotBlank("bbr_profile", string("bbr-profile"))
                    putModernQuicFields(this@toSingBoxOutbound)
                }
                "anytls" -> {
                    putNotBlank("password", string("password"))
                    putNotBlank(
                        "idle_session_check_interval",
                        secondsDuration("idle-session-check-interval"),
                    )
                    putNotBlank(
                        "idle_session_timeout",
                        secondsDuration("idle-session-timeout"),
                    )
                    putPositive("min_idle_session", int("min-idle-session"))
                }
                "snell" -> {
                    put("version", snellVersion)
                    putNotBlank("psk", string("psk"))
                    putNotBlank("userkey", string("userkey"))
                    putIfTrue("reuse", bool("reuse"))
                    putNotBlank("obfs_mode", string("obfs-opts", "mode"))
                    putNotBlank("obfs_host", string("obfs-opts", "host"))
                }
                "ssh" -> {
                    putNotBlank("user", string("username").ifBlank { string("user") })
                    putNotBlank("password", string("password"))
                    putListable("private_key", stringList("private-key"))
                    putNotBlank("private_key_passphrase", string("private-key-passphrase"))
                    putListable("host_key", stringList("host-key"))
                    putListable("host_key_algorithms", stringList("host-key-algorithms"))
                    putNotBlank("client_version", string("client-version"))
                    putListable("cipher", stringList("cipher"))
                    putListable("mac", stringList("mac"))
                    putListable("kex_algorithm", stringList("kex-algorithm"))
                }
            }
            if (containsKey("udp") && !bool("udp") &&
                type in setOf("socks", "shadowsocks", "vmess", "vless", "trojan")
            ) {
                put("network", "tcp")
            }
            val tls = if (type == "naive") {
                buildNaiveTls(this@toSingBoxOutbound)
            } else {
                buildTls(this@toSingBoxOutbound, type)
            }
            tls?.let { put("tls", it) }
            buildTransport(this@toSingBoxOutbound)?.let { put("transport", it) }
            if (type in setOf("shadowsocks", "vmess", "trojan", "vless")) {
                buildMultiplex(this@toSingBoxOutbound)?.let { put("multiplex", it) }
            }
        }
    }

    private fun buildNaiveTls(proxy: Map<*, *>): JsonObject = buildJsonObject {
        put("enabled", true)
        putNotBlank(
            "server_name",
            proxy.string("servername")
                .ifBlank { proxy.string("sni") }
                .ifBlank { proxy.string("name-cert-verify") },
        )
        val ech = proxy.map("ech-opts")
        val echConfig = ech.stringList("config")
        val echConfigPath = ech.string("config-path")
        val echQueryServerName = ech.string("query-server-name")
        if (
            ech.bool("enable") ||
            echConfig.isNotEmpty() ||
            echConfigPath.isNotBlank() ||
            echQueryServerName.isNotBlank()
        ) {
            put("ech", buildJsonObject {
                put("enabled", true)
                putListable("config", echConfig)
                putNotBlank("config_path", echConfigPath)
                putNotBlank("query_server_name", echQueryServerName)
            })
        }
    }

    private fun buildTls(proxy: Map<*, *>, type: String): JsonObject? {
        val serverName = proxy.string("servername")
            .ifBlank { proxy.string("sni") }
            .ifBlank { proxy.string("name-cert-verify") }
        val enabled = proxy.bool("tls") ||
            proxy.string("security").let { security ->
                security.equals("tls", ignoreCase = true) ||
                    security.equals("reality", ignoreCase = true)
            } ||
            type in setOf("trojan", "hysteria", "hysteria2", "tuic", "anytls", "shadowtls")
        val insecure = proxy.bool("skip-cert-verify")
        val fingerprint = proxy.string("client-fingerprint")
        val disableSni = proxy.bool("disable-sni")
        val reality = proxy.map("reality-opts")
        val ech = proxy.map("ech-opts")
        val certificate = proxy.string("certificate").takeIf {
            type in TlsCapableOutboundTypes
        }.orEmpty()
        val privateKey = proxy.string("private-key").takeIf {
            type in TlsCapableOutboundTypes
        }.orEmpty()
        if (!enabled) return null
        return buildJsonObject {
            put("enabled", true)
            putNotBlank("server_name", serverName)
            putIfTrue("insecure", insecure)
            putIfTrue("disable_sni", disableSni)
            val alpn = proxy.stringList("alpn")
            if (alpn.isNotEmpty()) put("alpn", JsonArray(alpn.map(::JsonPrimitive)))
            if (fingerprint.isNotBlank()) {
                put("utls", buildJsonObject {
                    put("enabled", true)
                    put("fingerprint", fingerprint)
                })
            }
            if (reality.isNotEmpty()) {
                put("reality", buildJsonObject {
                    put("enabled", true)
                    putNotBlank("public_key", reality.string("public-key"))
                    putNotBlank("short_id", reality.string("short-id"))
                })
            }
            if (ech.isNotEmpty() && (ech.bool("enable") || ech.stringList("config").isNotEmpty())) {
                put("ech", buildJsonObject {
                    put("enabled", true)
                    putListable("config", ech.stringList("config"))
                    putNotBlank("query_server_name", ech.string("query-server-name"))
                })
            }
            if (certificate.isNotBlank()) {
                if ("-----BEGIN" in certificate) {
                    put("client_certificate", certificate)
                    put("client_key", privateKey)
                } else {
                    put("client_certificate_path", certificate)
                    put("client_key_path", privateKey)
                }
            }
        }
    }

    private fun buildMultiplex(proxy: Map<*, *>): JsonObject? {
        val options = proxy.map("smux")
        if (!options.bool("enabled")) return null
        return buildJsonObject {
            put("enabled", true)
            options.string("protocol")
                .takeIf { it in setOf("smux", "yamux", "h2mux") }
                ?.let { put("protocol", it) }
            putPositive("max_connections", options.int("max-connections"))
            putPositive("min_streams", options.int("min-streams"))
            putPositive("max_streams", options.int("max-streams"))
            putIfTrue("padding", options.bool("padding"))
            val brutal = options.map("brutal-opts")
            if (brutal.bool("enabled")) {
                put("brutal", buildJsonObject {
                    put("enabled", true)
                    putPositive("up_mbps", bandwidthMbps(brutal["up"]))
                    putPositive("down_mbps", bandwidthMbps(brutal["down"]))
                })
            }
        }
    }

    private fun buildTransport(proxy: Map<*, *>): JsonObject? {
        val network = proxy.string("network")
            .ifBlank { proxy.string("transport") }
            .lowercase()
        val sourceType = canonicalV2RayTransport(network) ?: return null
        if (sourceType == "tcp") return null
        val options = proxy.map("$network-opts").ifEmpty {
            when (sourceType) {
                "ws" -> proxy.map("ws-opts")
                "grpc" -> proxy.map("grpc-opts")
                "http" -> proxy.map("h2-opts").ifEmpty { proxy.map("http-opts") }
                "httpupgrade" -> proxy.map("httpupgrade-opts")
                else -> emptyMap()
            }
        }
        val normalizedType = if (sourceType == "ws" && options.bool("v2ray-http-upgrade")) {
            "httpupgrade"
        } else {
            sourceType
        }
        if (normalizedType !in setOf("http", "ws", "quic", "grpc", "httpupgrade")) return null
        return buildJsonObject {
            put("type", normalizedType)
            when (normalizedType) {
                "http" -> {
                    options.stringList("host").takeIf(List<String>::isNotEmpty)?.let { hosts ->
                        put("host", JsonArray(hosts.map(::JsonPrimitive)))
                    }
                    putNotBlank("path", options.stringList("path").firstOrNull().orEmpty())
                    putNotBlank("method", options.string("method"))
                    putNotBlank("idle_timeout", options.string("idle-timeout"))
                    putNotBlank("ping_timeout", options.string("ping-timeout"))
                    options.headers()?.let { put("headers", it) }
                }
                "ws" -> {
                    putNotBlank("path", options.string("path"))
                    options.headers()?.let { put("headers", it) }
                    putPositive("max_early_data", options.int("max-early-data"))
                    putNotBlank(
                        "early_data_header_name",
                        options.string("early-data-header-name"),
                    )
                }
                "grpc" -> {
                    putNotBlank(
                        "service_name",
                        options.string("grpc-service-name").ifBlank {
                            options.string("service-name")
                        },
                    )
                    putNotBlank("idle_timeout", options.string("idle-timeout"))
                    putNotBlank("ping_timeout", options.string("ping-timeout"))
                    putIfTrue("permit_without_stream", options.bool("permit-without-stream"))
                }
                "httpupgrade" -> {
                    putNotBlank(
                        "host",
                        options.stringList("host").firstOrNull()
                            ?: options.headerValues("Host").firstOrNull().orEmpty(),
                    )
                    putNotBlank("path", options.string("path"))
                    options.headers(excludedNames = setOf("host"))?.let { put("headers", it) }
                }
                "quic" -> Unit
            }
        }
    }

    private fun Map<*, *>.requestsTls(type: String): Boolean =
        bool("tls") ||
            string("security").let { security ->
                security.equals("tls", ignoreCase = true) ||
                    security.equals("reality", ignoreCase = true)
            } ||
            listOf(
                "servername",
                "sni",
                "name-cert-verify",
                "client-fingerprint",
                "certificate",
            ).any { key -> string(key).isNotBlank() } ||
            (type != "ssh" && string("private-key").isNotBlank()) ||
            bool("skip-cert-verify") ||
            bool("disable-sni") ||
            map("reality-opts").isNotEmpty() ||
            map("ech-opts").isNotEmpty()

    private fun Map<*, *>.hasUnsupportedTransportOptions(network: String): Boolean {
        val normalized = if (network == "h2") "http" else network
        val options = map("$network-opts").ifEmpty {
            when (normalized) {
                "http" -> map("h2-opts").ifEmpty { map("http-opts") }
                "ws" -> map("ws-opts")
                "grpc" -> map("grpc-opts")
                "httpupgrade" -> map("httpupgrade-opts")
                "quic" -> map("quic-opts")
                else -> emptyMap()
            }
        }
        return when (normalized) {
            "grpc" -> options.string("authority").isNotBlank() ||
                options.string("mode").let { it.isNotBlank() && !it.equals("gun", true) }
            "quic" -> options.isNotEmpty()
            else -> false
        }
    }
}

private object ProxyUrlOutboundParser {
    private val urlPattern = Regex(
        """(?i)(?:socks(?:4a?|5h?)?|https?|ss|vmess|vless|trojan|hysteria|hy1|shadowtls|tuic|hysteria2|hy2|anytls|snell|ssh|tor|naive(?:\+(?:https|quic))?|wg|wireguard|awg|warp)://[^\s<>"']+""",
    )

    fun parse(content: String): List<ImportedSingBoxOutbound> {
        val outcome = parseOutcomeOrNull(content)
            ?: throw IllegalArgumentException("No proxy URLs found")
        if (outcome.accepted.isEmpty()) {
            throw IllegalArgumentException(
                outcome.issues.firstOrNull()?.message ?: "No supported proxy outbounds found",
            )
        }
        return outcome.accepted
    }

    fun parseOutcomeOrNull(content: String): ImportOutcome<ImportedSingBoxOutbound>? {
        val links = buildList {
            content.lineSequence().forEachIndexed { lineIndex, line ->
                urlPattern.findAll(line).forEach { match ->
                    add(
                        IndexedProxyLink(
                            sourceIndex = lineIndex,
                            value = match.value.trim().trimEnd(',', ';'),
                        ),
                    )
                }
            }
        }
        if (links.isEmpty()) return null
        try {
            requireImportCandidateCount(links.size)
        } catch (_: ImportLimitException) {
            return tooManyOutboundCandidates(
                format = OutboundImportFormat.URL,
                detectedCount = links.size,
            )
        }
        val issues = mutableListOf<ImportIssue>()
        val accepted = links.mapIndexedNotNull { candidateIndex, indexedLink ->
            val scheme = indexedLink.value.substringBefore("://").lowercase()
            if (scheme in RejectedOutboundUrlSchemes) {
                issues += rejectedOutboundCandidate(
                    reason = ImportIssueReason.UNSUPPORTED_TYPE,
                    sourceIndex = indexedLink.sourceIndex,
                    detectedType = scheme,
                    message = "This URL scheme is not supported for outbound import",
                )
                return@mapIndexedNotNull null
            }
            val converted = runCatching {
                parseLink(indexedLink.value, candidateIndex)
            }.getOrElse {
                issues += rejectedOutboundCandidate(
                    reason = ImportIssueReason.INVALID_FIELD,
                    sourceIndex = indexedLink.sourceIndex,
                    detectedType = scheme,
                    message = "Proxy URL fields are invalid",
                )
                return@mapIndexedNotNull null
            }
            if (converted == null) {
                issues += rejectedOutboundCandidate(
                    reason = ImportIssueReason.UNSUPPORTED_OPTION,
                    sourceIndex = indexedLink.sourceIndex,
                    detectedType = scheme,
                    message = "Proxy URL uses an unsupported option",
                )
                return@mapIndexedNotNull null
            }
            runCatching {
                SingBoxOutboundImporter.parsePreparedOutbounds(listOf(converted))
                    .single()
                    .copy(sourceIndex = indexedLink.sourceIndex)
            }.getOrElse {
                issues += rejectedOutboundCandidate(
                    reason = ImportIssueReason.INVALID_ENTRY,
                    sourceIndex = indexedLink.sourceIndex,
                    detectedType = scheme,
                    message = "Proxy URL could not be normalized",
                )
                null
            }
        }
        return ImportOutcome(
            format = OutboundImportFormat.URL,
            detectedCount = links.size,
            accepted = accepted,
            issues = issues,
        )
    }

    private fun parseLink(link: String, index: Int): JsonObject? {
        val scheme = link.substringBefore("://").lowercase()
        if (scheme == "vmess" && '@' !in link.substringAfter("://").substringBefore('#')) {
            return parseLegacyVmess(link, index)
        }
        if (scheme == "ss") return parseShadowsocks(link, index)
        val hopping = parsePortHoppingAuthority(link, scheme)
        val uri = URI(hopping?.normalizedLink ?: link)
        val query = parseQuery(uri.rawQuery)
        val type = canonicalProxyUrlOutboundType(scheme)
        if (type !in SupportedSingBoxProxyOutboundTypes) return null
        val host = (uri.host ?: extractBracketAwareHost(uri.rawAuthority))
            ?.removeSurrounding("[", "]")
        val port = uri.port.takeIf { it > 0 } ?: defaultPort(scheme)
        require(!host.isNullOrBlank()) { "Proxy URL server is required" }
        require(port in 1..65535) { "Proxy URL port is invalid" }
        val credentials = splitUserInfo(uri.rawUserInfo)
        val authentication = decodeComponent(uri.rawUserInfo)
        if (type == "hysteria" &&
            query.first("protocol").isNotBlank() &&
            !query.first("protocol").equals("udp", ignoreCase = true)
        ) return null
        if (type == "snell" && query.int("version").takeIf { it > 0 } != 4) return null
        val transportType = query.first("type", "network").lowercase()
        val normalizedTransportType = canonicalV2RayTransport(transportType)
        if (type in setOf("vmess", "vless", "trojan") &&
            transportType.isNotBlank() &&
            normalizedTransportType == null
        ) {
            throw IllegalArgumentException("Unsupported V2Ray transport: $transportType")
        }
        if (
            normalizedTransportType == "tcp" &&
            query.first("headerType", "header_type").let { header ->
                header.isNotBlank() && !header.equals("none", ignoreCase = true)
            }
        ) return null
        if (normalizedTransportType == "grpc" &&
            (
                query.first("authority").isNotBlank() ||
                    query.first("mode").let { mode ->
                        mode.isNotBlank() && !mode.equals("gun", ignoreCase = true)
                    }
            )
        ) return null
        if (normalizedTransportType == "quic" &&
            listOf("quicSecurity", "key", "headerType", "header_type")
                .any { key -> query.first(key).isNotBlank() }
        ) return null
        if (
            listOf("pinSHA256", "pcs", "vcn", "pqv", "fm")
                .any { key -> query.first(key).isNotBlank() }
        ) return null
        if (type == "naive") validateNaiveUrlOptions(query)
        val security = query.first("security", "tls")
        val tlsEnabled = scheme == "https" ||
            type in setOf(
                "naive",
                "trojan",
                "hysteria",
                "hysteria2",
                "shadowtls",
                "tuic",
                "anytls",
            ) ||
            security.equals("tls", ignoreCase = true) ||
            security.equals("reality", ignoreCase = true) ||
            query.bool("tls")
        val tlsOptionsPresent =
            listOf(
                "sni",
                "peer",
                "serverName",
                "servername",
                "allowInsecure",
                "allow_insecure",
                "insecure",
                "skip-cert-verify",
                "fp",
                "fingerprint",
                "pbk",
                "public-key",
                "public_key",
                "sid",
                "short-id",
                "short_id",
                "ech",
            ).any { key -> query.values(key).isNotEmpty() }
        if (type !in TlsCapableOutboundTypes && (tlsEnabled || tlsOptionsPresent)) return null
        if (type in TlsCapableOutboundTypes && tlsOptionsPresent && !tlsEnabled) return null
        if (security.equals("reality", ignoreCase = true) &&
            query.first("pbk", "public-key", "public_key").isBlank()
        ) return null
        val vlessEncryption = query.first("encryption")
        if (type == "vless" &&
            vlessEncryption.isNotBlank() &&
            !vlessEncryption.equals("none", ignoreCase = true)
        ) return null
        if (type == "hysteria2" &&
            query.first("obfs").let { obfs ->
                obfs.isNotBlank() && obfs.lowercase() !in setOf("salamander", "gecko")
            }
        ) return null
        if (type in setOf("vmess", "vless") && credentials.first.isBlank()) return null
        val queryServerPorts = if (type == "hysteria2") {
            normalizePortRanges(query.first("mport"))
        } else {
            emptyList()
        }
        if (type == "hysteria2" &&
            query.first("mport").isNotBlank() &&
            queryServerPorts.isEmpty()
        ) return null
        val serverPorts = hopping?.serverPorts ?: queryServerPorts
        val tag = decodeComponent(uri.rawFragment).ifBlank { "$type-${index + 1}" }
        return buildJsonObject {
            put("type", type)
            put("tag", tag)
            put("server", host)
            if (serverPorts.isEmpty()) {
                put("server_port", port)
            } else {
                put("server_ports", JsonArray(serverPorts.map(::JsonPrimitive)))
            }
            when (type) {
                "socks" -> {
                    put("version", when (scheme) {
                        "socks4" -> "4"
                        "socks4a" -> "4a"
                        else -> "5"
                    })
                    putNotBlank("username", credentials.first)
                    putNotBlank("password", credentials.second)
                }
                "http" -> {
                    putNotBlank("username", credentials.first)
                    putNotBlank("password", credentials.second)
                    putNotBlank("path", decodeComponent(uri.rawPath))
                }
                "naive" -> {
                    putNotBlank("username", credentials.first)
                    putNotBlank("password", credentials.second)
                    val rawConcurrency = query.first(
                        "insecure-concurrency",
                        "insecure_concurrency",
                    )
                    val concurrency = rawConcurrency.toIntOrNull()
                    require(
                        rawConcurrency.isBlank() ||
                            concurrency != null && concurrency >= 0,
                    ) {
                        "Naive URL insecure concurrency is invalid"
                    }
                    concurrency?.takeIf { it > 0 }?.let {
                        put("insecure_concurrency", it)
                    }
                    parseNaiveExtraHeaders(query)?.let { put("extra_headers", it) }
                    val udpOverTcp = query.naiveBoolean(
                        "UDP over TCP",
                        "udp-over-tcp",
                        "udp_over_tcp",
                    ) ?: false
                    val udpOverTcpVersion = query.naiveUdpOverTcpVersion()
                    require(udpOverTcpVersion == null || udpOverTcp) {
                        "Naive URL UDP over TCP version requires UDP over TCP"
                    }
                    if (udpOverTcp) {
                        put("udp_over_tcp", buildJsonObject {
                            put("enabled", true)
                            udpOverTcpVersion?.let { put("version", it) }
                        })
                    }
                    val queryQuic = query.naiveBoolean("QUIC", "quic")
                    require(scheme != "naive+quic" || queryQuic != false) {
                        "naive+quic URL cannot disable QUIC"
                    }
                    if (scheme == "naive+quic" || queryQuic == true) {
                        put("quic", true)
                    }
                    val congestionControl = query.first(
                        "quic-congestion-control",
                        "quic_congestion_control",
                    ).lowercase()
                    require(congestionControl in NaiveQuicCongestionControls) {
                        "Unsupported Naive QUIC congestion control"
                    }
                    putNotBlank("quic_congestion_control", congestionControl)
                }
                "vmess" -> {
                    put("uuid", credentials.first)
                    put(
                        "security",
                        query.first("encryption", "scy").ifBlank { "auto" },
                    )
                    putPositive("alter_id", query.int("alterId", "alter-id", "aid"))
                    putNotBlank(
                        "packet_encoding",
                        query.first("packetEncoding", "packet_encoding"),
                    )
                }
                "trojan" -> putNotBlank("password", authentication)
                "hysteria" -> {
                    putNotBlank("auth_str", authentication.ifBlank { query.first("auth") })
                    putNotBlank("obfs", query.first("obfsParam", "obfs-param"))
                    putPositive("up_mbps", query.int("upmbps", "up"))
                    putPositive("down_mbps", query.int("downmbps", "down"))
                }
                "vless" -> {
                    putNotBlank("uuid", credentials.first)
                    putNotBlank("flow", query.first("flow"))
                    putNotBlank("packet_encoding", query.first("packetEncoding", "packet_encoding"))
                }
                "shadowtls" -> {
                    putNotBlank("password", authentication)
                    putPositive("version", query.int("version"))
                }
                "tuic" -> {
                    putNotBlank("uuid", credentials.first)
                    putNotBlank("password", credentials.second)
                    putNotBlank("congestion_control", query.first("congestion_control", "congestion-controller"))
                    putNotBlank("udp_relay_mode", query.first("udp_relay_mode", "udp-relay-mode"))
                    putIfTrue("zero_rtt_handshake", query.bool("allow_insecure_0rtt", "reduce-rtt"))
                }
                "hysteria2" -> {
                    putNotBlank("password", authentication)
                    val obfsType = query.first("obfs")
                    val obfsPassword = query.first("obfs-password", "obfs_password")
                    if (obfsType.isNotBlank() || obfsPassword.isNotBlank()) {
                        put("obfs", buildJsonObject {
                            putNotBlank("type", obfsType)
                            putNotBlank("password", obfsPassword)
                        })
                    }
                    putPositive("up_mbps", query.int("upmbps", "up"))
                    putPositive("down_mbps", query.int("downmbps", "down"))
                }
                "anytls" -> putNotBlank("password", authentication)
                "snell" -> {
                    putNotBlank("psk", authentication)
                    put("version", 4)
                    putNotBlank("obfs_mode", query.first("obfs", "obfs_mode"))
                    putNotBlank("obfs_host", query.first("obfs-host", "obfs_host"))
                }
                "ssh" -> {
                    putNotBlank("user", credentials.first)
                    putNotBlank("password", credentials.second)
                    putNotBlank("private_key_path", query.first("private_key_path", "private-key-path"))
                    putNotBlank("private_key_passphrase", query.first("private_key_passphrase"))
                }
            }
            val tls = if (type == "naive") {
                buildNaiveUrlTls(query)
            } else {
                buildUrlTls(type, scheme, query)
            }
            tls?.let { put("tls", it) }
            if (type != "naive") {
                buildUrlTransport(query)?.let { put("transport", it) }
            }
        }
    }

    private fun parseLegacyVmess(link: String, index: Int): JsonObject {
        val encoded = link.substringAfter("://").substringBefore('#')
        val decoded = decodeBase64(encoded)
            ?: throw IllegalArgumentException("Invalid VMess URL payload")
        val source = SingBoxJson.parseToJsonElement(decoded) as? JsonObject
            ?: throw IllegalArgumentException("VMess URL payload must be JSON")
        val server = source.string("add")
        val port = source.int("port")
        require(server.isNotBlank() && port in 1..65535) { "Invalid VMess server" }
        require(source.string("v").let { it.isBlank() || it == "2" }) {
            "Unsupported VMess URL version"
        }
        require(source.string("id").isNotBlank()) { "VMess UUID is required" }
        val fragment = decodeComponent(link.substringAfter('#', ""))
        val tag = source.string("ps").ifBlank { fragment }.ifBlank { "vmess-${index + 1}" }
        val tlsMode = source.string("tls").lowercase()
        require(tlsMode.isBlank() || tlsMode in setOf("tls", "reality")) {
            "Unsupported VMess TLS mode"
        }
        val tlsEnabled = tlsMode in setOf("tls", "reality") || source.string("pbk").isNotBlank()
        val sourceTransportType = source.string("net").lowercase().ifBlank { "tcp" }
        val transportType = canonicalV2RayTransport(sourceTransportType)
        requireNotNull(transportType) { "Unsupported VMess transport" }
        val headerType = source.string("type")
        require(
            transportType !in setOf("tcp", "raw") ||
                headerType.isBlank() ||
                headerType.equals("none", ignoreCase = true),
        ) { "Unsupported VMess TCP header" }
        require(
            transportType != "grpc" ||
                (
                    source.string("host").isBlank() &&
                        headerType.let {
                            it.isBlank() ||
                                it.equals("none", true) ||
                                it.equals("gun", true)
                        }
                    ),
        ) { "Unsupported VMess gRPC options" }
        require(
            transportType != "quic" ||
                (
                    headerType.isBlank() &&
                        source.string("host").isBlank() &&
                        source.string("path").isBlank()
                    ),
        ) { "Unsupported VMess QUIC options" }
        require(
            listOf("pcs", "vcn", "pqv", "spx")
                .none { field -> source.string(field).isNotBlank() },
        ) { "Unsupported VMess security option" }
        require(!tlsMode.equals("reality") || source.string("pbk").isNotBlank()) {
            "VMess Reality public key is required"
        }
        val queryLike = mapOf(
            "security" to listOf(tlsMode),
            "sni" to listOf(source.string("sni")),
            "type" to listOf(transportType),
            "path" to listOf(source.string("path")),
            "host" to listOf(source.string("host")),
            "serviceName" to listOf(
                source.string("serviceName").ifBlank { source.string("path") },
            ),
            "fp" to listOf(source.string("fp")),
            "alpn" to listOf(source.string("alpn")),
            "insecure" to listOf(source.string("insecure")),
            "pbk" to listOf(source.string("pbk")),
            "sid" to listOf(source.string("sid")),
        )
        return buildJsonObject {
            put("type", "vmess")
            put("tag", tag)
            put("server", server)
            put("server_port", port)
            putNotBlank("uuid", source.string("id"))
            putNotBlank("security", source.string("scy").ifBlank { "auto" })
            putPositive("alter_id", source.int("aid"))
            if (tlsEnabled) buildUrlTls("vmess", "vmess", queryLike)?.let { put("tls", it) }
            buildUrlTransport(queryLike)?.let { put("transport", it) }
        }
    }

    private fun parseShadowsocks(link: String, index: Int): JsonObject? {
        val body = link.substringAfter("://")
        val fragment = decodeComponent(body.substringAfter('#', ""))
        val withoutFragment = body.substringBefore('#')
        val beforeQuery = withoutFragment.substringBefore('?')
        val rawQuery = withoutFragment.substringAfter('?', "")
        val decodedFull = if ('@' !in beforeQuery) decodeBase64(beforeQuery) else null
        val authority = decodedFull ?: beforeQuery
        val userInfo = authority.substringBeforeLast('@', "")
        val endpoint = authority.substringAfterLast('@', authority)
        val decodedUserInfo = decodeBase64(userInfo) ?: decodeComponent(userInfo)
        val method = decodedUserInfo.substringBefore(':')
        val password = decodedUserInfo.substringAfter(':', "")
        val endpointUri = URI("ss://$endpoint")
        val host = endpointUri.host ?: extractBracketAwareHost(endpoint)
        val port = endpointUri.port
        require(!host.isNullOrBlank() && port in 1..65535) { "Invalid Shadowsocks URL" }
        val query = parseQuery(rawQuery)
        val pluginValue = query.first("plugin")
        val plugin = if (pluginValue.isBlank()) {
            null
        } else {
            normalizeShadowsocksPlugin(
                pluginValue.substringBefore(';'),
                pluginValue.substringAfter(';', ""),
            ) ?: return null
        }
        return buildJsonObject {
            put("type", "shadowsocks")
            put("tag", fragment.ifBlank { "shadowsocks-${index + 1}" })
            put("server", host)
            put("server_port", port)
            putNotBlank("method", method)
            putNotBlank("password", password)
            if (plugin != null) {
                put("plugin", plugin.first)
                putNotBlank("plugin_opts", plugin.second)
            }
        }
    }

    private fun buildUrlTls(
        type: String,
        scheme: String,
        query: Map<String, List<String>>,
    ): JsonObject? {
        val security = query.first("security", "tls")
        val enabled = scheme == "https" ||
            type in setOf("trojan", "hysteria", "hysteria2", "shadowtls", "tuic", "anytls") ||
            security.equals("tls", true) ||
            security.equals("reality", true) ||
            query.bool("tls")
        val serverName = query.first("sni", "peer", "serverName", "servername")
        val insecure = query.bool(
            "allowInsecure",
            "allow_insecure",
            "insecure",
            "skip-cert-verify",
        )
        val fingerprint = query.first("fp", "fingerprint")
        val realityPublicKey = query.first("pbk", "public-key", "public_key")
        val realityShortId = query.first("sid", "short-id", "short_id")
        if (!enabled) return null
        return buildJsonObject {
            put("enabled", true)
            putNotBlank("server_name", serverName)
            putIfTrue("insecure", insecure)
            query.values("alpn")
                .flatMap { value -> value.split(',') }
                .filter(String::isNotBlank)
                .takeIf(List<String>::isNotEmpty)
                ?.let { values -> put("alpn", JsonArray(values.map(::JsonPrimitive))) }
            if (fingerprint.isNotBlank()) {
                put("utls", buildJsonObject {
                    put("enabled", true)
                    put("fingerprint", fingerprint)
                })
            }
            if (realityPublicKey.isNotBlank()) {
                put("reality", buildJsonObject {
                    put("enabled", true)
                    put("public_key", realityPublicKey)
                    putNotBlank("short_id", realityShortId)
                })
            }
            query.values("ech").takeIf(List<String>::isNotEmpty)?.let { configs ->
                put("ech", buildJsonObject {
                    put("enabled", true)
                    put("config", JsonArray(configs.map(::JsonPrimitive)))
                })
            }
        }
    }

    private fun buildNaiveUrlTls(query: Map<String, List<String>>): JsonObject =
        buildJsonObject {
            put("enabled", true)
            putNotBlank(
                "server_name",
                query.first("sni", "peer", "serverName", "servername"),
            )
            query.allValues("ech")
                .filter(String::isNotBlank)
                .takeIf(List<String>::isNotEmpty)
                ?.let { configs ->
                put("ech", buildJsonObject {
                    put("enabled", true)
                    put("config", JsonArray(configs.map(::JsonPrimitive)))
                })
            }
        }

    private fun validateNaiveUrlOptions(query: Map<String, List<String>>) {
        val unsupportedParameter = query.keys.firstOrNull { name ->
            name.lowercase() !in NaiveUrlQueryParameters
        }
        require(unsupportedParameter == null) {
            "Unsupported Naive URL parameter: $unsupportedParameter"
        }
        query.allValues("security").forEach { security ->
            require(security.isBlank() || security.equals("tls", ignoreCase = true)) {
                "Naive URL only supports TLS"
            }
        }
        query.allValues("tls").forEach { tls ->
            require(
                tls.isBlank() ||
                    tls == "1" ||
                    tls.equals("true", ignoreCase = true) ||
                    tls.equals("tls", ignoreCase = true),
            ) {
                "Naive URL requires TLS"
            }
        }
    }

    private fun parseNaiveExtraHeaders(
        query: Map<String, List<String>>,
    ): JsonObject? {
        val encodedHeaders = query.first("extra-headers", "extra_headers")
        if (encodedHeaders.isBlank()) return null
        val headers = linkedMapOf<String, String>()
        encodedHeaders
            .replace("\r\n", "\n")
            .split('\n')
            .filter(String::isNotBlank)
            .forEach { line ->
                val separator = line.indexOf(':')
                require(separator > 0) { "Invalid Naive extra header" }
                val name = line.substring(0, separator).trim()
                require(name.isHttpHeaderName()) { "Invalid Naive extra header name" }
                headers[name] = line.substring(separator + 1).trim()
            }
        require(headers.isNotEmpty()) { "Naive extra headers are empty" }
        return buildJsonObject {
            headers.forEach { (name, value) -> put(name, value) }
        }
    }

    private fun buildUrlTransport(query: Map<String, List<String>>): JsonObject? {
        val type = canonicalV2RayTransport(query.first("type", "network")) ?: return null
        if (type == "tcp") return null
        return buildJsonObject {
            put("type", type)
            when (type) {
                "http" -> {
                    query.values("host")
                        .flatMap { value -> value.split(',') }
                        .filter(String::isNotBlank)
                        .takeIf(List<String>::isNotEmpty)
                        ?.let { hosts -> put("host", JsonArray(hosts.map(::JsonPrimitive))) }
                    putNotBlank("path", query.first("path"))
                    putNotBlank("method", query.first("method"))
                }
                "ws" -> {
                    putNotBlank("path", query.first("path"))
                    query.first("host").takeIf(String::isNotBlank)?.let { host ->
                        put("headers", buildJsonObject { put("Host", host) })
                    }
                    putPositive("max_early_data", query.int("ed", "max-early-data"))
                    putNotBlank(
                        "early_data_header_name",
                        query.first("eh", "early-data-header-name"),
                    )
                }
                "grpc" -> {
                    putNotBlank(
                        "service_name",
                        query.first("serviceName", "service_name", "service-name"),
                    )
                }
                "httpupgrade" -> {
                    putNotBlank("host", query.first("host"))
                    putNotBlank("path", query.first("path"))
                }
                "quic" -> Unit
            }
        }
    }
}

private fun decodeBase64Payload(content: String): String? {
    val compact = content.trim().filterNot(Char::isWhitespace)
    if (compact.length < 16 || !compact.matches(Regex("""[A-Za-z0-9_+/=-]+"""))) return null
    return decodeBase64ImportPayload(compact)?.takeIf { decoded ->
        decoded.isNotBlank() && decoded.count(Char::isISOControl) <= decoded.length / 20
    }
}

private val MihomoProxiesHeader = Regex("""(?m)^\s*proxies\s*:""", RegexOption.IGNORE_CASE)

internal fun decodeBase64ImportPayload(
    value: String,
    maxDecodedBytes: Int = MaxImportBytes,
): String? {
    val compact = value.trim().filterNot(Char::isWhitespace)
    val padded = compact + "=".repeat((4 - compact.length % 4) % 4)
    return sequenceOf(Base64.UrlSafe, Base64.Default)
        .mapNotNull { decoder ->
            runCatching { decoder.decode(padded) }.getOrNull()
        }
        .firstOrNull()
        ?.let { decoded ->
            if (decoded.size > maxDecodedBytes) {
                throw ImportLimitException(
                    reason = ImportIssueReason.INPUT_TOO_LARGE,
                    message = "Decoded import content exceeds the allowed size",
                )
            }
            decoded.decodeToString()
        }
}

private fun decodeBase64(value: String): String? {
    val compact = value.trim().filterNot(Char::isWhitespace)
    val padded = compact + "=".repeat((4 - compact.length % 4) % 4)
    return sequenceOf(Base64.UrlSafe, Base64.Default)
        .mapNotNull { decoder ->
            runCatching {
                decoder.decode(padded).decodeToString()
            }.getOrNull()
        }
        .firstOrNull()
}

private data class IndexedProxyLink(
    val sourceIndex: Int,
    val value: String,
)

private val RejectedOutboundUrlSchemes =
    setOf("tor", "wg", "wireguard", "awg", "warp")

private fun rejectedOutboundCandidate(
    reason: ImportIssueReason,
    sourceIndex: Int,
    message: String,
    detectedType: String? = null,
): ImportIssue = ImportIssue(
    reason = reason,
    severity = ImportIssueSeverity.ERROR,
    stage = ImportStage.PARSE,
    sourceIndex = sourceIndex,
    detectedType = detectedType,
    message = message,
)

private fun tooManyOutboundCandidates(
    format: OutboundImportFormat,
    detectedCount: Int,
): ImportOutcome<ImportedSingBoxOutbound> = ImportOutcome(
    format = format,
    detectedCount = detectedCount,
    accepted = emptyList(),
    issues = listOf(
        ImportIssue(
            reason = ImportIssueReason.TOO_MANY_CANDIDATES,
            severity = ImportIssueSeverity.ERROR,
            stage = ImportStage.PARSE,
            message = "Import contains too many outbound candidates",
        ),
    ),
)

private fun parseQuery(rawQuery: String?): Map<String, List<String>> {
    if (rawQuery.isNullOrBlank()) return emptyMap()
    return rawQuery.split('&')
        .mapNotNull { part ->
            val key = decodeComponent(part.substringBefore('='))
            if (key.isBlank()) null else key to decodeComponent(part.substringAfter('=', ""))
        }
        .groupBy({ it.first }, { it.second })
}

private data class PortHoppingLink(
    val normalizedLink: String,
    val serverPorts: List<String>,
)

private fun parsePortHoppingAuthority(link: String, scheme: String): PortHoppingLink? {
    if (scheme != "hysteria2" && scheme != "hy2") return null
    val authorityStart = link.indexOf("://").takeIf { it >= 0 }?.plus(3) ?: return null
    val authorityEnd = sequenceOf(
        link.indexOf('/', authorityStart),
        link.indexOf('?', authorityStart),
        link.indexOf('#', authorityStart),
    ).filter { it >= 0 }.minOrNull() ?: link.length
    val authority = link.substring(authorityStart, authorityEnd)
    val userInfo = authority.substringBeforeLast('@', "")
    val endpoint = authority.substringAfterLast('@')
    val host = if (endpoint.startsWith('[')) {
        endpoint.substringBefore(']') + "]"
    } else {
        endpoint.substringBefore(':')
    }
    val portList = if (endpoint.startsWith('[')) {
        endpoint.substringAfter(']', "").removePrefix(":")
    } else {
        endpoint.substringAfter(':', "")
    }
    if (',' !in portList && '-' !in portList) return null
    val serverPorts = normalizePortRanges(portList)
    if (serverPorts.isEmpty()) return null
    val firstPort = serverPorts.firstOrNull()
        ?.substringBefore(':')
        ?.toIntOrNull()
        ?.takeIf { it in 1..65535 }
        ?: return null
    val normalizedAuthority = buildString {
        if (userInfo.isNotBlank()) append(userInfo).append('@')
        append(host).append(':').append(firstPort)
    }
    return PortHoppingLink(
        normalizedLink = link.replaceRange(authorityStart, authorityEnd, normalizedAuthority),
        serverPorts = serverPorts,
    )
}

private fun splitUserInfo(rawUserInfo: String?): Pair<String, String> {
    val raw = rawUserInfo.orEmpty()
    if (':' in raw) {
        return decodeComponent(raw.substringBefore(':')) to
            decodeComponent(raw.substringAfter(':', ""))
    }
    val decoded = decodeBase64(decodeComponent(raw))?.takeIf { ':' in it }
    return if (decoded != null) {
        decoded.substringBefore(':') to decoded.substringAfter(':', "")
    } else {
        decodeComponent(raw) to ""
    }
}

private fun normalizePortRanges(value: String): List<String> =
    value.split(',')
        .map(String::trim)
        .filter(String::isNotBlank)
        .let { items ->
            val result = mutableListOf<String>()
            items.forEach { item ->
                val normalized = item.replace(Regex("""^(\d+)-(\d+)$"""), "$1:$2")
                val bounds = normalized.split(':')
                    .mapNotNull(String::toIntOrNull)
                when {
                    bounds.size == 1 && bounds[0] in 1..65535 -> Unit
                    bounds.size == 2 &&
                        bounds[0] in 1..65535 &&
                        bounds[1] in bounds[0]..65535 -> Unit
                    else -> return emptyList()
                }
                result += normalized
            }
            result
        }

private fun extractBracketAwareHost(authority: String?): String? {
    val endpoint = authority?.substringAfterLast('@') ?: return null
    return if (endpoint.startsWith('[')) endpoint.substringAfter('[').substringBefore(']')
    else endpoint.substringBeforeLast(':', endpoint)
}

private fun defaultPort(scheme: String): Int = when (scheme) {
    "http" -> 80
    "https", "hysteria2", "hy2", "naive", "naive+https", "naive+quic" -> 443
    "ssh" -> 22
    else -> -1
}

private fun String.isHttpHeaderName(): Boolean =
    isNotBlank() && all { character ->
        character.isLetterOrDigit() || character in "!#$%&'*+-.^_`|~"
    }

private fun decodeComponent(value: String?): String {
    if (value.isNullOrBlank()) return ""
    return runCatching {
        URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8.name())
    }.getOrDefault(value)
}

private fun JsonObject.string(name: String): String =
    (get(name) as? JsonPrimitive)?.contentOrNull.orEmpty()

private fun JsonObject.int(name: String): Int =
    (get(name) as? JsonPrimitive)?.intOrNull ?: string(name).toIntOrNull() ?: 0

private fun Map<String, List<String>>.first(vararg names: String): String =
    names.firstNotNullOfOrNull { name ->
        entries.firstOrNull { entry -> entry.key.equals(name, ignoreCase = true) }
            ?.value
            ?.firstOrNull()
            ?.takeIf(String::isNotBlank)
    }.orEmpty()

private fun Map<String, List<String>>.values(vararg names: String): List<String> =
    names.firstNotNullOfOrNull { name ->
        entries.firstOrNull { entry -> entry.key.equals(name, ignoreCase = true) }
            ?.value
            ?.filter(String::isNotBlank)
            ?.takeIf(List<String>::isNotEmpty)
    }.orEmpty()

private fun Map<String, List<String>>.allValues(vararg names: String): List<String> =
    entries
        .filter { entry -> names.any { name -> entry.key.equals(name, ignoreCase = true) } }
        .flatMap { entry -> entry.value }

private fun Map<String, List<String>>.naiveBoolean(
    label: String,
    vararg names: String,
): Boolean? {
    val values = allValues(*names)
    if (values.isEmpty()) return null
    val parsed = values.map { value ->
        when {
            value == "1" || value.equals("true", ignoreCase = true) -> true
            value == "0" || value.equals("false", ignoreCase = true) -> false
            else -> throw IllegalArgumentException("Naive URL $label is invalid")
        }
    }
    require(parsed.distinct().size == 1) {
        "Naive URL $label has conflicting values"
    }
    return parsed.first()
}

private fun Map<String, List<String>>.naiveUdpOverTcpVersion(): Int? {
    val values = allValues("udp-over-tcp-version", "udp_over_tcp_version")
    if (values.isEmpty()) return null
    require(values.size == 1) {
        "Naive URL UDP over TCP version must be specified once"
    }
    return values.single().toIntOrNull()?.takeIf { it in 1..2 }
        ?: throw IllegalArgumentException("Naive URL UDP over TCP version is invalid")
}

private fun Map<String, List<String>>.int(vararg names: String): Int =
    first(*names).filter(Char::isDigit).toIntOrNull() ?: 0

private fun Map<String, List<String>>.bool(vararg names: String): Boolean =
    first(*names).let { it == "1" || it.equals("true", true) }

private fun Map<*, *>.string(name: String): String = get(name)?.toString().orEmpty()

private fun Map<*, *>.string(parent: String, name: String): String =
    map(parent).string(name)

private fun Map<*, *>.firstValue(vararg names: String): Any? =
    names.firstNotNullOfOrNull { name ->
        entries.firstOrNull { entry ->
            entry.key?.toString()?.equals(name, ignoreCase = true) == true
        }?.value
    }

private fun Map<*, *>.naiveYamlUdpOverTcpVersion(): Int? {
    val rawVersion = firstValue(
        "udp-over-tcp-version",
        "udp_over_tcp_version",
    ) ?: return null
    return rawVersion.toString().toIntOrNull()?.takeIf { it in 1..2 }
        ?: throw IllegalArgumentException("Mihomo Naive UDP over TCP version is invalid")
}

private fun Map<*, *>.int(name: String): Int = when (val value = get(name)) {
    is Number -> value.toInt()
    else -> value?.toString()?.toIntOrNull() ?: 0
}

private fun Map<*, *>.bool(name: String): Boolean = when (val value = get(name)) {
    is Boolean -> value
    is Number -> value.toInt() != 0
    else -> value?.toString()?.let { it == "1" || it.equals("true", true) } == true
}

private fun Map<*, *>.map(name: String): Map<*, *> = get(name) as? Map<*, *> ?: emptyMap<Any?, Any?>()

private fun Map<*, *>.hasUnsupportedNaiveTlsOptions(): Boolean {
    val tlsValue = firstValue("tls")
    val security = string("security")
    return tlsValue != null && !bool("tls") ||
        security.isNotBlank() && !security.equals("tls", ignoreCase = true) ||
        bool("skip-cert-verify") ||
        bool("disable-sni") ||
        string("client-fingerprint").isNotBlank() ||
        stringList("alpn").isNotEmpty() ||
        map("reality-opts").isNotEmpty() ||
        string("certificate").isNotBlank() ||
        string("private-key").isNotBlank()
}

private fun Map<*, *>.naiveExtraHeaders(): JsonObject? {
    val rawHeaders = firstValue("extra-headers", "extra_headers") ?: return null
    val headers = rawHeaders as? Map<*, *>
        ?: throw IllegalArgumentException("Mihomo Naive extra headers must be a mapping")
    if (headers.isEmpty()) return null
    return buildJsonObject {
        headers.forEach { (key, value) ->
            val name = key?.toString()?.trim().orEmpty()
            require(name.isHttpHeaderName()) {
                "Mihomo Naive extra header name is invalid"
            }
            when (value) {
                is Iterable<*> -> {
                    val values = value.mapNotNull { item ->
                        item?.toString()?.takeIf(String::isNotBlank)
                    }
                    require(values.isNotEmpty()) {
                        "Mihomo Naive extra header value is required"
                    }
                    put(name, JsonArray(values.map(::JsonPrimitive)))
                }
                null -> throw IllegalArgumentException(
                    "Mihomo Naive extra header value is required",
                )
                else -> put(name, value.toString())
            }
        }
    }
}

private fun Map<*, *>.list(name: String): List<*> = get(name) as? List<*> ?: emptyList<Any?>()

private fun Map<*, *>.stringList(name: String): List<String> = when (val value = get(name)) {
    is Iterable<*> -> value.mapNotNull { item -> item?.toString()?.takeIf(String::isNotBlank) }
    null -> emptyList()
    else -> listOf(value.toString()).filter(String::isNotBlank)
}

private fun Map<*, *>.headerValues(name: String): List<String> {
    val headers = map("headers")
    val value = headers.entries
        .firstOrNull { entry -> entry.key?.toString()?.equals(name, ignoreCase = true) == true }
        ?.value
    return when (value) {
        is Iterable<*> -> value.mapNotNull { item ->
            item?.toString()?.takeIf(String::isNotBlank)
        }
        null -> emptyList()
        else -> listOf(value.toString()).filter(String::isNotBlank)
    }
}

private fun Map<*, *>.headers(excludedNames: Set<String> = emptySet()): JsonObject? {
    val headers = map("headers")
    if (headers.isEmpty()) return null
    return buildJsonObject {
        headers.forEach { (key, value) ->
            if (key == null || value == null) return@forEach
            if (excludedNames.any { name -> key.toString().equals(name, ignoreCase = true) }) {
                return@forEach
            }
            when (value) {
                is Iterable<*> -> {
                    val values = value.mapNotNull { item ->
                        item?.toString()?.takeIf(String::isNotBlank)
                    }
                    if (values.isNotEmpty()) {
                        put(key.toString(), JsonArray(values.map(::JsonPrimitive)))
                    }
                }
                else -> value.toString().takeIf(String::isNotBlank)?.let { text ->
                    put(key.toString(), text)
                }
            }
        }
    }.takeIf(JsonObject::isNotEmpty)
}

private fun canonicalShadowsocksPlugin(plugin: String): String? =
    when (plugin.lowercase()) {
        "obfs", "obfs-local", "simple-obfs" -> "obfs-local"
        "v2ray", "v2ray-plugin" -> "v2ray-plugin"
        else -> null
    }

private fun isSupportedShadowsocksPlugin(plugin: String): Boolean =
    plugin.isBlank() || canonicalShadowsocksPlugin(plugin) != null

private fun normalizeShadowsocksPlugin(
    plugin: String,
    options: String,
): Pair<String, String>? {
    val normalizedName = canonicalShadowsocksPlugin(plugin) ?: return null
    if (normalizedName != "obfs-local") return normalizedName to options
    val normalizedOptions = options.split(';')
        .map(String::trim)
        .filter(String::isNotBlank)
        .map { option ->
            val key = option.substringBefore('=')
            val value = option.substringAfter('=', "")
            when {
                key.equals("mode", ignoreCase = true) -> "obfs=$value"
                key.equals("host", ignoreCase = true) -> "obfs-host=$value"
                else -> option
            }
        }
        .joinToString(";")
    return normalizedName to normalizedOptions
}

private fun Map<*, *>.portRanges(name: String): List<String> =
    normalizePortRanges(stringList(name).joinToString(","))

private fun Map<*, *>.hopIntervals(name: String): Pair<String, String> {
    val value = get(name)?.toString()?.trim().orEmpty()
    if (value.isBlank()) return "" to ""
    val range = Regex("""^(\d+(?:\.\d+)?)(?:s)?-(\d+(?:\.\d+)?)(?:s)?$""")
        .matchEntire(value)
    return if (range != null) {
        "${range.groupValues[1]}s" to "${range.groupValues[2]}s"
    } else {
        value.asSecondsDuration() to ""
    }
}

private fun Map<*, *>.secondsDuration(name: String): String =
    get(name)?.toString()?.trim().orEmpty().asSecondsDuration()

private fun Map<*, *>.millisecondsDuration(name: String): String {
    val value = get(name)?.toString()?.trim().orEmpty()
    if (value.isBlank()) return ""
    if (value.any(Char::isLetter)) return value
    val milliseconds = value.toLongOrNull() ?: return ""
    return if (milliseconds % 1_000L == 0L) {
        "${milliseconds / 1_000L}s"
    } else {
        "${milliseconds}ms"
    }
}

private fun String.asSecondsDuration(): String {
    if (isBlank()) return ""
    return if (any(Char::isLetter)) this else "${this}s"
}

private fun bandwidthMbps(value: Any?): Int {
    if (value is Number) return value.toInt().coerceAtLeast(0)
    val text = value?.toString()?.trim().orEmpty()
    text.toIntOrNull()?.let { return it.coerceAtLeast(0) }
    val match = Regex(
        """^(\d+(?:\.\d+)?)\s*([KMGT]?)([bB])ps$""",
        RegexOption.IGNORE_CASE,
    ).matchEntire(text) ?: return 0
    val amount = match.groupValues[1].toDoubleOrNull() ?: return 0
    val prefixFactor = when (match.groupValues[2].uppercase()) {
        "K" -> 0.001
        "M" -> 1.0
        "G" -> 1_000.0
        "T" -> 1_000_000.0
        else -> 0.000001
    }
    val byteFactor = if (match.groupValues[3] == "B") 8.0 else 1.0
    return kotlin.math.round(amount * prefixFactor * byteFactor)
        .toInt()
        .coerceAtLeast(0)
}

private fun kotlinx.serialization.json.JsonObjectBuilder.putHysteriaBandwidth(
    name: String,
    value: Any?,
) {
    when (value) {
        is Number -> putPositive("${name}_mbps", value.toInt())
        null -> Unit
        else -> {
            val text = value.toString().trim()
            val numeric = text.toIntOrNull()
            if (numeric != null) {
                putPositive("${name}_mbps", numeric)
            } else {
                putNotBlank(name, text)
            }
        }
    }
}

private fun kotlinx.serialization.json.JsonObjectBuilder.putUdpOverTcp(proxy: Map<*, *>) {
    if (!proxy.bool("udp-over-tcp")) return
    val version = proxy.int("udp-over-tcp-version")
    if (version > 0) {
        put("udp_over_tcp", buildJsonObject {
            put("enabled", true)
            put("version", version)
        })
    } else {
        put("udp_over_tcp", true)
    }
}

private fun kotlinx.serialization.json.JsonObjectBuilder.putModernQuicFields(proxy: Map<*, *>) {
    val streamWindow = proxy.int("max-stream-receive-window")
        .takeIf { it > 0 }
        ?: proxy.int("recv-window-conn")
    val connectionWindow = proxy.int("max-connection-receive-window")
        .takeIf { it > 0 }
        ?: proxy.int("recv-window")
    putPositive("stream_receive_window", streamWindow)
    putPositive("connection_receive_window", connectionWindow)
    if (proxy.bool("disable-mtu-discovery") || proxy.bool("disable_mtu_discovery")) {
        put("disable_path_mtu_discovery", true)
    }
}

private fun kotlinx.serialization.json.JsonObjectBuilder.putListable(
    name: String,
    values: List<String>,
) {
    if (values.isNotEmpty()) put(name, JsonArray(values.map(::JsonPrimitive)))
}

private fun kotlinx.serialization.json.JsonObjectBuilder.putNotBlank(name: String, value: String) {
    if (value.isNotBlank()) put(name, value)
}

private fun kotlinx.serialization.json.JsonObjectBuilder.putPositive(name: String, value: Int) {
    if (value > 0) put(name, value)
}

private fun kotlinx.serialization.json.JsonObjectBuilder.putIfTrue(name: String, value: Boolean) {
    if (value) put(name, true)
}
