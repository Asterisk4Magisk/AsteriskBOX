// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package engine.singbox.plugin

import android.content.Context
import app.R
import engine.singbox.config.encodeSingBoxJson
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import utils.booleanOrNull
import utils.intOrNull
import utils.stringOrEmpty

/*
 * VLESS nodes may carry Xray extension fields (see the abox-plugin vless
 * README): the VLESS (Xray) plugin reads them, the built-in core does not
 * know them. A node is stored once and runs on either implementation.
 */

/** A vless outbound needs the Xray plugin while the built-in core serves vless. */
internal class VlessXrayRequiredException(
    val tag: String,
    val features: List<String>,
) : IllegalArgumentException("Outbound $tag uses Xray-only features (${features.joinToString(", ")})")

/** The Xray-only features [this] vless outbound uses; empty when the built-in core can run it. */
internal fun JsonObject.xrayOnlyVlessFeatures(): List<String> = buildList {
    val transport = this@xrayOnlyVlessFeatures["transport"] as? JsonObject
    when (transport?.stringOrEmpty("type")?.lowercase()) {
        "xhttp" -> add("XHTTP")
        "mkcp" -> add("mKCP")
        "raw" -> add("RAW header")
        "grpc" -> {
            if (transport.stringOrEmpty("authority").isNotBlank()) add("gRPC authority")
            if (transport.isTrue("multi_mode")) add("gRPC multiMode")
        }
    }
    val encryption = stringOrEmpty("encryption")
    if (encryption.isNotBlank() && !encryption.equals("none", ignoreCase = true)) add("VLESS Encryption")
    val tls = this@xrayOnlyVlessFeatures["tls"] as? JsonObject
    if (tls?.stringOrEmpty("pinned_peer_cert_sha256").orEmpty().isNotBlank()) add("pinnedPeerCertSha256")
    val reality = tls?.get("reality") as? JsonObject
    if (reality?.stringOrEmpty("mldsa65_verify").orEmpty().isNotBlank()) add("ML-DSA-65")
    if (this@xrayOnlyVlessFeatures["xray_finalmask"].isPresentObject()) add("finalmask")
    if (this@xrayOnlyVlessFeatures["xray_stream_settings"].isPresentObject()) add("streamSettings")
}

/**
 * [this] vless outbound without the extension fields, for the built-in core.
 * `spider_x` only seeds REALITY's crawler path, so it is dropped silently.
 */
internal fun JsonObject.withoutXrayVlessExtensions(): JsonObject {
    val outbound = toMutableMap()
    outbound.remove("encryption")
    outbound.remove("xray_finalmask")
    outbound.remove("xray_stream_settings")
    (outbound["tls"] as? JsonObject)?.let { tls ->
        val stripped = tls.toMutableMap()
        stripped.remove("pinned_peer_cert_sha256")
        (stripped["reality"] as? JsonObject)?.let { reality ->
            stripped["reality"] = JsonObject(reality - setOf("spider_x", "mldsa65_verify"))
        }
        outbound["tls"] = JsonObject(stripped)
    }
    (outbound["transport"] as? JsonObject)?.let { transport ->
        outbound["transport"] = JsonObject(transport - setOf("authority", "multi_mode"))
    }
    return JsonObject(outbound)
}

/** Builds the user-facing error for [error] with this context's strings. */
internal fun Context.vlessXrayRequiredMessage(error: VlessXrayRequiredException): String =
    getString(
        R.string.plugin_vless_xray_required,
        error.tag,
        error.features.joinToString(getString(R.string.plugin_vless_feature_separator)),
    )

/** Runs [block], turning a [VlessXrayRequiredException] into a localized failure. */
internal inline fun <T> Context.withLocalizedVlessXrayErrors(block: () -> T): T = try {
    block()
} catch (error: VlessXrayRequiredException) {
    throw IllegalArgumentException(vlessXrayRequiredMessage(error), error)
}

/** Options the plugin cannot honour fail the compile instead of failing in the plugin. */
internal fun JsonObject.requireSupportedByVlessPlugin(tag: String) {
    require(!((this["multiplex"] as? JsonObject)?.isTrue("enabled") ?: false)) {
        "VLESS plugin outbound $tag does not support multiplex; UDP always uses XUDP"
    }
    require(!((this["tls"] as? JsonObject)?.isTrue("insecure") ?: false)) {
        "VLESS plugin outbound $tag does not support insecure TLS; pin the certificate instead"
    }
    val transportType = (this["transport"] as? JsonObject)?.stringOrEmpty("type")?.lowercase().orEmpty()
    require(transportType != "http" && transportType != "quic") {
        "VLESS plugin outbound $tag uses the $transportType transport, which Xray does not support; use XHTTP"
    }
}

/**
 * VLESS plugin config: the listener and dial target around the vless outbound.
 * Only fields the plugin reads are copied, with editor strings coerced to JSON
 * types and JSON-text fields parsed into objects.
 */
internal fun buildVlessConfig(
    outbound: JsonObject,
    tag: String,
    socksPort: Int,
    mappingAddress: String,
    protectPath: String,
): String {
    val uuid = outbound.stringOrEmpty("uuid")
    require(uuid.isNotBlank()) { "VLESS outbound $tag needs a uuid" }
    val vless = buildJsonObject {
        put("type", "vless")
        put("tag", tag)
        put("server", outbound.stringOrEmpty("server"))
        put("server_port", outbound.intOrNull("server_port") ?: 0)
        put("uuid", uuid)
        copyString(outbound, "flow")
        copyString(outbound, "encryption")
        copyString(outbound, "packet_encoding")
        (outbound["tls"] as? JsonObject)?.let { tls ->
            putJsonObject("tls") {
                put("enabled", tls.isTrue("enabled"))
                copyString(tls, "server_name")
                copyList(tls, "alpn")
                copyString(tls, "certificate_server_name")
                copyString(tls, "pinned_peer_cert_sha256")
                (tls["utls"] as? JsonObject)?.let { utls ->
                    putJsonObject("utls") {
                        put("enabled", utls.isTrue("enabled"))
                        copyString(utls, "fingerprint")
                    }
                }
                (tls["ech"] as? JsonObject)?.let { ech ->
                    putJsonObject("ech") {
                        put("enabled", ech.isTrue("enabled"))
                        copyPem(ech, "config")
                    }
                }
                (tls["reality"] as? JsonObject)?.let { reality ->
                    putJsonObject("reality") {
                        put("enabled", reality.isTrue("enabled"))
                        listOf("public_key", "short_id", "spider_x", "mldsa65_verify")
                            .forEach { name -> copyString(reality, name) }
                    }
                }
            }
        }
        (outbound["transport"] as? JsonObject)?.let { transport ->
            putJsonObject("transport") {
                put("type", transport.stringOrEmpty("type"))
                listOf("path", "service_name", "authority", "mode", "early_data_header_name", "header_type", "seed")
                    .forEach { name -> copyString(transport, name) }
                copyList(transport, "host")
                (transport["headers"] as? JsonObject)?.let { headers -> put("headers", headers) }
                if (transport.isTrue("multi_mode")) put("multi_mode", true)
                listOf("max_early_data", "mtu", "tti").forEach { name -> copyInt(transport, name, tag) }
                transport.jsonObjectField("extra", tag)?.let { extra -> put("extra", extra) }
            }
        }
        outbound.jsonObjectField("xray_finalmask", tag)?.let { mask -> put("xray_finalmask", mask) }
        outbound.jsonObjectField("xray_stream_settings", tag)?.let { stream -> put("xray_stream_settings", stream) }
    }
    return encodeSingBoxJson(
        buildJsonObject {
            put("listen", "$PluginSocksHost:$socksPort")
            if (mappingAddress.isNotBlank()) put("dial", mappingAddress)
            if (protectPath.isNotBlank()) put("protect_path", protectPath)
            put("log_level", "warn")
            put("outbound", vless)
        },
    )
}

private fun JsonObjectBuilder.copyString(source: JsonObject, name: String) {
    source.stringOrEmpty(name).takeIf(String::isNotBlank)?.let { value -> put(name, value) }
}

private fun JsonObjectBuilder.copyInt(source: JsonObject, name: String, tag: String) {
    if (source[name] == null || source.stringOrEmpty(name).isBlank()) return
    val value = source.intOrNull(name) ?: error("VLESS outbound $tag has an invalid $name")
    put(name, value)
}

/** A list option: a JSON array (imported config) or a comma/space separated string (editor). */
private fun JsonObjectBuilder.copyList(source: JsonObject, name: String) {
    val values = when (val element = source[name]) {
        is JsonArray -> element.mapNotNull { item -> (item as? JsonPrimitive)?.contentOrNull }
        is JsonPrimitive -> element.contentOrNull.orEmpty().split(',', ' ', '\n', '\r', '\t')
        else -> emptyList()
    }.map(String::trim).filter(String::isNotEmpty)
    if (values.isNotEmpty()) putJsonArray(name) { values.forEach { value -> add(value) } }
}

/** A PEM-like option kept whole: a string, or an array of its lines. */
private fun JsonObjectBuilder.copyPem(source: JsonObject, name: String) {
    when (val element = source[name]) {
        is JsonArray -> put(name, element)
        is JsonPrimitive -> element.contentOrNull?.takeIf(String::isNotBlank)?.let { value -> put(name, value) }
        else -> Unit
    }
}

/** An object option the editor may store as JSON text; null when unset. */
private fun JsonObject.jsonObjectField(name: String, tag: String): JsonObject? = when (val element = this[name]) {
    null -> null
    is JsonObject -> element.takeIf { it.isNotEmpty() }
    is JsonPrimitive -> element.contentOrNull?.takeIf(String::isNotBlank)?.let { text ->
        runCatching { Json.parseToJsonElement(text) }.getOrNull() as? JsonObject
            ?: throw IllegalArgumentException("VLESS outbound $tag: $name must be a JSON object")
    }?.takeIf { it.isNotEmpty() }
    else -> throw IllegalArgumentException("VLESS outbound $tag: $name must be a JSON object")
}

private fun JsonElement?.isPresentObject(): Boolean = when (this) {
    null -> false
    is JsonObject -> isNotEmpty()
    is JsonPrimitive -> contentOrNull.orEmpty().trim().let { text -> text.isNotEmpty() && text != "{}" }
    else -> true
}

private fun JsonObject.isTrue(name: String): Boolean = booleanOrNull(name) ?: false
