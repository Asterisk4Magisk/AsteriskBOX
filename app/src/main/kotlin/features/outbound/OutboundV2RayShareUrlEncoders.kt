// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.outbound

import engine.singbox.config.SingBoxJson
import java.net.IDN
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import utils.booleanOrFalse
import utils.encodeBase64
import utils.intOrZero
import utils.objectValue
import utils.stringListValue
import utils.stringOrEmpty

internal fun encodeV2RayOutboundShareUrl(
    type: String,
    outbound: JsonObject,
    remarks: String,
): String? = when (type) {
    "vless" -> outbound.encodeVlessShareUrl(remarks)
    "trojan" -> outbound.encodeTrojanShareUrl(remarks)
    "vmess" -> outbound.encodeVmessShareUrl(remarks)
    else -> null
}

private fun JsonObject.encodeVlessShareUrl(remarks: String): String {
    requireOnlyShareFields(
        "uuid",
        "flow",
        "network",
        "packet_encoding",
        "tls",
        "transport",
        "multiplex",
        "encryption",
        "xray_finalmask",
        "xray_stream_settings",
    )
    require(stringOrEmpty("network").isBlank()) { "VLESS network cannot be shared" }
    require(!get("multiplex").isMeaningfulShareValue()) { "VLESS multiplex cannot be shared" }
    val uuid = stringOrEmpty("uuid")
    require(uuid.isNotBlank()) { "VLESS UUID is required" }
    // Xray extension fields use NG's link parameters; the rest is shared as before.
    val xray = vlessXrayShareParameters()
    val transportParameters = xray.transportParameters ?: xray.shareable.readV2RayShareTransport().parameters
    val tls = xray.shareable.readShareTls(required = false)
    val parameters = buildList {
        add("encryption" to stringOrEmpty("encryption").ifBlank { "none" })
        stringOrEmpty("flow").takeIf(String::isNotBlank)?.let { add("flow" to it) }
        stringOrEmpty("packet_encoding").takeIf(String::isNotBlank)?.let {
            add("packetEncoding" to it)
        }
        addAll(transportParameters)
        xray.finalmask?.let { add("fm" to it) }
        addAll(tls.securityParameters())
        if (tls.enabled) addAll(xray.tlsParameters)
    }
    return buildOutboundShareUri(
        scheme = "vless",
        endpoint = requireShareEndpoint(),
        encodedUserInfo = encodeShareComponent(uuid),
        parameters = parameters,
        remarks = remarks,
    )
}

private fun JsonObject.encodeTrojanShareUrl(remarks: String): String {
    requireOnlyShareFields("password", "network", "tls", "transport", "multiplex")
    require(stringOrEmpty("network").isBlank()) { "Trojan network cannot be shared" }
    require(!get("multiplex").isMeaningfulShareValue()) { "Trojan multiplex cannot be shared" }
    val password = stringOrEmpty("password")
    require(password.isNotBlank()) { "Trojan password is required" }
    val transport = readV2RayShareTransport()
    val tls = readShareTls(required = true)
    return buildOutboundShareUri(
        scheme = "trojan",
        endpoint = requireShareEndpoint(),
        encodedUserInfo = encodeShareComponent(password),
        parameters = transport.parameters + tls.securityParameters(),
        remarks = remarks,
    )
}

private fun JsonObject.encodeVmessShareUrl(remarks: String): String {
    requireOnlyShareFields(
        "uuid",
        "security",
        "alter_id",
        "global_padding",
        "authenticated_length",
        "network",
        "packet_encoding",
        "tls",
        "transport",
        "multiplex",
    )
    require(!booleanOrFalse("global_padding")) { "VMess global padding cannot be shared" }
    require(!booleanOrFalse("authenticated_length")) { "VMess authenticated length cannot be shared" }
    require(stringOrEmpty("network").isBlank()) { "VMess network cannot be shared" }
    require(stringOrEmpty("packet_encoding").isBlank()) { "VMess packet encoding cannot be shared" }
    require(!get("multiplex").isMeaningfulShareValue()) { "VMess multiplex cannot be shared" }
    val uuid = stringOrEmpty("uuid")
    require(uuid.isNotBlank()) { "VMess UUID is required" }
    val alterId = requireVmessAlterId()
    return if (alterId == 0) {
        encodeVmessAeadShareUrl(uuid, remarks)
    } else {
        encodeLegacyVmessShareUrl(uuid, alterId, remarks)
    }
}

private fun JsonObject.encodeVmessAeadShareUrl(
    uuid: String,
    remarks: String,
): String {
    val encryption = stringOrEmpty("security").ifBlank { "auto" }
    require(encryption in VmessAeadEncryptionMethods) {
        "Unsupported VMess AEAD encryption"
    }
    requireVmessAeadTransportRepresentable()
    val transport = readV2RayShareTransport()
    val tls = readShareTls(required = false)
        .requireOnlyShareParameters(
            "VMessAEAD",
            "sni",
            "alpn",
            "fp",
            "pbk",
            "sid",
            "ech",
        )
    require(tls.parameters.count { (name, _) -> name == "ech" } <= 1) {
        "VMessAEAD URL supports at most one ECH config"
    }
    require(!tls.reality || tls.parameters.any { (name, _) -> name == "fp" }) {
        "VMessAEAD REALITY requires a fingerprint"
    }
    return buildOutboundShareUri(
        scheme = "vmess",
        endpoint = requireShareEndpoint().toVmessAeadEndpoint(),
        encodedUserInfo = encodeShareComponent(uuid),
        parameters = buildList {
            add("encryption" to encryption)
            addAll(transport.parameters)
            addAll(tls.securityParameters())
        },
        remarks = remarks,
    )
}

private fun JsonObject.requireVmessAeadTransportRepresentable() {
    val transport = objectValue("transport") ?: return
    when (transport.stringOrEmpty("type").lowercase()) {
        "quic" -> throw IllegalArgumentException(
            "VMessAEAD URL does not support sing-box QUIC transport",
        )
        "http" -> require(transport.stringOrEmpty("method").isBlank()) {
            "VMessAEAD URL cannot represent HTTP method"
        }
        "ws" -> {
            val earlyData = transport.intOrZero("max_early_data")
            val earlyDataHeader = transport.stringOrEmpty("early_data_header_name")
            require(earlyData >= 0) { "WebSocket early data cannot be negative" }
            if (earlyDataHeader.isNotBlank()) {
                require(earlyData > 0) {
                    "WebSocket early data header requires early data"
                }
                require(earlyDataHeader.equals(VmessAeadEarlyDataHeader, ignoreCase = true)) {
                    "VMessAEAD URL cannot represent this WebSocket early data header"
                }
            }
        }
    }
}

private fun OutboundShareEndpoint.toVmessAeadEndpoint(): OutboundShareEndpoint {
    if (host.all { character -> character.code <= 0x7f }) return this
    val asciiHost = IDN.toASCII(host)
    require(asciiHost.isNotBlank()) { "VMessAEAD IDN server is invalid" }
    return copy(host = asciiHost)
}

private fun JsonObject.encodeLegacyVmessShareUrl(
    uuid: String,
    alterId: Int,
    remarks: String,
): String {
    val endpoint = requireShareEndpoint()
    requireLegacyVmessTransportRepresentable()
    val transport = readV2RayShareTransport()
    val tls = readShareTls(required = false)
    require(tls.parameters.none { (name, _) -> name == "ech" }) {
        "VMess legacy URL cannot represent ECH"
    }
    val tlsParameters = tls.parameters.toMap()
    val payload = buildJsonObject {
        put("v", "2")
        put("ps", remarks)
        put("add", endpoint.host)
        put("port", endpoint.port.toString())
        put("id", uuid)
        put("aid", alterId.toString())
        put("scy", stringOrEmpty("security").ifBlank { "auto" })
        put("net", transport.type)
        put("type", transport.legacyHeaderType)
        put("host", transport.legacyHost)
        put("path", transport.legacyPath)
        put("tls", when {
            tls.reality -> "reality"
            tls.enabled -> "tls"
            else -> ""
        })
        put("sni", tlsParameters["sni"].orEmpty())
        put("alpn", tlsParameters["alpn"].orEmpty())
        put("fp", tlsParameters["fp"].orEmpty())
        put("pbk", tlsParameters["pbk"].orEmpty())
        put("sid", tlsParameters["sid"].orEmpty())
        put("insecure", tlsParameters["insecure"].orEmpty())
    }
    val encoded = SingBoxJson.encodeToString(JsonElement.serializer(), payload).encodeBase64()
    return buildString {
        append("vmess://")
        append(encoded)
        remarks.takeIf(String::isNotBlank)?.let {
            append('#')
            append(encodeShareComponent(it))
        }
    }
}

private fun JsonObject.requireVmessAlterId(): Int {
    val value = get("alter_id") ?: return 0
    val primitive = value as? JsonPrimitive
        ?: throw IllegalArgumentException("VMess alter ID must be an integer")
    val alterId = primitive.intOrNull
        ?: primitive.contentOrNull?.toIntOrNull()
        ?: throw IllegalArgumentException("VMess alter ID must be an integer")
    require(alterId >= 0) { "VMess alter ID cannot be negative" }
    return alterId
}

private data class V2RayShareTransport(
    val type: String,
    val parameters: List<Pair<String, String>>,
    val legacyHeaderType: String = "none",
    val legacyHost: String = "",
    val legacyPath: String = "",
)

private fun JsonObject.readV2RayShareTransport(): V2RayShareTransport {
    val transport = objectValue("transport") ?: return V2RayShareTransport(type = "tcp", parameters = emptyList())
    transport.requireOnlyNestedShareFields(
        "type",
        "host",
        "path",
        "method",
        "headers",
        "max_early_data",
        "early_data_header_name",
        "service_name",
        "idle_timeout",
        "ping_timeout",
        "permit_without_stream",
    )
    val type = transport.stringOrEmpty("type").lowercase()
    if (type.isBlank() || type in setOf("tcp", "raw")) {
        require(!transport.hasMeaningfulTransportFieldBesides("type")) {
            "Raw transport contains unsupported options"
        }
        return V2RayShareTransport(type = "tcp", parameters = emptyList())
    }
    return when (type) {
        "http" -> {
            require(!transport.get("headers").isMeaningfulShareValue()) {
                "HTTP transport headers cannot be shared"
            }
            requireTransportFieldsEmpty(transport, "max_early_data", "early_data_header_name", "service_name", "idle_timeout", "ping_timeout", "permit_without_stream")
            val hosts = transport.stringListValue("host")
            val path = transport.stringOrEmpty("path")
            val method = transport.stringOrEmpty("method")
            V2RayShareTransport(
                type = "http",
                parameters = buildList {
                    add("type" to "http")
                    if (hosts.isNotEmpty()) add("host" to hosts.joinToString(","))
                    path.takeIf(String::isNotBlank)?.let { add("path" to it) }
                    method.takeIf(String::isNotBlank)?.let { add("method" to it) }
                },
                legacyHost = hosts.joinToString(","),
                legacyPath = path,
            )
        }
        "ws" -> {
            requireTransportFieldsEmpty(transport, "host", "method", "service_name", "idle_timeout", "ping_timeout", "permit_without_stream")
            val host = transport.singleWebSocketHost()
            val path = transport.stringOrEmpty("path")
            val earlyData = transport.intOrZero("max_early_data")
            val earlyDataHeader = transport.stringOrEmpty("early_data_header_name")
            V2RayShareTransport(
                type = "ws",
                parameters = buildList {
                    add("type" to "ws")
                    path.takeIf(String::isNotBlank)?.let { add("path" to it) }
                    host.takeIf(String::isNotBlank)?.let { add("host" to it) }
                    earlyData.takeIf { it > 0 }?.let { add("ed" to it.toString()) }
                    earlyDataHeader.takeIf(String::isNotBlank)?.let { add("eh" to it) }
                },
                legacyHost = host,
                legacyPath = path,
            )
        }
        "quic" -> {
            require(!transport.hasMeaningfulTransportFieldBesides("type")) {
                "QUIC transport contains unsupported options"
            }
            V2RayShareTransport(type = "quic", parameters = listOf("type" to "quic"), legacyHeaderType = "")
        }
        "grpc" -> {
            requireTransportFieldsEmpty(transport, "host", "path", "method", "headers", "max_early_data", "early_data_header_name", "idle_timeout", "ping_timeout", "permit_without_stream")
            val serviceName = transport.stringOrEmpty("service_name")
            V2RayShareTransport(
                type = "grpc",
                parameters = buildList {
                    add("type" to "grpc")
                    serviceName.takeIf(String::isNotBlank)?.let { add("serviceName" to it) }
                },
                legacyPath = serviceName,
            )
        }
        "httpupgrade" -> {
            requireTransportFieldsEmpty(transport, "method", "headers", "max_early_data", "early_data_header_name", "service_name", "idle_timeout", "ping_timeout", "permit_without_stream")
            val host = transport.stringOrEmpty("host")
            val path = transport.stringOrEmpty("path")
            V2RayShareTransport(
                type = "httpupgrade",
                parameters = buildList {
                    add("type" to "httpupgrade")
                    host.takeIf(String::isNotBlank)?.let { add("host" to it) }
                    path.takeIf(String::isNotBlank)?.let { add("path" to it) }
                },
                legacyHost = host,
                legacyPath = path,
            )
        }
        else -> throw IllegalArgumentException("Unsupported V2Ray transport")
    }
}

private fun JsonObject.requireLegacyVmessTransportRepresentable() {
    val transport = objectValue("transport") ?: return
    when (transport.stringOrEmpty("type").lowercase()) {
        "http" -> require(transport.stringOrEmpty("method").isBlank()) {
            "VMess legacy URL cannot represent HTTP method"
        }
        "ws" -> require(
            transport.intOrZero("max_early_data") == 0 &&
                transport.stringOrEmpty("early_data_header_name").isBlank(),
        ) { "VMess legacy URL cannot represent WebSocket early data" }
    }
}

private fun OutboundShareTls.securityParameters(): List<Pair<String, String>> {
    if (!enabled) return emptyList()
    return listOf("security" to if (reality) "reality" else "tls") + parameters
}

private fun JsonObject.singleWebSocketHost(): String {
    val headers = objectValue("headers") ?: return ""
    val meaningful = headers.entries.filter { (_, value) -> value.isMeaningfulShareValue() }
    require(meaningful.all { (name, _) -> name.equals("Host", ignoreCase = true) }) {
        "WebSocket header cannot be shared"
    }
    if (meaningful.isEmpty()) return ""
    require(meaningful.size == 1) { "WebSocket URL supports exactly one Host header" }
    val value = meaningful.single().value
    val hosts = when (value) {
        is JsonPrimitive -> {
            require(value.isString && !value.contentOrNull.isNullOrBlank()) {
                "WebSocket Host header is invalid"
            }
            listOf(value.content)
        }
        is JsonArray -> value.map { item ->
            val host = item as? JsonPrimitive
            require(host?.isString == true && !host.contentOrNull.isNullOrBlank()) {
                "WebSocket Host header is invalid"
            }
            host.content
        }
        else -> throw IllegalArgumentException("WebSocket Host header is invalid")
    }
    require(hosts.size == 1) { "WebSocket URL supports exactly one Host header value" }
    return hosts.single()
}

private fun requireTransportFieldsEmpty(transport: JsonObject, vararg fields: String) {
    fields.forEach { field ->
        require(!transport.get(field).isMeaningfulShareValue()) {
            "Transport field cannot be shared: $field"
        }
    }
}

private fun JsonObject.hasMeaningfulTransportFieldBesides(vararg ignored: String): Boolean =
    entries.any { (name, value) -> name !in ignored && value.isMeaningfulShareValue() }

private val VmessAeadEncryptionMethods = setOf(
    "auto",
    "aes-128-gcm",
    "chacha20-poly1305",
    "none",
)

private const val VmessAeadEarlyDataHeader = "Sec-WebSocket-Protocol"
