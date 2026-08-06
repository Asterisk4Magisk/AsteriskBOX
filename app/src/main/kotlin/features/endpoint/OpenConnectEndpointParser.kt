// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.endpoint

import engine.singbox.config.SingBoxJson
import features.importing.ImportIssue
import features.importing.ImportIssueReason
import features.importing.ImportIssueSeverity
import features.importing.ImportOutcome
import features.importing.ImportStage
import java.net.URI
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal object OpenConnectEndpointParser {
    fun parseOutcomeOrNull(content: String): RecognizedEndpointImport? {
        if (!looksLikeOpenConnect(content)) return null
        return RecognizedEndpointImport(
            runCatching { parseOpenConnect(content) }.getOrElse { error ->
                ImportOutcome(
                    format = EndpointImportFormat.OPENCONNECT,
                    detectedCount = 1,
                    accepted = emptyList(),
                    issues = listOf(
                        ImportIssue(
                            reason = ImportIssueReason.UNSAFE_EXTERNAL_REFERENCE,
                            severity = ImportIssueSeverity.ERROR,
                            stage = ImportStage.PARSE,
                            sourceIndex = 0,
                            detectedType = "openconnect",
                            message = error.message ?: "Invalid OpenConnect configuration",
                        ),
                    ),
                )
            },
        )
    }
}

private fun looksLikeOpenConnect(content: String): Boolean =
    content.lineSequence().map(String::trim).any { line ->
        val key = line.substringBefore('=').substringBefore(' ').lowercase().removePrefix("--")
        key in OpenConnectRecognitionOptions || line.startsWith("openconnect ")
    }

private fun parseOpenConnect(content: String): ImportOutcome<ImportedSingBoxEndpoint> {
    val options = content.lineSequence()
        .map(String::trim)
        .filter { it.isNotBlank() && !it.startsWith('#') && !it.startsWith(';') }
        .map { line ->
            require(!line.startsWith("openconnect ") && !line.startsWith("#!")) {
                "OpenConnect shell wrappers are not supported"
            }
            val separator = line.indexOf('=').takeIf { it > 0 }
                ?: line.indexOfFirst(Char::isWhitespace).takeIf { it > 0 }
                ?: line.length
            val name = line.substring(0, separator).trim().lowercase().removePrefix("--")
            val value = line.substring(separator).trimStart('=', ' ', '\t').trim()
            name to value
        }
        .toList()
    val unsafe = options.firstOrNull { it.first in OpenConnectUnsafeOptions }
    require(unsafe == null) {
        "Unsafe OpenConnect option is not supported: ${unsafe?.first}"
    }
    val mutations = ignoredEndpointOptionMutations(
        profile = "OpenConnect",
        optionNames = options
            .filter { it.first !in OpenConnectSupportedOptions }
            .map { it.first },
    )
    fun last(vararg names: String): String? =
        options.lastOrNull { it.first in names }?.second?.takeIf(String::isNotBlank)
    fun present(vararg names: String): Boolean =
        options.any { it.first in names && it.second.toBooleanOption() }

    val server = last("server")
        ?.let(::normalizeOpenConnectServer)
        ?: error("OpenConnect server is required")
    val flavor = last("protocol")?.lowercase().orEmpty().ifBlank { "anyconnect" }
    require(flavor in OpenConnectFlavors) { "Unsupported OpenConnect protocol flavor" }
    val formEntries = options.filter { it.first == "form-entry" }.map { (_, value) ->
        parseOpenConnectFormEntry(value)
    }
    val fingerprints = options.filter { it.first == "servercert" }
        .flatMap { it.second.split(',') }
        .map(String::trim)
        .filter(String::isNotBlank)
        .map(::normalizeOpenConnectFingerprint)
    val tls = buildJsonObject {
        if (present("no-cert-check")) put("insecure", true)
        last("sni")?.let { put("server_name", it) }
        if (fingerprints.isNotEmpty()) {
            put("peer_fingerprint", JsonArray(fingerprints.map(::JsonPrimitive)))
        }
        if (present("no-system-trust")) put("system_trust_disabled", true)
    }
    val json = buildJsonObject {
        put("type", "openconnect")
        put("system", false)
        put("server", server)
        put("flavor", flavor)
        last("user")?.let { put("username", it) }
        last("passwd-on-stdin", "password")?.let { put("password", it) }
        last("usergroup", "authgroup")?.let { put("auth_group", it) }
        last("cookie")?.let { put("cookie", it) }
        last("os")?.let { put("reported_os", it) }
        last("useragent")?.let { put("user_agent", it) }
        if (present("no-dtls", "no-udp")) put("no_udp", true)
        last("dtls-local-port")?.toPortOrNull("OpenConnect DTLS port")
            ?.let { put("dtls_local_port", it) }
        if (present("no-http-keepalive")) put("http_keepalive_disabled", true)
        if (present("no-xmlpost")) put("xml_post_disabled", true)
        if (present("no-external-auth")) put("external_auth_disabled", true)
        if (present("no-passwd")) put("password_authentication_disabled", true)
        if (present("tcp-keepalive")) put("tcp_keep_alive_enabled", true)
        if (present("pfs")) put("pfs", true)
        if (present("disable-ipv6")) put("ipv6_disabled", true)
        if (present("allow-insecure-crypto")) put("allow_insecure_crypto", true)
        last("mtu")?.toIntIn("OpenConnect MTU", 1, 65_535)?.let { put("mtu", it) }
        last("base-mtu")?.toIntIn("OpenConnect base MTU", 1, 65_535)
            ?.let { put("base_mtu", it) }
        last("dpd")?.secondsDuration("OpenConnect DPD")
            ?.let { put("dpd_interval", it) }
        last("reconnect-timeout")?.secondsDuration("OpenConnect reconnect timeout")
            ?.let { put("reconnect_timeout", it) }
        if (tls.isNotEmpty()) put("tls", tls)
        if (formEntries.isNotEmpty()) put("form_entries", JsonArray(formEntries))
    }
    return ImportOutcome(
        format = EndpointImportFormat.OPENCONNECT,
        detectedCount = 1,
        accepted = listOf(
            ImportedSingBoxEndpoint(
                sourceIndex = 0,
                remarks = "openconnect",
                type = "openconnect",
                json = SingBoxJson.encodeToString(JsonObject.serializer(), json),
            ),
        ),
        mutations = mutations,
    )
}

private fun parseOpenConnectFormEntry(value: String): JsonObject {
    val separator = value.indexOf('=')
    require(separator > 0) { "Malformed OpenConnect form entry" }
    val selector = value.substring(0, separator)
    val entryValue = value.substring(separator + 1)
    require(entryValue.isNotEmpty()) { "Malformed OpenConnect form entry" }
    return buildJsonObject {
        val colon = selector.indexOf(':')
        if (colon > 0) {
            val form = selector.substring(0, colon)
            val name = selector.substring(colon + 1)
            require(form.isNotBlank() && name.isNotBlank()) {
                "Malformed OpenConnect form entry"
            }
            put("form_id", form)
            put("name", name)
        } else {
            require(selector.isNotBlank()) { "Malformed OpenConnect form entry" }
            put("submission_key", selector)
        }
        put("value", entryValue)
    }
}

private fun normalizeOpenConnectServer(value: String): String {
    val candidate = if ("://" in value) value else "https://$value"
    val uri = URI(candidate)
    require(
        uri.scheme.equals("https", ignoreCase = true) &&
            !uri.host.isNullOrBlank() &&
            uri.rawUserInfo == null &&
            uri.rawQuery == null &&
            uri.rawFragment == null &&
            (uri.port == -1 || uri.port in 1..65_535)
    ) { "OpenConnect server URL is invalid" }
    return uri.toASCIIString()
}

private fun normalizeOpenConnectFingerprint(value: String): String {
    val normalized = value.trim()
    val lowercase = normalized.lowercase()
    return when {
        lowercase.startsWith("sha1:") ->
            lowercase.requireHexFingerprint("sha1:", maximumLength = 40)
        lowercase.startsWith("sha256:") ->
            lowercase.requireHexFingerprint("sha256:", maximumLength = 64)
        lowercase.startsWith("pin-sha256:") -> {
            val encoded = normalized.substringAfter(':')
            require(encoded.length >= 4 && encoded.matches(Base64FingerprintPattern)) {
                "OpenConnect server fingerprint is invalid"
            }
            "pin-sha256:$encoded"
        }
        else -> {
            val encoded = lowercase.replace(":", "")
            require(
                encoded.length in 4..40 &&
                    encoded.matches(HexFingerprintPattern),
            ) { "OpenConnect server fingerprint is invalid" }
            encoded
        }
    }
}

private fun String.requireHexFingerprint(prefix: String, maximumLength: Int): String {
    val encoded = substring(prefix.length)
    require(
        encoded.length in 4..maximumLength &&
            encoded.matches(HexFingerprintPattern),
    ) { "OpenConnect server fingerprint is invalid" }
    return this
}

private fun String?.toBooleanOption(): Boolean =
    this == null || isBlank() || lowercase() in setOf("1", "true", "yes", "on")

private fun String.toPortOrNull(label: String): Int = toIntIn(label, 1, 65_535)

private fun String.toIntIn(label: String, minimum: Int, maximum: Int): Int {
    val parsed = toIntOrNull()
    require(parsed != null && parsed in minimum..maximum) { "$label is invalid" }
    return parsed
}

private fun String.secondsDuration(label: String): String {
    val seconds = toLongOrNull()
    require(seconds != null && seconds >= 0) { "$label is invalid" }
    return "${seconds}s"
}

private val OpenConnectFlavors = setOf("anyconnect", "gp", "fortinet", "f5", "pulse", "nc")
private val HexFingerprintPattern = Regex("[0-9a-f]+")
private val Base64FingerprintPattern = Regex("[A-Za-z0-9+/_=-]+")
private val OpenConnectRecognitionOptions = setOf(
    "server", "protocol", "usergroup", "authgroup", "servercert", "no-dtls",
)
private val OpenConnectUnsafeOptions = setOf(
    "csd-wrapper", "hip-wrapper", "tncc-wrapper", "script", "vpnc-script",
    "cafile", "certificate", "sslkey", "key-password", "pid-file",
)
private val OpenConnectSupportedOptions = setOf(
    "server", "protocol", "user", "usergroup", "authgroup", "os", "useragent",
    "password", "passwd-on-stdin", "cookie", "form-entry", "no-dtls", "no-udp",
    "dtls-local-port", "no-http-keepalive", "no-xmlpost", "no-external-auth",
    "no-passwd", "tcp-keepalive", "pfs", "disable-ipv6", "allow-insecure-crypto",
    "mtu", "base-mtu", "dpd", "reconnect-timeout", "servercert", "sni",
    "no-system-trust", "no-cert-check",
) + OpenConnectUnsafeOptions
