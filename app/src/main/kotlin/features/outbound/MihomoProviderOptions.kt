// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.outbound

import engine.singbox.config.SingBoxJson
import features.importing.ImportOutcome
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject

/** Provider filtering precedes renaming, as in Mihomo and the NG importer. */
internal class MihomoProviderOptions private constructor(
    private val filters: List<Regex>,
    private val excludes: List<Regex>,
    private val excludedTypes: Set<String>,
    private val overrides: Map<*, *>,
    private val replacements: List<Pair<Regex, String>>,
) {
    fun applyToYaml(source: List<*>): List<Map<*, *>> {
        val nodes = source.map { requireNotNull(it as? Map<*, *>) { "Provider node must be a mapping" } }
        return select(nodes, { it["name"].scalar().orEmpty() }, { it["type"].scalar().orEmpty() }).map { node ->
            node.toMutableMap().apply {
                this["name"] = renamed(node["name"].scalar().orEmpty())
                overrides.forEach { (key, value) ->
                    if (key !in setOf("additional-prefix", "additional-suffix", "proxy-name")) this[key] = value
                }
            }
        }
    }

    private fun <T> select(source: List<T>, name: (T) -> String, type: (T) -> String): List<T> {
        val seen = mutableSetOf<String>()
        return filters.flatMap { filter ->
            source.filter { node ->
                canonicalType(type(node)) !in excludedTypes &&
                    excludes.none { it.containsMatchIn(name(node)) } &&
                    filter.containsMatchIn(name(node)) && seen.add(name(node))
            }
        }
    }

    fun apply(source: ImportOutcome<ImportedSingBoxOutbound>): ImportOutcome<ImportedSingBoxOutbound> {
        val selected = select(source.accepted, { it.remarks }, { it.type })
        return ImportOutcome(
            format = source.format,
            detectedCount = source.detectedCount - source.accepted.size + selected.size,
            accepted = selected.map(::overrideNode),
            duplicateCount = source.duplicateCount,
            issues = source.issues,
            mutations = source.mutations,
            priorOmittedDetailCount = source.omittedDetailCount,
        )
    }

    private fun renamed(original: String): String = overrides["additional-prefix"].scalar().orEmpty() +
            replacements.fold(original) { text, (pattern, target) ->
                pattern.replace(text) { match -> expandMihomoReplacement(target, text, match) }
            } + overrides["additional-suffix"].scalar().orEmpty()

    private fun overrideNode(node: ImportedSingBoxOutbound): ImportedSingBoxOutbound {
        val name = renamed(node.remarks)
        val json = SingBoxJson.parseToJsonElement(node.json).jsonObject.toMutableMap()
        json["tag"] = JsonPrimitive(name)
        mapOf("tfo" to "tcp_fast_open", "mptcp" to "tcp_multi_path").forEach { (from, to) ->
            overrides.boolean(from)?.let { json[to] = JsonPrimitive(it) }
        }
        mapOf("dialer-proxy" to "detour", "interface-name" to "bind_interface").forEach { (from, to) ->
            overrides[from].scalar()?.let { value ->
                if (value.isBlank()) json.remove(to) else json[to] = JsonPrimitive(value)
            }
        }
        overrides["routing-mark"].scalar()?.toInt()?.let { mark ->
            if (mark == 0) json.remove("routing_mark") else json["routing_mark"] = JsonPrimitive(mark)
        }
        overrides.boolean("udp")?.let { enabled ->
            if (node.type in setOf("socks", "shadowsocks", "vmess", "vless", "trojan", "hysteria", "hysteria2", "tuic")) {
                if (enabled) json.remove("network") else json["network"] = JsonPrimitive("tcp")
            } else require(enabled) { "UDP override is not supported for this outbound" }
        }
        overrides.boolean("udp-over-tcp")?.let { enabled ->
            if (node.type in setOf("socks", "shadowsocks", "naive")) {
                if (enabled) {
                    val options = (json["udp_over_tcp"] as? JsonObject).orEmpty().toMutableMap()
                    options["enabled"] = JsonPrimitive(true)
                    json["udp_over_tcp"] = JsonObject(options)
                } else json.remove("udp_over_tcp")
            } else require(!enabled) { "UDP over TCP override is not supported for this outbound" }
        }
        val tls = json["tls"] as? JsonObject
        if (tls != null && (tls["enabled"] as? JsonPrimitive)?.booleanOrNull == true) {
            val adjusted = tls.toMutableMap()
            overrides.boolean("skip-cert-verify")?.let { insecure ->
                if (node.type == "naive") require(!insecure) { "Naive certificate verification cannot be disabled" }
                else adjusted["insecure"] = JsonPrimitive(insecure)
            }
            overrides["name-cert-verify"].scalar()?.let { name ->
                require(node.type != "naive" || name.isBlank()) { "Naive certificate name override is not supported" }
                if (name.isBlank()) adjusted.remove("certificate_server_name")
                else adjusted["certificate_server_name"] = JsonPrimitive(name)
            }
            json["tls"] = JsonObject(adjusted)
        }
        if (node.type in setOf("hysteria", "hysteria2")) {
            listOf("up", "down").forEach { direction ->
                if (overrides.containsKey(direction)) {
                    val mbps = bandwidthMbps(overrides[direction])
                    require(mbps >= 0) { "Invalid provider bandwidth override" }
                    if (mbps == 0) json.remove("${direction}_mbps")
                    else json["${direction}_mbps"] = JsonPrimitive(mbps)
                }
            }
        }
        // Preserve sourceTag so references using the original provider names can be resolved.
        return node.copy(remarks = name, json = JsonObject(json).toString())
    }

    companion object {
        fun from(provider: Map<*, *>): MihomoProviderOptions {
            val overrides = (provider["override"] as? Map<*, *> ?: emptyMap<Any, Any>().also {
                require(provider["override"] == null) { "Provider override must be a mapping" }
            }).toMutableMap()
            if ("dialer-proxy" in provider && "dialer-proxy" !in overrides) {
                overrides["dialer-proxy"] = provider["dialer-proxy"]
            }
            val supported = setOf(
                "additional-prefix", "additional-suffix", "proxy-name", "skip-cert-verify",
                "name-cert-verify", "up", "down", "tfo", "mptcp", "udp", "udp-over-tcp", "ip-version",
                "dialer-proxy", "interface-name", "routing-mark",
            )
            require(overrides.keys.all { it in supported }) { "Unsupported provider override" }
            listOf("tfo", "mptcp", "udp", "udp-over-tcp", "skip-cert-verify").forEach { key ->
                require(key !in overrides || overrides.boolean(key) != null) { "Invalid boolean provider override" }
            }
            listOf("additional-prefix", "additional-suffix", "name-cert-verify", "up", "down", "dialer-proxy", "interface-name").forEach { key ->
                require(key !in overrides || overrides[key].scalar() != null) { "Invalid provider override value" }
            }
            require("routing-mark" !in overrides || overrides["routing-mark"].scalar()?.toIntOrNull()?.let { it >= 0 } == true) {
                "Invalid provider routing mark"
            }
            require("ip-version" !in overrides || overrides["ip-version"].scalar()?.lowercase() == "dual") {
                "Unsupported provider IP version override"
            }
            val rawReplacements = overrides["proxy-name"] as? List<*> ?: emptyList<Any>().also {
                require(overrides["proxy-name"] == null) { "Provider proxy-name must be a list" }
            }
            val replacements = rawReplacements.map { value ->
                val entry = value as? Map<*, *> ?: error("Provider rename entry must be a mapping")
                Regex(requireNotNull(entry["pattern"].scalar())) to requireNotNull(entry["target"].scalar())
            }
            listOf("filter", "exclude-filter", "exclude-type").forEach { key ->
                require(key !in provider || provider[key].scalar() != null) { "Invalid provider filter" }
            }
            return MihomoProviderOptions(
                filters = provider["filter"].scalar().orEmpty().split('`').map(::Regex),
                excludes = provider["exclude-filter"].scalar()?.takeIf(String::isNotBlank)?.split('`')?.map(::Regex).orEmpty(),
                excludedTypes = provider["exclude-type"].scalar()?.split('|')?.map(::canonicalType)?.toSet().orEmpty(),
                overrides = overrides,
                replacements = replacements,
            )
        }
    }
}

private fun Any?.scalar(): String? = when (this) {
    is String, is Number, is Boolean -> toString()
    else -> null
}

private fun Map<*, *>.boolean(name: String): Boolean? = when (val value = get(name)) {
    is Boolean -> value
    is String -> value.toBooleanStrictOrNull()
    else -> null
}

private fun canonicalType(value: String): String = when (val type = value.trim().lowercase()) {
    "shadowsocks" -> "ss"
    "socks", "socks4" -> "socks5"
    "hy1" -> "hysteria"
    "hy2" -> "hysteria2"
    else -> type
}

/** Same regexp2 replacement syntax as NG; JVM replacement escaping has different semantics. */
private fun expandMihomoReplacement(template: String, input: String, match: MatchResult): String = buildString {
    fun groupValue(reference: String): String? {
        val number = reference.toIntOrNull()
        if (number != null) {
            return if (number in match.groups.indices) match.groups[number]?.value.orEmpty() else null
        }
        return runCatching { match.groups[reference]?.value.orEmpty() }.getOrNull()
    }
    var index = 0
    while (index < template.length) {
        if (template[index] != '$' || index + 1 == template.length) {
            append(template[index++])
            continue
        }
        val next = template[index + 1]
        val special = when (next) {
            '$' -> "$"
            '&' -> match.value
            '`' -> input.substring(0, match.range.first)
            '\'' -> input.substring(match.range.last + 1)
            '+' -> match.groups[match.groups.size - 1]?.value.orEmpty()
            '_' -> input
            else -> null
        }
        if (special != null) {
            append(special)
            index += 2
            continue
        }
        val end = when (next) {
            '{' -> template.indexOf('}', index + 2).takeIf { it >= 0 }?.plus(1)
            in '0'..'9' -> {
                var cursor = index + 2
                while (cursor < template.length && template[cursor] in '0'..'9') cursor++
                cursor
            }
            else -> null
        }
        if (end != null) {
            val reference = if (next == '{') template.substring(index + 2, end - 1) else template.substring(index + 1, end)
            append(groupValue(reference) ?: template.substring(index, end))
            index = end
        } else append(template[index++])
    }
}
