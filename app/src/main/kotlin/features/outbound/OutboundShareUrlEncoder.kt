// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.outbound

import engine.singbox.config.SingBoxJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlin.io.encoding.Base64

internal fun encodeOutboundShareUrl(
    json: String,
    remarks: String,
): OutboundShareUrlResult = try {
    val outbound = requireNotNull(SingBoxJson.parseToJsonElement(json) as? JsonObject) {
        "Outbound must be a JSON object"
    }
    val type = outbound.stringValue("type")
    require(type.isNotBlank()) { "Outbound type is required" }
    val url = encodeStandardOutboundShareUrl(type, outbound, remarks)
        ?: encodeV2RayOutboundShareUrl(type, outbound, remarks)
        ?: encodeQuicOutboundShareUrl(type, outbound, remarks)
        ?: throw IllegalArgumentException("Outbound type does not have a share encoder")
    AvailableOutboundShareUrl(url)
} catch (_: IllegalArgumentException) {
    UnavailableOutboundShareUrl
}

internal data class OutboundShareEndpoint(
    val host: String,
    val port: Int,
)

internal data class OutboundShareTls(
    val enabled: Boolean,
    val reality: Boolean,
    val parameters: List<Pair<String, String>>,
)

internal fun OutboundShareTls.requireOnlyShareParameters(
    protocol: String,
    vararg allowed: String,
): OutboundShareTls {
    val supported = allowed.toSet()
    val unsupported = parameters.firstOrNull { (name, _) -> name !in supported }
    require(unsupported == null) {
        "$protocol TLS option cannot be represented by its official URI: ${unsupported?.first}"
    }
    return this
}

internal fun JsonObject.requireShareEndpoint(): OutboundShareEndpoint {
    val host = stringValue("server").removeSurrounding("[", "]")
    val port = intValue("server_port")
    require(host.isNotBlank()) { "Outbound server is required" }
    require(port in 1..65535) { "Outbound server port is invalid" }
    return OutboundShareEndpoint(host, port)
}

internal fun JsonObject.requireOnlyShareFields(vararg allowed: String) {
    val accepted = CommonShareRootFields + allowed
    val unsupported = entries.firstOrNull { (name, value) ->
        name !in accepted && value.isMeaningfulShareValue()
    }
    require(unsupported == null) { "Unsupported outbound field: ${unsupported?.key}" }
}

internal fun JsonObject.requireOnlyNestedShareFields(vararg allowed: String) {
    val accepted = allowed.toSet()
    val unsupported = entries.firstOrNull { (name, value) ->
        name !in accepted && value.isMeaningfulShareValue()
    }
    require(unsupported == null) { "Unsupported nested outbound field: ${unsupported?.key}" }
}

internal fun JsonObject.stringValue(name: String): String =
    (get(name) as? JsonPrimitive)?.contentOrNull.orEmpty()

internal fun JsonObject.intValue(name: String): Int {
    val primitive = get(name) as? JsonPrimitive ?: return 0
    return primitive.intOrNull ?: primitive.contentOrNull?.toIntOrNull() ?: 0
}

internal fun JsonObject.booleanValue(name: String): Boolean {
    val primitive = get(name) as? JsonPrimitive ?: return false
    return primitive.booleanOrNull ?: primitive.contentOrNull.let { value ->
        value == "1" || value.equals("true", ignoreCase = true)
    }
}

internal fun JsonObject.objectValue(name: String): JsonObject? = get(name) as? JsonObject

internal fun JsonObject.stringListValue(name: String): List<String> = when (val value = get(name)) {
    is JsonArray -> value.mapNotNull { item ->
        (item as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)
    }
    is JsonPrimitive -> value.contentOrNull?.takeIf(String::isNotBlank)?.let(::listOf).orEmpty()
    else -> emptyList()
}

internal fun JsonElement?.isMeaningfulShareValue(): Boolean = when (this) {
    null,
    JsonNull -> false
    is JsonArray -> any(JsonElement::isMeaningfulShareValue)
    is JsonObject -> values.any(JsonElement::isMeaningfulShareValue)
    is JsonPrimitive -> when {
        booleanOrNull != null -> booleanOrNull == true
        intOrNull != null -> intOrNull != 0
        else -> !contentOrNull.isNullOrBlank()
    }
}

internal fun JsonObject.readShareTls(required: Boolean): OutboundShareTls {
    val tls = objectValue("tls")
    if (tls == null) {
        require(!required) { "TLS is required" }
        return OutboundShareTls(enabled = false, reality = false, parameters = emptyList())
    }
    tls.requireOnlyNestedShareFields(
        "enabled",
        "server_name",
        "insecure",
        "alpn",
        "utls",
        "reality",
        "ech",
    )
    val enabled = tls.booleanValue("enabled")
    require(enabled || !tls.hasMeaningfulFieldBesides("enabled")) {
        "Disabled TLS contains connection options"
    }
    require(!required || enabled) { "TLS is required" }
    if (!enabled) return OutboundShareTls(false, false, emptyList())

    val parameters = mutableListOf<Pair<String, String>>()
    tls.stringValue("server_name").takeIf(String::isNotBlank)?.let { parameters += "sni" to it }
    if (tls.booleanValue("insecure")) parameters += "insecure" to "1"
    tls.stringListValue("alpn").takeIf(List<String>::isNotEmpty)?.let { values ->
        parameters += "alpn" to values.joinToString(",")
    }

    tls.objectValue("utls")?.let { utls ->
        utls.requireOnlyNestedShareFields("enabled", "fingerprint")
        val utlsEnabled = utls.booleanValue("enabled")
        require(utlsEnabled || !utls.hasMeaningfulFieldBesides("enabled")) {
            "Disabled uTLS contains connection options"
        }
        if (utlsEnabled) {
            parameters += "fp" to utls.stringValue("fingerprint").ifBlank { "chrome" }
        }
    }

    var realityEnabled = false
    tls.objectValue("reality")?.let { reality ->
        reality.requireOnlyNestedShareFields("enabled", "public_key", "short_id")
        realityEnabled = reality.booleanValue("enabled")
        require(realityEnabled || !reality.hasMeaningfulFieldBesides("enabled")) {
            "Disabled REALITY contains connection options"
        }
        if (realityEnabled) {
            val publicKey = reality.stringValue("public_key")
            require(publicKey.isNotBlank()) { "REALITY public key is required" }
            parameters += "pbk" to publicKey
            reality.stringValue("short_id").takeIf(String::isNotBlank)?.let {
                parameters += "sid" to it
            }
        }
    }

    tls.objectValue("ech")?.let { ech ->
        ech.requireOnlyNestedShareFields("enabled", "config")
        val echEnabled = ech.booleanValue("enabled")
        require(echEnabled || !ech.hasMeaningfulFieldBesides("enabled")) {
            "Disabled ECH contains connection options"
        }
        if (echEnabled) {
            val configs = ech.stringListValue("config")
            require(configs.isNotEmpty()) { "ECH config is required" }
            configs.forEach { parameters += "ech" to it }
        }
    }
    return OutboundShareTls(enabled, realityEnabled, parameters)
}

internal fun buildOutboundShareUri(
    scheme: String,
    endpoint: OutboundShareEndpoint,
    encodedUserInfo: String? = null,
    path: String = "",
    parameters: List<Pair<String, String>> = emptyList(),
    remarks: String,
    authorityPort: String = endpoint.port.toString(),
): String = buildString {
    append(scheme)
    append("://")
    encodedUserInfo?.takeIf(String::isNotBlank)?.let { append(it).append('@') }
    append(renderShareHost(endpoint.host))
    append(':')
    append(authorityPort)
    if (path.isNotBlank()) {
        if (!path.startsWith('/')) append('/')
        append(encodeSharePath(path))
    }
    if (parameters.isNotEmpty()) {
        append('?')
        parameters.forEachIndexed { index, (name, value) ->
            if (index > 0) append('&')
            append(encodeShareComponent(name))
            append('=')
            append(encodeShareComponent(value))
        }
    }
    remarks.takeIf(String::isNotBlank)?.let {
        append('#')
        append(encodeShareComponent(it))
    }
}

internal fun encodeShareCredentials(username: String, password: String): String? = when {
    username.isBlank() && password.isBlank() -> null
    password.isBlank() -> encodeShareComponent(username)
    else -> encodeShareComponent(username) + ":" + encodeShareComponent(password)
}

internal fun encodeShareComponent(value: String): String = encodeShareBytes(value, path = false)

internal fun encodeSharePath(value: String): String = encodeShareBytes(value, path = true)

internal fun encodeShareBase64Url(value: String): String =
    Base64.UrlSafe.encode(value.encodeToByteArray()).trimEnd('=')

private fun encodeShareBytes(value: String, path: Boolean): String = buildString {
    value.encodeToByteArray().forEach { byte ->
        val unsigned = byte.toInt() and 0xff
        val character = unsigned.toChar()
        if (
            character in 'a'..'z' ||
            character in 'A'..'Z' ||
            character in '0'..'9' ||
            character == '-' || character == '.' || character == '_' || character == '~' ||
            path && character == '/'
        ) {
            append(character)
        } else {
            append('%')
            append(HexDigits[unsigned ushr 4])
            append(HexDigits[unsigned and 0x0f])
        }
    }
}

private fun renderShareHost(host: String): String =
    if (':' in host) "[$host]" else host

private fun JsonObject.hasMeaningfulFieldBesides(vararg ignored: String): Boolean =
    entries.any { (name, value) -> name !in ignored && value.isMeaningfulShareValue() }

private val CommonShareRootFields = setOf(
    "type",
    "server",
    "server_port",
    "tag",
    "detour",
    "bind_interface",
    "inet4_bind_address",
    "inet6_bind_address",
    "bind_address_no_port",
    "routing_mark",
    "reuse_addr",
    "connect_timeout",
    "tcp_fast_open",
    "tcp_multi_path",
    "disable_tcp_keep_alive",
    "tcp_keep_alive",
    "tcp_keep_alive_interval",
    "udp_fragment",
    "domain_resolver",
    "network_strategy",
    "network_type",
    "fallback_network_type",
    "fallback_delay",
)

private const val HexDigits = "0123456789ABCDEF"
