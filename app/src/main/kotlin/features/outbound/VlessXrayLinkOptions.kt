// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.outbound

import engine.singbox.plugin.withoutXrayVlessExtensions
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/*
 * vless:// link options that only the VLESS (Xray) plugin can run, read the
 * way AsteriskNG's V2RayParameters.parse reads them. They are stored as Xray
 * extension fields on the sing-box vless outbound (see VlessXrayFeatures).
 */

/**
 * The transport for the link options sing-box has no equivalent for: XHTTP,
 * mKCP, RAW with header camouflage, and gRPC with authority or multi mode.
 * Null for everything else, which the shared link transport handles.
 */
internal fun vlessXrayLinkTransport(query: Map<String, List<String>>): JsonObject? {
    return when (query.param("type", "network").lowercase()) {
        "xhttp", "splithttp" -> buildJsonObject {
            put("type", "xhttp")
            putParam("path", query.param("path"))
            putParam("host", query.param("host"))
            putParam("mode", query.param("mode"))
            query.param("extra").takeIf(String::isNotBlank)?.let { extra ->
                put("extra", parseLinkJsonObject("extra", extra))
            }
        }

        "kcp", "mkcp" -> buildJsonObject {
            put("type", "mkcp")
            putParam("header_type", query.param("headerType").takeUnless { it.equals("none", ignoreCase = true) })
            putParam("host", query.param("host"))
            // NG drops a non-numeric mtu or tti.
            query.param("mtu").toIntOrNull()?.let { mtu -> put("mtu", mtu) }
            query.param("tti").toIntOrNull()?.let { tti -> put("tti", tti) }
            putParam("seed", query.param("seed"))
        }

        "", "tcp", "raw" -> {
            val headerType = query.param("headerType")
            if (headerType.isBlank() || headerType.equals("none", ignoreCase = true)) return null
            buildJsonObject {
                put("type", "raw")
                put("header_type", headerType)
                putParam("host", query.param("host"))
                putParam("path", query.param("path"))
            }
        }

        "grpc" -> {
            val authority = query.param("authority")
            val multi = query.param("mode") == "multi"
            if (authority.isBlank() && !multi) return null
            buildJsonObject {
                put("type", "grpc")
                putParam("service_name", query.param("serviceName", "service_name", "service-name"))
                putParam("authority", authority)
                if (multi) put("multi_mode", true)
            }
        }

        else -> null
    }
}

/**
 * Adds the Xray-only TLS options to a link's TLS object: `pcs` for TLS, `spx`
 * and `pqv` for REALITY. REALITY without `fp` gets `chrome`, as in NG.
 */
internal fun JsonObject.withVlessXrayTls(query: Map<String, List<String>>): JsonObject {
    val tls = toMutableMap()
    val reality = this["reality"] as? JsonObject
    if (reality == null) {
        query.param("pcs").takeIf(String::isNotBlank)?.let { pins -> tls["pinned_peer_cert_sha256"] = JsonPrimitive(pins) }
        return JsonObject(tls)
    }
    val realityOptions = reality.toMutableMap()
    query.param("spx").takeIf(String::isNotBlank)?.let { spider -> realityOptions["spider_x"] = JsonPrimitive(spider) }
    query.param("pqv").takeIf(String::isNotBlank)?.let { verify -> realityOptions["mldsa65_verify"] = JsonPrimitive(verify) }
    tls["reality"] = JsonObject(realityOptions)
    if (tls["utls"] == null) {
        tls["utls"] = buildJsonObject {
            put("enabled", true)
            put("fingerprint", "chrome")
        }
    }
    return JsonObject(tls)
}

/** The link's `fm` finalmask object, or null when absent. */
internal fun vlessXrayFinalmask(query: Map<String, List<String>>): JsonObject? =
    query.param("fm").takeIf(String::isNotBlank)?.let { mask -> parseLinkJsonObject("fm", mask) }

/**
 * Splits a link's `headers` JSON the way NG's httpTransportHeaders does: the
 * first non-blank `Host` entry (any case) becomes the host, the rest stay
 * headers. Null parts are absent.
 */
internal fun parseVlessHeadersParameter(value: String): Pair<String?, JsonObject?> {
    val headers = parseLinkJsonObject("headers", value)
    var host: String? = null
    val rest = buildJsonObject {
        headers.forEach { (name, element) ->
            if (name.equals("Host", ignoreCase = true)) {
                if (host.isNullOrBlank()) host = element.headerString()
            } else {
                put(name, element)
            }
        }
    }
    return host to rest.takeIf { it.isNotEmpty() }
}

/**
 * Applies a link's `headers` JSON to a ws or httpupgrade transport: an
 * explicit `host` wins over the Host header, and the other headers are kept.
 */
internal fun JsonObject.withVlessLinkHeaders(query: Map<String, List<String>>): JsonObject {
    val value = query.param("headers").takeIf(String::isNotBlank) ?: return this
    val type = (this["type"] as? JsonPrimitive)?.contentOrNull
    if (type != "ws" && type != "httpupgrade") return this
    val (headerHost, headers) = parseVlessHeadersParameter(value)
    val transport = toMutableMap()
    val explicitHost = query.param("host").takeIf(String::isNotBlank)
    val host = explicitHost ?: headerHost
    if (type == "ws") {
        transport["headers"] = buildJsonObject {
            host?.let { put("Host", it) }
            headers?.forEach { (name, element) -> put(name, element) }
        }.takeIf { it.isNotEmpty() } ?: return this
    } else {
        host?.let { transport["host"] = JsonPrimitive(it) }
        headers?.let { transport["headers"] = it }
    }
    return JsonObject(transport)
}

private fun parseLinkJsonObject(name: String, value: String): JsonObject =
    runCatching { Json.parseToJsonElement(value) }.getOrNull() as? JsonObject
        ?: throw IllegalArgumentException("VLESS link parameter $name must be a JSON object")

private fun JsonElement.headerString(): String? =
    (this as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotEmpty)

/** First non-blank value of the first present name, case-insensitively, like the link parser. */
private fun Map<String, List<String>>.param(vararg names: String): String =
    names.firstNotNullOfOrNull { name ->
        entries.firstOrNull { entry -> entry.key.equals(name, ignoreCase = true) }
            ?.value
            ?.firstOrNull()
            ?.takeIf(String::isNotBlank)
    }.orEmpty()

private fun kotlinx.serialization.json.JsonObjectBuilder.putParam(name: String, value: String?) {
    value?.takeIf(String::isNotBlank)?.let { put(name, it) }
}

/**
 * The share-link form of a vless outbound's Xray extension fields, with
 * NG's parameter names (V2RayParameters.get). [shareable] is the outbound
 * left for the shared encoder; [transportParameters] replaces the shared
 * transport parameters when non-null. `xray_stream_settings` has no link
 * form and is dropped.
 */
internal data class VlessXrayShareParameters(
    val shareable: JsonObject,
    val transportParameters: List<Pair<String, String>>?,
    val finalmask: String?,
    val tlsParameters: List<Pair<String, String>>,
)

internal fun JsonObject.vlessXrayShareParameters(): VlessXrayShareParameters {
    val transport = this["transport"] as? JsonObject
    val transportParameters = transport?.vlessXrayTransportShareParameters()
    var shareable = withoutXrayVlessExtensions()
    if (transportParameters != null) shareable = JsonObject(shareable - "transport")
    // The shared TLS reader has no `vcn`; vless writes it here, as NG does.
    (shareable["tls"] as? JsonObject)?.let { tls -> shareable = JsonObject(shareable + ("tls" to JsonObject(tls - "certificate_server_name"))) }
    val tls = this["tls"] as? JsonObject
    val reality = tls?.get("reality") as? JsonObject
    val tlsParameters = buildList {
        if (reality != null && reality.isTrueText("enabled")) {
            reality.text("mldsa65_verify").takeIf(String::isNotBlank)?.let { add("pqv" to it) }
            reality.text("spider_x").takeIf(String::isNotBlank)?.let { add("spx" to it) }
        } else if (tls != null) {
            tls.text("pinned_peer_cert_sha256").takeIf(String::isNotBlank)?.let { add("pcs" to it) }
            tls.text("certificate_server_name").takeIf(String::isNotBlank)?.let { add("vcn" to it) }
        }
    }
    return VlessXrayShareParameters(
        shareable = shareable,
        transportParameters = transportParameters,
        finalmask = this["xray_finalmask"].compactJsonObjectText(),
        tlsParameters = tlsParameters,
    )
}

/** Parameters for the transports the shared encoder cannot write; null for the rest. */
private fun JsonObject.vlessXrayTransportShareParameters(): List<Pair<String, String>>? {
    val transport = this
    fun MutableList<Pair<String, String>>.addText(name: String, field: String) {
        transport.text(field).takeIf(String::isNotBlank)?.let { add(name to it) }
    }
    return when (text("type").lowercase()) {
        "xhttp" -> buildList {
            add("type" to "xhttp")
            addText("path", "path")
            addText("host", "host")
            addText("mode", "mode")
            transport["extra"].compactJsonObjectText()?.let { add("extra" to it) }
        }

        "mkcp" -> buildList {
            add("type" to "kcp")
            addText("headerType", "header_type")
            addText("host", "host")
            addText("mtu", "mtu")
            addText("tti", "tti")
            addText("seed", "seed")
        }

        "raw" -> buildList {
            add("type" to "raw")
            addText("headerType", "header_type")
            addText("host", "host")
            addText("path", "path")
        }

        "grpc" -> {
            val multi = isTrueText("multi_mode")
            if (text("authority").isBlank() && !multi) return null
            buildList {
                add("type" to "grpc")
                if (multi) add("mode" to "multi")
                addText("authority", "authority")
                addText("serviceName", "service_name")
            }
        }

        "ws", "httpupgrade" -> {
            val headers = this["headers"] as? JsonObject ?: return null
            val extraHeaders = JsonObject(headers.filterKeys { name -> !name.equals("Host", ignoreCase = true) })
            if (extraHeaders.isEmpty()) return null
            val isWebSocket = text("type").equals("ws", ignoreCase = true)
            val host = if (isWebSocket) {
                headers.entries.firstOrNull { (name, _) -> name.equals("Host", ignoreCase = true) }?.value.firstText()
            } else {
                text("host")
            }
            buildList {
                add("type" to if (isWebSocket) "ws" else "httpupgrade")
                addText("path", "path")
                host?.takeIf(String::isNotBlank)?.let { add("host" to it) }
                add("headers" to extraHeaders.toString())
                if (isWebSocket) {
                    text("max_early_data").toIntOrNull()?.takeIf { it > 0 }?.let { add("ed" to it.toString()) }
                    addText("eh", "early_data_header_name")
                }
            }
        }

        else -> null
    }
}

private fun JsonObject.text(name: String): String = (this[name] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()

private fun JsonObject.isTrueText(name: String): Boolean = text(name).equals("true", ignoreCase = true)

private fun JsonElement?.firstText(): String? = when (this) {
    is JsonPrimitive -> contentOrNull
    is JsonArray -> (firstOrNull() as? JsonPrimitive)?.contentOrNull
    else -> null
}?.trim()?.takeIf(String::isNotEmpty)

/** An object option (an object, or editor JSON text) in compact form; null when unset. */
private fun JsonElement?.compactJsonObjectText(): String? = when (this) {
    is JsonObject -> takeIf { it.isNotEmpty() }?.toString()
    is JsonPrimitive -> contentOrNull?.takeIf(String::isNotBlank)?.let { text ->
        (runCatching { Json.parseToJsonElement(text) }.getOrNull() as? JsonObject)
            ?.takeIf { it.isNotEmpty() }
            ?.toString()
            ?: throw IllegalArgumentException("VLESS option must be a JSON object")
    }
    else -> null
}
