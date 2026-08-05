// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package engine.singbox.config

import app.AppState
import app.SingBoxDnsRuleMatchState
import app.SingBoxDnsRuleMatchers
import app.SingBoxDnsRuleState
import app.SingBoxDnsServerState
import app.SingBoxDnsServerTypes
import app.effectiveLocalDnsEnabled
import app.modes.RunModeVpnService
import app.modes.SingBoxModeDirect
import app.modes.SingBoxModeGlobal
import engine.singbox.DefaultSingBoxDnsFakeIpRange
import engine.singbox.DefaultSingBoxDnsServers
import engine.singbox.SingBoxUnsigned32Max
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import utils.toTrimmedNonEmptyDistinctList

internal data class SingBoxDnsCompileResult(
    val dns: JsonObject,
    val defaultDomainResolver: String,
)

internal object SingBoxDnsCompiler {
    fun compile(appState: AppState): SingBoxDnsCompileResult? {
        if (!appState.effectiveLocalDnsEnabled) return null

        val sourceServers = appState.dnsServers.ifEmpty { DefaultSingBoxDnsServers }
        require(sourceServers.map(SingBoxDnsServerState::id).distinct().size == sourceServers.size) {
            "Duplicate managed DNS server id"
        }
        val servers = sourceServers.map { server ->
            val normalized = server.sanitized()
            require(normalized.type in SingBoxDnsServerTypes) {
                "Unsupported DNS server type: ${normalized.type}"
            }
            normalized.toJson()
        }
        val serverTags = servers.map { server -> (server["tag"] as JsonPrimitive).content }
        val defaultServer = appState.dnsFinal.trim().takeIf { tag -> tag in serverTags }
            ?: serverTags.first()
        val defaultDomainResolver = appState.routeDefaultDomainResolver
            .trim()
            .takeIf { tag -> tag in serverTags }
            ?: defaultServer

        return SingBoxDnsCompileResult(
            dns = buildJsonObject {
                put("servers", JsonArray(servers))
                val rules = appState.dnsRules
                    .filter(SingBoxDnsRuleState::enabled)
                    .map(SingBoxDnsRuleState::sanitized)
                    .mapNotNull { rule ->
                        if (appState.runMode == RunModeVpnService) {
                            rule
                        } else {
                            rule.forSingBoxMode(appState.singBoxMode)
                        }
                    }
                    .map(SingBoxDnsRuleState::toJson)
                if (rules.isNotEmpty()) put("rules", JsonArray(rules))
                put("final", defaultServer)
                put(
                    "strategy",
                    when {
                        appState.enableIpv6Prefer -> "prefer_ipv6"
                        !appState.enableIpv6 -> "ipv4_only"
                        else -> "prefer_ipv4"
                    },
                )
                appState.dnsCacheCapacity.toLongOrNull()
                    ?.takeIf { capacity -> capacity in 1..SingBoxUnsigned32Max }
                    ?.let { capacity -> put("cache_capacity", capacity) }
                if (appState.dnsOptimisticCache) {
                    put("optimistic", true)
                } else {
                    if (appState.dnsDisableCache) put("disable_cache", true)
                    if (appState.dnsDisableExpire) put("disable_expire", true)
                }
                appState.dnsTimeout.trim().takeIf(String::isNotEmpty)?.let { timeout ->
                    put("timeout", timeout)
                }
            },
            defaultDomainResolver = defaultDomainResolver,
        )
    }
}

private fun SingBoxDnsRuleState.forSingBoxMode(mode: Int): SingBoxDnsRuleState? {
    val modeMatch = matches.firstOrNull { match -> match.field == "clash_mode" }
        ?: return this
    val activeMode = when (mode) {
        SingBoxModeGlobal -> "Global"
        SingBoxModeDirect -> "Direct"
        else -> "Rule"
    }
    val matchesActiveMode = modeMatch.values.any { value ->
        value.equals(activeMode, ignoreCase = true)
    }
    if (!matchesActiveMode && !invert) return null
    if (!matchesActiveMode) {
        return copy(
            matches = emptyList(),
            ipVersion = "",
            network = "",
            invert = false,
        )
    }
    val remainingMatches = matches.filterNot { match -> match.field == "clash_mode" }
    if (invert && remainingMatches.isEmpty() && ipVersion.isBlank() && network.isBlank()) {
        return null
    }
    return copy(matches = remainingMatches)
}

internal fun SingBoxDnsServerState.sanitized(): SingBoxDnsServerState =
    copy(
        remarks = remarks.trim(),
        type = type.trim().lowercase(),
        server = server.trim(),
        serverPort = serverPort.trim(),
        path = path.trim(),
        hostsPaths = hostsPaths.toTrimmedNonEmptyDistinctList(),
        predefinedHosts = predefinedHosts.toTrimmedNonEmptyDistinctList(),
        interfaceName = interfaceName.trim(),
        interfaceNames = interfaceNames.toTrimmedNonEmptyDistinctList(),
        inet4Range = inet4Range.trim(),
        inet6Range = inet6Range.trim(),
        endpoint = endpoint.trim(),
        service = service.trim(),
        neighborDomain = neighborDomain.toTrimmedNonEmptyDistinctList(),
        domainResolver = domainResolver.trim(),
        detour = detour.trim(),
        tlsServerName = tlsServerName.trim(),
        servers = servers.toTrimmedNonEmptyDistinctList(),
    )

internal fun SingBoxDnsRuleState.sanitized(): SingBoxDnsRuleState =
    copy(
        remarks = remarks.trim(),
        matches = matches
            .map(SingBoxDnsRuleMatchState::sanitized)
            .filter { match -> match.field in SingBoxDnsRuleMatchers && match.values.isNotEmpty() }
            .groupBy(SingBoxDnsRuleMatchState::field)
            .map { (field, matches) ->
                SingBoxDnsRuleMatchState(
                    field = field,
                    values = matches.flatMap(SingBoxDnsRuleMatchState::values)
                        .toTrimmedNonEmptyDistinctList(),
                    encodeAsString = matches.any(SingBoxDnsRuleMatchState::encodeAsString),
                )
            },
        ipVersion = ipVersion.trim(),
        network = network.trim(),
        action = action.trim(),
        server = server.trim(),
        rewriteTtl = rewriteTtl.trim(),
        timeout = timeout.trim(),
        clientSubnet = clientSubnet.trim(),
        rejectMethod = rejectMethod.trim(),
        rcode = rcode.trim(),
        answer = answer.toTrimmedNonEmptyDistinctList(),
        ns = ns.toTrimmedNonEmptyDistinctList(),
        extra = extra.toTrimmedNonEmptyDistinctList(),
    )

internal fun SingBoxDnsRuleMatchState.sanitized(): SingBoxDnsRuleMatchState =
    copy(
        field = field.trim(),
        values = values.toTrimmedNonEmptyDistinctList(),
    )

private fun SingBoxDnsServerState.toJson(): JsonObject = buildJsonObject {
    put("type", type)
    put("tag", tag)

    when (type) {
        "local" -> {
            if (preferGo) put("prefer_go", true)
            putStringArrayIfNotEmpty("neighbor_domain", neighborDomain)
        }
        "hosts" -> {
            putStringArrayIfNotEmpty("path", hostsPaths)
            val hosts = parsePredefinedHosts(predefinedHosts)
            if (hosts.isNotEmpty()) {
                putJsonObject("predefined") {
                    hosts.forEach { (domain, addresses) ->
                        if (addresses.size == 1) {
                            put(domain, addresses.first())
                        } else {
                            putJsonArray(domain) { addresses.forEach(::add) }
                        }
                    }
                }
            }
        }
        "group" -> {
            putStringArrayIfNotEmpty("servers", servers)
        }
        "udp", "tcp" -> putNetworkServerFields(this@toJson, includeTls = false, includePath = false)
        "tls", "quic" -> putNetworkServerFields(this@toJson, includeTls = true, includePath = false)
        "https", "h3" -> putNetworkServerFields(this@toJson, includeTls = true, includePath = true)
        "dhcp" -> putIfNotBlank("interface", interfaceName)
        "mdns" -> putStringArrayIfNotEmpty("interface", interfaceNames)
        "fakeip" -> {
            put("inet4_range", inet4Range.ifBlank { DefaultSingBoxDnsFakeIpRange })
            putIfNotBlank("inet6_range", inet6Range)
        }
        "tailscale", "openconnect", "openvpn" -> {
            putIfNotBlank("endpoint", endpoint)
            if (acceptDefaultResolvers) put("accept_default_resolvers", true)
            if (acceptSearchDomain) put("accept_search_domain", true)
        }
        "resolved" -> {
            putIfNotBlank("service", service)
            if (acceptDefaultResolvers) put("accept_default_resolvers", true)
        }
    }

    if (type in DialDnsServerTypes) {
        putIfNotBlank("domain_resolver", domainResolver)
        putIfNotBlank("detour", detour)
    }
}

private fun kotlinx.serialization.json.JsonObjectBuilder.putNetworkServerFields(
    serverState: SingBoxDnsServerState,
    includeTls: Boolean,
    includePath: Boolean,
) {
    putIfNotBlank("server", serverState.server)
    serverState.serverPort.toIntOrNull()?.takeIf { port -> port in 1..65535 }?.let { port ->
        put("server_port", port)
    }
    if (includePath) putIfNotBlank("path", serverState.path)
    if (includeTls) {
        putJsonObject("tls") {
            put("enabled", true)
            putIfNotBlank("server_name", serverState.tlsServerName)
            if (serverState.tlsInsecure) put("insecure", true)
        }
    }
}

private fun SingBoxDnsRuleState.toJson(): JsonObject = buildJsonObject {
    matches.filter { match -> match.field in SingBoxDnsRuleMatchers }.forEach { match ->
        when (match.field) {
            "source_port", "port" -> {
                val numbers = match.values.mapNotNull(String::toIntOrNull)
                if (numbers.isNotEmpty()) {
                    putJsonArray(match.field) { numbers.forEach(::add) }
                }
            }
            "query_type" -> {
                putJsonArray(match.field) {
                    match.values.forEach { value ->
                        value.toIntOrNull()?.let(::add) ?: add(value)
                    }
                }
            }
            "response_rcode" -> {
                val value = match.values.first()
                val code = value.toIntOrNull()
                if (code != null) put(match.field, code) else put(match.field, value)
            }
            "match_response" -> {
                val value = match.values.first()
                if (match.encodeAsString) {
                    put(match.field, value)
                } else {
                    value.toBooleanStrictOrNull()
                        ?.let { put(match.field, it) }
                        ?: put(match.field, value)
                }
            }
            "interface_address", "network_interface_address" -> {
                val addressMap = parseDnsAddressMap(match.values)
                if (addressMap.isNotEmpty()) {
                    putJsonObject(match.field) {
                        addressMap.forEach { (name, addresses) ->
                            putJsonArray(name) { addresses.forEach(::add) }
                        }
                    }
                }
            }
            else -> putStringArrayIfNotEmpty(match.field, match.values)
        }
    }
    ipVersion.toIntOrNull()?.takeIf { version -> version == 4 || version == 6 }?.let { version ->
        put("ip_version", version)
    }
    putIfNotBlank("network", network)
    if (invert) put("invert", true)

    put("action", action)
    when (action) {
        "route", "evaluate" -> {
            putIfNotBlank("server", server)
            if (action == "evaluate") putIfNotBlank("tag", evaluationTag)
            putRouteOptions(this@toJson)
        }
        "route-options" -> putRouteOptions(this@toJson)
        "reject" -> {
            if (rejectMethod == "drop") {
                put("method", "drop")
            } else if (noDrop) {
                put("no_drop", true)
            }
        }
        "predefined" -> {
            rcode.takeIf(String::isNotBlank)?.let { value ->
                val code = value.toIntOrNull()
                if (code != null) put("rcode", code) else put("rcode", value)
            }
            putStringArrayIfNotEmpty("answer", answer)
            putStringArrayIfNotEmpty("ns", ns)
            putStringArrayIfNotEmpty("extra", extra)
        }
    }
}

private fun kotlinx.serialization.json.JsonObjectBuilder.putRouteOptions(ruleState: SingBoxDnsRuleState) {
    if (ruleState.disableCache) put("disable_cache", true)
    ruleState.rewriteTtl.toLongOrNull()
        ?.takeIf { ttl -> ttl in 0..SingBoxUnsigned32Max }
        ?.let { ttl -> put("rewrite_ttl", ttl) }
    putIfNotBlank("timeout", ruleState.timeout)
    putIfNotBlank("client_subnet", ruleState.clientSubnet)
}

private fun kotlinx.serialization.json.JsonObjectBuilder.putIfNotBlank(
    key: String,
    value: String,
) {
    value.takeIf(String::isNotBlank)?.let { put(key, it) }
}

private fun kotlinx.serialization.json.JsonObjectBuilder.putStringArrayIfNotEmpty(
    key: String,
    values: List<String>,
) {
    if (values.isNotEmpty()) {
        putJsonArray(key) { values.forEach(::add) }
    }
}

private fun parsePredefinedHosts(values: List<String>): Map<String, List<String>> =
    buildMap {
        values.forEach { entry ->
            val separator = entry.indexOf('=')
            if (separator <= 0 || separator >= entry.lastIndex) return@forEach
            val domain = entry.substring(0, separator).trim()
            val addresses = entry.substring(separator + 1)
                .split(',')
                .map(String::trim)
                .filter(String::isNotEmpty)
                .distinct()
            if (domain.isNotEmpty() && addresses.isNotEmpty()) put(domain, addresses)
        }
    }

private fun parseDnsAddressMap(values: List<String>): Map<String, List<String>> =
    values
        .mapNotNull { entry ->
            val separator = entry.indexOf('=')
            if (separator <= 0 || separator >= entry.lastIndex) return@mapNotNull null
            val name = entry.substring(0, separator).trim()
            val addresses = entry.substring(separator + 1)
                .split(',')
                .map(String::trim)
                .filter(String::isNotEmpty)
            if (name.isEmpty() || addresses.isEmpty()) null else name to addresses
        }
        .groupBy(Pair<String, List<String>>::first, Pair<String, List<String>>::second)
        .mapValues { (_, addressLists) -> addressLists.flatten().distinct() }

private val DialDnsServerTypes = setOf(
    "udp",
    "tcp",
    "tls",
    "quic",
    "https",
    "h3",
    "dhcp",
    "mdns",
)
