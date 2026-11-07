// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.outbound

import engine.singbox.plugin.decodeMieruTrafficPattern
import engine.singbox.plugin.encodeMieruTrafficPatternBase64
import engine.singbox.plugin.mieruMultiplexingLevel
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.URI
import utils.booleanOrFalse
import utils.decodeUrlComponentPreservingPlus
import utils.encodeUrlComponent
import utils.intOrZero
import utils.stringOrEmpty

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
    val username = rawUserInfo.substringBefore(':', "").decodeUrlComponentPreservingPlus()
    val password = rawUserInfo.substringAfter(':', "").decodeUrlComponentPreservingPlus()
    val port = if (uri.port == -1) {
        443
    } else {
        require(uri.port in 1..65535) { "invalid port" }
        uri.port
    }
    val fragment = uri.rawFragment.orEmpty().decodeUrlComponentPreservingPlus()
    return when (scheme) {
        "mierus" -> buildJsonObject {
            put("type", "mieru")
            // husi writes the name into `profile`; older links use the fragment.
            val tag = fragment.ifBlank { query["profile"].orEmpty() }
            if (tag.isNotBlank()) put("tag", tag)
            put("server", host)
            // Mieru links carry repeatable port=/protocol= pairs in the query; the
            // first binding is used. Links without them keep the port in the authority.
            val queryPairs = parsePluginQueryPairs(uri.rawQuery)
            val ports = queryPairs.filter { (key, _) -> key == "port" }.map { (_, value) -> value }
            val protocols = queryPairs.filter { (key, _) -> key == "protocol" }.map { (_, value) -> value }
            put("server_port", ports.firstOrNull()?.let(::mieruBindingPort) ?: port)
            protocols.firstOrNull()?.uppercase()?.takeIf { it == "TCP" || it == "UDP" }?.let { protocol ->
                put("protocol", protocol)
            }
            put("username", username)
            put("password", password)
            query["mtu"]?.toIntOrNull()?.takeIf { it > 0 }?.let { put("mtu", it) }
            query["multiplexing"]?.let { level -> put("multiplexing", mieruMuxLevel(level)) }
            query["traffic-pattern"]?.takeIf(String::isNotBlank)?.let { pattern ->
                // Stored as JSON for the editor; an undecodable value is kept so the start reports it.
                put("traffic_pattern", decodeMieruTrafficPattern(pattern)?.toString() ?: pattern)
            }
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
    val server = outbound.stringOrEmpty("server").removeSurrounding("[", "]")
    if (server.isBlank()) return null
    val port = outbound.intOrZero("server_port").takeIf { it > 0 } ?: 443
    val hostPart = if (server.contains(':')) "[$server]" else server
    return when (type) {
        // Official mieru simple link: port and protocol are query parameters,
        // enum values use their protobuf names (mieru pkg/appctl/url.go). The
        // port is repeated in the authority, which mieru ignores, for husi and
        // older AsteriskBOX builds that only read it there.
        "mieru" -> buildString {
            append("mierus://")
            append(outbound.stringOrEmpty("username").encodeUrlComponent())
            append(':')
            append(outbound.stringOrEmpty("password").encodeUrlComponent())
            append('@')
            append(hostPart)
            append(':')
            append(port)
            val params = buildList {
                outbound.intOrZero("mtu").takeIf { it > 0 }?.let { add("mtu" to it.toString()) }
                mieruMultiplexingLevel(outbound.stringOrEmpty("multiplexing"))?.let { level ->
                    add("multiplexing" to level)
                }
                add("port" to port.toString())
                add("profile" to remarks.ifBlank { "default" })
                add("protocol" to outbound.stringOrEmpty("protocol").ifBlank { "TCP" }.uppercase())
                outbound.stringOrEmpty("traffic_pattern").takeIf(String::isNotBlank)?.let { raw ->
                    add("traffic-pattern" to (encodeMieruTrafficPatternBase64(raw) ?: raw))
                }
            }
            appendQuery(params)
        }

        "shadowquic" -> {
            if (outbound.stringOrEmpty("sub_protocol").equals("sunnyquic", ignoreCase = true)) {
                return null
            }
            buildString {
                append("sq://")
                append(outbound.stringOrEmpty("username").encodeUrlComponent())
                append(':')
                append(outbound.stringOrEmpty("password").encodeUrlComponent())
                append('@')
                append(hostPart)
                append(':')
                append(port)
                val mtu = outbound.intOrZero("initial_mtu").takeIf { it > 0 } ?: 1280
                val params = buildList {
                    add("sni" to outbound.stringOrEmpty("server_name").ifBlank { server })
                    add(
                        "udp_mode" to if (outbound.booleanOrFalse("udp_over_stream")) {
                            "stream"
                        } else {
                            "datagram"
                        },
                    )
                    if (outbound.booleanOrFalse("zero_rtt")) add("zero_rtt" to "true")
                    outbound.stringOrEmpty("congestion_control")
                        .takeIf { it.isNotBlank() && it != "brutal" }
                        ?.let { add("congestion" to it) }
                    add("mtu" to mtu.toString())
                    outbound.stringOrEmpty("alpn").takeIf(String::isNotBlank)?.let { add("alpn" to it) }
                }
                appendQuery(params)
                if (remarks.isNotBlank()) append('#').append(remarks.encodeUrlComponent())
            }
        }

        "juicity" -> buildString {
            append("juicity://")
            append(outbound.stringOrEmpty("uuid").encodeUrlComponent())
            append(':')
            append(outbound.stringOrEmpty("password").encodeUrlComponent())
            append('@')
            append(hostPart)
            append(':')
            append(port)
            val params = buildList {
                outbound.stringOrEmpty("sni").takeIf(String::isNotBlank)?.let { add("sni" to it) }
                if (outbound.booleanOrFalse("allow_insecure")) add("allow_insecure" to "1")
                outbound.stringOrEmpty("pin_sha256").takeIf(String::isNotBlank)?.let {
                    add("pinned_certchain_sha256" to it)
                }
                outbound.stringOrEmpty("congestion_control").takeIf(String::isNotBlank)?.let {
                    add("congestion_control" to it)
                }
            }
            appendQuery(params)
            if (remarks.isNotBlank()) append('#').append(remarks.encodeUrlComponent())
        }

        else -> null
    }
}

private fun StringBuilder.appendQuery(params: List<Pair<String, String>>) {
    if (params.isEmpty()) return
    append('?')
    append(params.joinToString("&") { (key, value) -> "$key=${value.encodeUrlComponent()}" })
}

private fun parsePluginQuery(rawQuery: String?): Map<String, String> = parsePluginQueryPairs(rawQuery).toMap()

/** Query parameters in order, keeping repeated keys. */
private fun parsePluginQueryPairs(rawQuery: String?): List<Pair<String, String>> {
    if (rawQuery.isNullOrBlank()) return emptyList()
    return rawQuery.split('&')
        .mapNotNull { item ->
            if (item.isBlank()) return@mapNotNull null
            val key = item.substringBefore('=').decodeUrlComponentPreservingPlus()
            val value = item.substringAfter('=', "").decodeUrlComponentPreservingPlus()
            key to value
        }
}

/** A mieru `port=` value: a port, or a `begin-end` range whose first port is used. */
private fun mieruBindingPort(value: String): Int? =
    value.trim().substringBefore('-').trim().toIntOrNull()?.takeIf { it in 1..65535 }

private fun parseFlag(value: String): Boolean =
    value.isNotEmpty() && value != "0" && !value.equals("false", ignoreCase = true)

/** Editor value for a link's multiplexing level; mieru calls the middle level MIDDLE. */
private fun mieruMuxLevel(value: String): String = when (mieruMultiplexingLevel(value)) {
    "MULTIPLEXING_OFF" -> "OFF"
    "MULTIPLEXING_LOW" -> "LOW"
    "MULTIPLEXING_MIDDLE" -> "MEDIUM"
    "MULTIPLEXING_HIGH" -> "HIGH"
    else -> ""
}
