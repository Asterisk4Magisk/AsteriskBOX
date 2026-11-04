// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.outbound

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Share links of the plugin-backed protocols. Formats follow husi:
 * `mierus://` (Mieru), `sq://` (ShadowQUIC, official format) and
 * `juicity://`.
 */
internal val PluginShareLinkSchemes = setOf("mierus", "sq", "juicity")

internal fun parsePluginShareLink(link: String): JsonObject? {
    val scheme = link.substringBefore("://").lowercase()
    if (scheme !in PluginShareLinkSchemes) return null
    val uri = runCatching { URI(link) }.getOrNull() ?: return null
    val host = (uri.host ?: uri.rawAuthority?.substringAfterLast('@')?.substringBefore(':'))
        ?.removeSurrounding("[", "]")
        ?.takeIf(String::isNotBlank)
        ?: return null
    val query = parsePluginQuery(uri.rawQuery)
    // Split on ':' before decoding: ':' inside a credential is percent-encoded
    // by the encoder, so decoding first would split at the wrong place.
    val rawUserInfo = uri.rawUserInfo.orEmpty()
    val username = decode(rawUserInfo.substringBefore(':', ""))
    val password = decode(rawUserInfo.substringAfter(':', ""))
    val port = if (uri.port == -1) {
        443
    } else {
        require(uri.port in 1..65535) { "invalid port" }
        uri.port
    }
    val fragment = decode(uri.rawFragment.orEmpty())
    return when (scheme) {
        "mierus" -> buildJsonObject {
            put("type", "mieru")
            // husi writes the name into `profile`; older links use the fragment.
            val tag = fragment.ifBlank { query["profile"].orEmpty() }
            if (tag.isNotBlank()) put("tag", tag)
            put("server", host)
            put("server_port", port)
            put("username", username)
            put("password", password)
            query["mtu"]?.toIntOrNull()?.takeIf { it > 0 }?.let { put("mtu", it) }
            query["multiplexing"]?.let { level -> put("multiplexing", mieruMuxLevel(level)) }
            query["traffic-pattern"]?.let { pattern -> put("traffic_pattern", pattern) }
        }

        "sq" -> buildJsonObject {
            put("type", "shadowquic")
            put("server", host)
            put("server_port", port)
            put("username", username)
            put("password", password)
            query["sni"]?.takeIf(String::isNotBlank)?.let { put("server_name", it) }
            val udpMode = query["udp_mode"] ?: query["udp_mod"]
            if (udpMode != null) put("udp_over_stream", udpMode == "stream")
            query["zero_rtt"]?.let { value ->
                // Presence means on unless the value explicitly says otherwise.
                if (value.isEmpty() || parseFlag(value)) put("zero_rtt", true)
            }
            query["congestion"]?.takeIf { it != "copa" }?.let { put("congestion_control", it) }
            val mtu = query["mtu"]?.toIntOrNull() ?: 1280
            put("initial_mtu", mtu)
            put("min_mtu", mtu)
            query["alpn"]?.takeIf(String::isNotBlank)?.let { put("alpn", it) }
            val tag = fragment.ifBlank { query["tag"].orEmpty() }
            if (tag.isNotBlank()) put("tag", tag)
        }

        else -> buildJsonObject {
            require(username.isNotBlank()) { "juicity uuid is empty" }
            require(password.isNotBlank()) { "juicity password is empty" }
            put("type", "juicity")
            put("server", host)
            put("server_port", port)
            put("uuid", username)
            put("password", password)
            query["sni"]?.takeIf(String::isNotBlank)?.let { put("sni", it) }
            if (query["allow_insecure"]?.let(::parseFlag) == true) put("allow_insecure", true)
            query["pinned_certchain_sha256"]?.takeIf(String::isNotBlank)?.let { put("pin_sha256", it) }
            query["congestion_control"]?.takeIf(String::isNotBlank)?.let {
                put("congestion_control", it)
            }
            if (fragment.isNotBlank()) put("tag", fragment)
        }
    }
}

internal fun encodePluginShareUrl(type: String, outbound: JsonObject, remarks: String): String? {
    val server = outbound.stringValue("server").removeSurrounding("[", "]")
    if (server.isBlank()) return null
    val port = outbound.intValue("server_port").takeIf { it > 0 } ?: 443
    val hostPart = if (server.contains(':')) "[$server]" else server
    return when (type) {
        "mieru" -> buildString {
            append("mierus://")
            append(encode(outbound.stringValue("username")))
            append(':')
            append(encode(outbound.stringValue("password")))
            append('@')
            append(hostPart)
            append(':')
            append(port)
            val params = buildList {
                if (remarks.isNotBlank()) add("profile" to remarks)
                outbound.intValue("mtu").takeIf { it > 0 }?.let { add("mtu" to it.toString()) }
                outbound.stringValue("multiplexing").takeIf(String::isNotBlank)?.let {
                    add("multiplexing" to it.uppercase())
                }
                outbound.stringValue("traffic_pattern").takeIf(String::isNotBlank)?.let {
                    add("traffic-pattern" to it)
                }
            }
            appendQuery(params)
        }

        "shadowquic" -> {
            if (outbound.stringValue("sub_protocol").equals("sunnyquic", ignoreCase = true)) {
                return null
            }
            buildString {
                append("sq://")
                append(encode(outbound.stringValue("username")))
                append(':')
                append(encode(outbound.stringValue("password")))
                append('@')
                append(hostPart)
                append(':')
                append(port)
                val mtu = outbound.intValue("initial_mtu").takeIf { it > 0 } ?: 1280
                val params = buildList {
                    add("sni" to outbound.stringValue("server_name").ifBlank { server })
                    add(
                        "udp_mode" to if (outbound.booleanValue("udp_over_stream")) {
                            "stream"
                        } else {
                            "datagram"
                        },
                    )
                    if (outbound.booleanValue("zero_rtt")) add("zero_rtt" to "true")
                    outbound.stringValue("congestion_control")
                        .takeIf { it.isNotBlank() && it != "brutal" }
                        ?.let { add("congestion" to it) }
                    add("mtu" to mtu.toString())
                    outbound.stringValue("alpn").takeIf(String::isNotBlank)?.let { add("alpn" to it) }
                }
                appendQuery(params)
                if (remarks.isNotBlank()) append('#').append(encode(remarks))
            }
        }

        "juicity" -> buildString {
            append("juicity://")
            append(encode(outbound.stringValue("uuid")))
            append(':')
            append(encode(outbound.stringValue("password")))
            append('@')
            append(hostPart)
            append(':')
            append(port)
            val params = buildList {
                outbound.stringValue("sni").takeIf(String::isNotBlank)?.let { add("sni" to it) }
                if (outbound.booleanValue("allow_insecure")) add("allow_insecure" to "1")
                outbound.stringValue("pin_sha256").takeIf(String::isNotBlank)?.let {
                    add("pinned_certchain_sha256" to it)
                }
                outbound.stringValue("congestion_control").takeIf(String::isNotBlank)?.let {
                    add("congestion_control" to it)
                }
            }
            appendQuery(params)
            if (remarks.isNotBlank()) append('#').append(encode(remarks))
        }

        else -> null
    }
}

private fun StringBuilder.appendQuery(params: List<Pair<String, String>>) {
    if (params.isEmpty()) return
    append('?')
    append(params.joinToString("&") { (key, value) -> "$key=${encode(value)}" })
}

private fun parsePluginQuery(rawQuery: String?): Map<String, String> {
    if (rawQuery.isNullOrBlank()) return emptyMap()
    return rawQuery.split('&')
        .mapNotNull { item ->
            if (item.isBlank()) return@mapNotNull null
            val key = decode(item.substringBefore('='))
            val value = decode(item.substringAfter('=', ""))
            key to value
        }
        .toMap()
}

private fun parseFlag(value: String): Boolean =
    value.isNotEmpty() && value != "0" && !value.equals("false", ignoreCase = true)

private fun mieruMuxLevel(value: String): String = when (value.uppercase()) {
    "MULTIPLEXING_LOW" -> "LOW"
    "MULTIPLEXING_MEDIUM" -> "MEDIUM"
    "MULTIPLEXING_HIGH" -> "HIGH"
    "LOW", "MEDIUM", "HIGH" -> value.uppercase()
    else -> ""
}

private fun encode(value: String): String =
    URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")

private fun decode(value: String): String =
    runCatching { URLDecoder.decode(value, Charsets.UTF_8.name()) }.getOrDefault(value)
