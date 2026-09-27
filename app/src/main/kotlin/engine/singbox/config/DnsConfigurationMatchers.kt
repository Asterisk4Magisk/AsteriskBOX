// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package engine.singbox.config

import engine.network.isCidrAddress
import engine.network.isIpAddress
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

internal val DnsConfigurationMatchFields = setOf("dns_server_address", "dns_search_domain")
internal val DnsConfigurationServerTypes =
    setOf("local", "dhcp", "resolved", "tailscale", "openvpn", "openconnect")

// State uses the same tag=value1,value2 representation as existing address map matchers.
internal fun dnsConfigurationEntryTag(entry: String): String = entry.substringBefore('=').trim()

internal fun mapDnsConfigurationTags(values: List<String>, resolve: (String) -> String): List<String> =
    values.map { entry ->
        if ('=' in entry) resolve(dnsConfigurationEntryTag(entry)) + "=" + entry.substringAfter('=')
        else entry
    }

internal fun isDnsConfigurationEntry(field: String, entry: String): Boolean {
    val separator = entry.indexOf('=')
    if (separator <= 0 || separator == entry.lastIndex) return false
    if (dnsConfigurationEntryTag(entry).isBlank()) return false
    val values = entry.substring(separator + 1).split(',').map(String::trim)
    return values.all { value ->
        when (field) {
            "dns_server_address" -> '%' !in value && (isIpAddress(value) || isCidrAddress(value))
            // The core canonicalizes search domains, including trailing dots and case.
            "dns_search_domain" -> value.isNotEmpty() && value.none { it.isWhitespace() || it in "=/" }
            else -> false
        }
    }
}

internal fun JsonObjectBuilder.putDnsConfigurationMatch(field: String, entries: List<String>) {
    if (entries.isEmpty()) return
    require(entries.all { isDnsConfigurationEntry(field, it) }) { "Invalid $field mapping" }
    val valuesByTag = linkedMapOf<String, MutableList<String>>()
    entries.forEach { entry ->
        valuesByTag.getOrPut(dnsConfigurationEntryTag(entry)) { mutableListOf() }
            .addAll(entry.substringAfter('=').split(',').map(String::trim))
    }
    putJsonObject(field) {
        valuesByTag.forEach { (tag, values) ->
            putJsonArray(tag) { values.distinct().forEach(::add) }
        }
    }
}
