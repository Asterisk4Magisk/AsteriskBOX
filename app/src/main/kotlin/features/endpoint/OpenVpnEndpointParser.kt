// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.endpoint

import engine.singbox.config.SingBoxJson
import features.importing.ImportIssue
import features.importing.ImportIssueReason
import features.importing.ImportIssueSeverity
import features.importing.ImportMutation
import features.importing.ImportMutationCode
import features.importing.ImportOutcome
import features.importing.ImportStage
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal object OpenVpnEndpointParser {
    fun parseOutcomeOrNull(content: String): RecognizedEndpointImport? {
        if (!looksLikeOpenVpn(content)) return null
        return RecognizedEndpointImport(
            runCatching { parseOpenVpn(content) }.getOrElse { error ->
                ImportOutcome(
                    format = EndpointImportFormat.OPENVPN,
                    detectedCount = 1,
                    accepted = emptyList(),
                    issues = listOf(
                        ImportIssue(
                            reason = ImportIssueReason.UNSAFE_EXTERNAL_REFERENCE,
                            severity = ImportIssueSeverity.ERROR,
                            stage = ImportStage.PARSE,
                            sourceIndex = 0,
                            detectedType = "openvpn-client",
                            message = error.message ?: "Invalid OpenVPN profile",
                        ),
                    ),
                )
            },
        )
    }
}

private fun looksLikeOpenVpn(content: String): Boolean =
    content.lineSequence().map(String::trim).any { line ->
        val key = line.substringBefore(' ').lowercase()
        key in setOf("client", "remote", "proto", "dev", "<ca>", "<cert>", "<key>")
    }

private fun parseOpenVpn(content: String): ImportOutcome<ImportedSingBoxEndpoint> {
    val parsed = tokenizeOpenVpn(content)
    val directives = parsed.directives
    val mutations = mutableListOf<ImportMutation>()
    val noOps = directives.filter { it.name in OpenVpnProcessOnlyDirectives }
    noOps.forEach {
        mutations += ImportMutation(
            code = ImportMutationCode.IGNORED_FIELD,
            message = "Ignored harmless OpenVPN process directive: ${it.name}",
        )
    }
    val unknown = directives.firstOrNull {
        it.name !in OpenVpnSupportedDirectives &&
            it.name !in OpenVpnProcessOnlyDirectives
    }
    require(unknown == null) {
        "Unsupported OpenVPN directive: ${unknown?.name}"
    }
    val unsafe = directives.firstOrNull { it.name in OpenVpnUnsafeDirectives }
    require(unsafe == null) {
        "Unsafe OpenVPN directive is not supported: ${unsafe?.name}"
    }
    parsed.blocks.keys.forEach { name ->
        require(name in OpenVpnInlineBlocks) { "Unsupported OpenVPN inline block" }
    }
    fun directive(name: String): OpenVpnDirective? =
        directives.lastOrNull { it.name == name }
    fun directives(name: String): List<OpenVpnDirective> =
        directives.filter { it.name == name }

    listOf("ca", "cert", "key").forEach { name ->
        directive(name)?.let { item ->
            require(
                parsed.blocks[name] != null &&
                    (
                        item.arguments.isEmpty() ||
                            item.arguments.singleOrNull()?.lowercase() in OpenVpnInlineMarkers
                        )
            ) {
                "External OpenVPN $name files are not supported"
            }
        }
    }
    directive("auth-user-pass")?.let { item ->
        require(
            item.arguments.isEmpty() ||
                item.arguments.singleOrNull()?.lowercase() in OpenVpnInlineMarkers
        ) {
            "External OpenVPN authentication files are not supported"
        }
    }
    val remotes = directives("remote")
    require(remotes.isNotEmpty()) { "OpenVPN profile has no remote server" }
    val defaultNetwork = normalizeOpenVpnNetwork(
        directive("proto")?.singleArgument("OpenVPN proto") ?: "udp",
    )
    val servers = remotes.map { remote ->
        require(remote.arguments.size in 1..3) { "OpenVPN remote is malformed" }
        val port = remote.arguments.getOrNull(1)
            ?.toIntIn("OpenVPN remote port", 1, 65_535)
            ?: 1194
        require(port in 1..65_535) { "OpenVPN remote port is invalid" }
        val network = remote.arguments.getOrNull(2)
            ?.let(::normalizeOpenVpnNetwork)
            ?: defaultNetwork
        buildJsonObject {
            put("server", remote.arguments.first())
            put("server_port", port)
            put("network", network)
        }
    }
    val ca = parsed.blocks["ca"]
    val fingerprints = directives("peer-fingerprint").flatMap { item ->
        item.arguments.map(::normalizeOpenVpnFingerprint)
    }
    require(ca != null || fingerprints.isNotEmpty()) {
        "OpenVPN profile requires an inline CA or peer fingerprint"
    }
    val certificate = parsed.blocks["cert"]
    val privateKey = parsed.blocks["key"]
    require((certificate == null) == (privateKey == null)) {
        "OpenVPN client certificate and private key must both be inline"
    }
    val authLines = parsed.blocks["auth-user-pass"].orEmpty()
        .filter(String::isNotBlank)
    require(authLines.size <= 2) { "Inline OpenVPN authentication is malformed" }
    val controlBlocks = listOf("tls-auth", "tls-crypt", "tls-crypt-v2")
        .filter { parsed.blocks[it] != null }
    require(controlBlocks.size <= 1) { "Multiple OpenVPN TLS control keys are not supported" }
    val controlType = controlBlocks.singleOrNull()
    val inlineDirection = controlType?.let { type ->
        directive(type)?.let { item ->
            if (item.arguments.isEmpty()) {
                null
            } else {
                require(item.arguments.firstOrNull()?.lowercase() in OpenVpnInlineMarkers) {
                    "External OpenVPN $type files are not supported"
                }
                require(item.arguments.size <= 2) { "OpenVPN $type is malformed" }
                item.arguments.getOrNull(1)
            }
        }
    }
    listOf("tls-auth", "tls-crypt", "tls-crypt-v2").forEach { type ->
        if (directive(type) != null) {
            require(parsed.blocks[type] != null) {
                "External OpenVPN $type files are not supported"
            }
        }
    }
    val explicitDirection = directive("key-direction")
        ?.singleArgument("OpenVPN key direction")
    val keyDirection = (inlineDirection ?: explicitDirection)
        ?.let(::normalizeOpenVpnKeyDirection)
    if (inlineDirection != null && explicitDirection != null) {
        require(
            normalizeOpenVpnKeyDirection(inlineDirection) ==
                normalizeOpenVpnKeyDirection(explicitDirection),
        ) { "Conflicting OpenVPN key directions" }
    }
    require(keyDirection == null || controlType == "tls-auth") {
        "OpenVPN key direction is only valid with tls-auth"
    }

    val tls = buildJsonObject {
        ca?.let { put("certificate", it.toSingleJsonStringArray()) }
        certificate?.let { put("client_certificate", it.toSingleJsonStringArray()) }
        privateKey?.let { put("client_key", it.toSingleJsonStringArray()) }
        if (fingerprints.isNotEmpty()) {
            put("peer_fingerprint", JsonArray(fingerprints.map(::JsonPrimitive)))
        }
        directive("verify-x509-name")?.let { item ->
            require(item.arguments.isNotEmpty()) { "OpenVPN verify-x509-name is malformed" }
            put("server_name", item.arguments[0])
            item.arguments.getOrNull(1)?.let { put("server_name_type", it) }
        }
        directive("remote-cert-ku")?.let { item ->
            put("remote_certificate_ku", JsonArray(item.arguments.map(::JsonPrimitive)))
        }
        directive("remote-cert-eku")?.arguments?.firstOrNull()
            ?.let { put("remote_certificate_eku", it) }
        directive("remote-cert-tls")?.arguments?.firstOrNull()
            ?.let { put("remote_certificate_tls", it) }
        directive("tls-version-min")?.arguments?.firstOrNull()
            ?.let { put("version_min", it) }
        directive("tls-version-max")?.arguments?.firstOrNull()
            ?.let { put("version_max", it) }
        directive("tls-cipher")?.arguments?.joinToString(":")
            ?.takeIf(String::isNotBlank)
            ?.let { put("cipher", it) }
        directive("tls-groups")?.arguments?.joinToString(":")
            ?.takeIf(String::isNotBlank)
            ?.let { put("groups", it) }
        controlType?.let { type ->
            put(
                "control_wrap",
                buildJsonObject {
                    put("type", type.replace('-', '_'))
                    put("key", parsed.blocks.getValue(type).toSingleJsonStringArray())
                    keyDirection?.let { put("direction", it) }
                },
            )
        }
    }
    val json = buildJsonObject {
        put("type", "openvpn-client")
        put("system", false)
        put("mode", "tls")
        put("servers", JsonArray(servers))
        put("network", defaultNetwork)
        if (directive("remote-random") != null) put("remote_random", true)
        authLines.getOrNull(0)?.let { put("username", it) }
        authLines.getOrNull(1)?.let { put("password", it) }
        put("tls", tls)
        directive("cipher")?.arguments?.firstOrNull()?.let { put("cipher", it) }
        directive("data-ciphers")?.arguments?.firstOrNull()?.let {
            put("data_ciphers", JsonArray(it.split(':').map(::JsonPrimitive)))
        }
        directive("data-ciphers-fallback")?.arguments?.firstOrNull()
            ?.let { put("data_ciphers_fallback", it) }
        directive("auth")?.arguments?.firstOrNull()?.let { put("auth", it) }
        directive("mssfix")?.let { item ->
            if (item.arguments.isNotEmpty()) {
                put("mss_fix", item.singleInt("OpenVPN mssfix", 1, 65_535))
            }
        }
        directive("fragment")?.let { item ->
            put("fragment", item.singleInt("OpenVPN fragment", 1, 65_535))
        }
        directive("tun-mtu")?.let { item ->
            put("mtu", item.singleInt("OpenVPN tun-mtu", 1, 65_535))
        }
        directive("compress")?.let { item ->
            require(item.arguments.size <= 1) { "OpenVPN compress is malformed" }
            put("compression", item.arguments.singleOrNull() ?: "stub")
        }
        directive("comp-lzo")?.let { item ->
            require(item.arguments.size <= 1) { "OpenVPN comp-lzo is malformed" }
            put("compression_lzo", item.arguments.singleOrNull() ?: "adaptive")
        }
        directive("allow-compression")?.let { item ->
            put("allow_compression", item.singleArgument("OpenVPN allow-compression"))
        }
        if (directive("route-nopull") != null || directive("route-no-pull") != null) {
            put("route_no_pull", true)
        }
        val routes = directives("route").map { item ->
            require(item.arguments.size in 1..4) { "OpenVPN route is malformed" }
            item.arguments.joinToString(" ")
        }
        if (routes.isNotEmpty()) put("routes", JsonArray(routes.map(::JsonPrimitive)))
        directive("route-gateway")?.arguments?.firstOrNull()
            ?.let { put("route_gateway", it) }
        directive("route-metric")?.let { item ->
            put("route_metric", item.singleInt("OpenVPN route metric", 0, Int.MAX_VALUE))
        }
        directive("redirect-gateway")?.let { item ->
            put("redirect_gateway", true)
            if (item.arguments.isNotEmpty()) {
                put(
                    "redirect_gateway_flags",
                    JsonArray(item.arguments.map(::JsonPrimitive)),
                )
            }
        }
        val pullFilters = directives("pull-filter").map { item ->
            require(item.arguments.size >= 2) { "OpenVPN pull-filter is malformed" }
            buildJsonObject {
                put("action", item.arguments.first())
                put("text", item.arguments.drop(1).joinToString(" "))
            }
        }
        if (pullFilters.isNotEmpty()) put("pull_filters", JsonArray(pullFilters))
        directive("keepalive")?.let { item ->
            require(item.arguments.size == 2) { "OpenVPN keepalive is malformed" }
            put("ping_interval", item.arguments[0].secondsDuration())
            put("ping_restart", item.arguments[1].secondsDuration())
        }
        directive("ping")?.arguments?.firstOrNull()
            ?.let { put("ping_interval", it.secondsDuration()) }
        directive("ping-restart")?.arguments?.firstOrNull()
            ?.let { put("ping_restart", it.secondsDuration()) }
        directive("reneg-sec")?.arguments?.firstOrNull()
            ?.let { put("renegotiate_interval", it.secondsDuration()) }
        directive("explicit-exit-notify")?.let { item ->
            require(item.arguments.size <= 1) {
                "OpenVPN explicit-exit-notify is malformed"
            }
            put(
                "explicit_exit_notify",
                item.arguments.singleOrNull()?.let { value ->
                    value.toIntIn("OpenVPN explicit-exit-notify", 0, Int.MAX_VALUE)
                } ?: 1,
            )
        }
    }
    return ImportOutcome(
        format = EndpointImportFormat.OPENVPN,
        detectedCount = 1,
        accepted = listOf(
            ImportedSingBoxEndpoint(
                sourceIndex = 0,
                remarks = "openvpn",
                type = "openvpn-client",
                json = SingBoxJson.encodeToString(JsonObject.serializer(), json),
            ),
        ),
        mutations = mutations,
    )
}

private data class OpenVpnDirective(
    val name: String,
    val arguments: List<String>,
)

private data class ParsedOpenVpn(
    val directives: List<OpenVpnDirective>,
    val blocks: Map<String, List<String>>,
)

private fun tokenizeOpenVpn(content: String): ParsedOpenVpn {
    val lines = content.lines()
    val directives = mutableListOf<OpenVpnDirective>()
    val blocks = linkedMapOf<String, List<String>>()
    var index = 0
    while (index < lines.size) {
        val line = lines[index].trim()
        index += 1
        if (line.isBlank() || line.startsWith('#') || line.startsWith(';')) continue
        val blockMatch = OpenVpnBlockStart.matchEntire(line)
        if (blockMatch != null) {
            val name = blockMatch.groupValues[1].lowercase()
            require(name !in blocks) { "Repeated OpenVPN inline block is not supported" }
            val blockLines = mutableListOf<String>()
            var closed = false
            while (index < lines.size) {
                val blockLine = lines[index]
                index += 1
                if (blockLine.trim().equals("</$name>", ignoreCase = true)) {
                    closed = true
                    break
                }
                blockLines += blockLine
            }
            require(closed) { "Unclosed OpenVPN inline block" }
            blocks[name] = blockLines
            continue
        }
        val tokens = OpenVpnToken.findAll(line)
            .map { match -> match.value.trim('"', '\'') }
            .toList()
        require(tokens.isNotEmpty()) { "Invalid OpenVPN directive" }
        directives += OpenVpnDirective(
            name = tokens.first().lowercase(),
            arguments = tokens.drop(1),
        )
    }
    return ParsedOpenVpn(directives, blocks)
}

private fun normalizeOpenVpnNetwork(value: String): String = when {
    value.lowercase().startsWith("udp") -> "udp"
    value.lowercase().startsWith("tcp") -> "tcp"
    else -> error("Unsupported OpenVPN transport")
}

private fun normalizeOpenVpnFingerprint(value: String): String {
    val normalized = value.substringAfter(':', value).replace(":", "").lowercase()
    require(normalized.matches(Regex("[0-9a-f]{64}"))) {
        "OpenVPN peer fingerprint is invalid"
    }
    return normalized
}

private fun normalizeOpenVpnKeyDirection(value: String): String = when (value.lowercase()) {
    "0", "server" -> "server"
    "1", "client" -> "client"
    else -> error("OpenVPN key direction is invalid")
}

private fun String.secondsDuration(): String {
    require(toLongOrNull()?.let { it >= 0 } == true) { "OpenVPN duration is invalid" }
    return "${this}s"
}

private fun OpenVpnDirective.singleArgument(label: String): String {
    require(arguments.size == 1 && arguments.single().isNotBlank()) { "$label is malformed" }
    return arguments.single()
}

private fun OpenVpnDirective.singleInt(
    label: String,
    minimum: Int,
    maximum: Int,
): Int = singleArgument(label).toIntIn(label, minimum, maximum)

private fun String.toIntIn(label: String, minimum: Int, maximum: Int): Int {
    val parsed = toIntOrNull()
    require(parsed != null && parsed in minimum..maximum) { "$label is invalid" }
    return parsed
}

private fun List<String>.toSingleJsonStringArray(): JsonArray =
    JsonArray(
        listOf(
            JsonPrimitive(joinToString("\n").trimEnd() + "\n"),
        ),
    )

private val OpenVpnBlockStart = Regex("""<([A-Za-z0-9-]+)>""")
private val OpenVpnToken = Regex("""[^\s"']+|"[^"]*"|'[^']*'""")
private val OpenVpnInlineMarkers = setOf("[inline]", "inline")
private val OpenVpnInlineBlocks = setOf(
    "ca", "cert", "key", "tls-auth", "tls-crypt", "tls-crypt-v2", "auth-user-pass",
)
private val OpenVpnProcessOnlyDirectives = setOf(
    "client", "nobind", "persist-key", "persist-tun", "resolv-retry", "verb", "mute",
    "pull", "tls-client", "dev", "dev-type",
)
private val OpenVpnUnsafeDirectives = setOf(
    "plugin", "up", "down", "route-up", "route-pre-down", "ipchange",
    "client-connect", "client-disconnect", "auth-user-pass-verify", "tls-verify",
    "script-security", "config", "include", "crl-verify", "pkcs12", "askpass",
)
private val OpenVpnSupportedDirectives = setOf(
    "remote", "proto", "remote-random", "ca", "cert", "key", "tls-auth",
    "tls-crypt", "tls-crypt-v2", "key-direction", "auth-user-pass", "cipher",
    "data-ciphers", "data-ciphers-fallback", "auth", "verify-x509-name",
    "remote-cert-ku", "remote-cert-eku", "remote-cert-tls", "peer-fingerprint",
    "tls-version-min", "tls-version-max", "tls-cipher", "tls-groups", "compress",
    "comp-lzo", "allow-compression", "mssfix", "fragment", "tun-mtu",
    "route-nopull", "route-no-pull", "pull-filter", "route", "route-gateway",
    "route-metric", "redirect-gateway", "keepalive", "ping", "ping-restart",
    "reneg-sec", "explicit-exit-notify",
) + OpenVpnUnsafeDirectives
