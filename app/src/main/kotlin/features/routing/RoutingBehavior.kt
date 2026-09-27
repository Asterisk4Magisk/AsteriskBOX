// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.routing

import app.AppState
import app.SingBoxRouteRuleState
import app.SingBoxRouteRuleTypeLogical
import app.visibleManagedReference
import engine.singbox.isSingBoxPortRange
import engine.singbox.isSingBoxUnsigned16
import app.R

internal val RouteRuleMatcherLabelResources = linkedMapOf(
    "process_name" to R.string.rule_matcher_process_name,
    "process_path" to R.string.rule_matcher_process_path,
    "process_path_regex" to R.string.rule_matcher_process_path_regex,
    "user" to R.string.rule_matcher_user,
    "user_id" to R.string.rule_matcher_user_id,

    "auth_user" to R.string.rule_matcher_auth_user,
    "client" to R.string.routing_client,
    "package_name_regex" to R.string.rule_matcher_package_name_regex,
    "network_interface_address" to R.string.rule_matcher_network_interface_address,
    "source_mac_address" to R.string.rule_matcher_source_mac_address,
    "source_hostname" to R.string.rule_matcher_source_hostname,
    "preferred_by" to R.string.rule_matcher_preferred_by,
    "network_is_expensive" to R.string.rule_matcher_network_is_expensive,

    "ip_version" to R.string.rule_matcher_ip_version,
    "network" to R.string.rule_matcher_network,
    "inbound" to R.string.rule_matcher_inbound,
    "protocol" to R.string.rule_matcher_protocol,
    "domain" to R.string.rule_matcher_domain,
    "domain_suffix" to R.string.rule_matcher_domain_suffix,
    "domain_keyword" to R.string.rule_matcher_domain_keyword,
    "domain_regex" to R.string.rule_matcher_domain_regex,
    "ip_cidr" to R.string.routing_ip_cidr,
    "ip_is_private" to R.string.routing_ip_is_private,
    "port" to R.string.rule_matcher_port,
    "port_range" to R.string.rule_matcher_port_range,
    "rule_set" to R.string.rule_matcher_rule_set,
    "source_ip_cidr" to R.string.rule_matcher_source_ip_cidr,
    "source_ip_is_private" to R.string.rule_matcher_source_ip_is_private,
    "source_port" to R.string.rule_matcher_source_port,
    "source_port_range" to R.string.rule_matcher_source_port_range,
    "package_name" to R.string.rule_matcher_package_name,
    "network_type" to R.string.rule_matcher_network_type,
    "wifi_ssid" to R.string.rule_matcher_wifi_ssid,
    "wifi_bssid" to R.string.rule_matcher_wifi_bssid,
    "dns_server_address" to R.string.rule_matcher_dns_server_address,
    "dns_search_domain" to R.string.rule_matcher_dns_search_domain,
)

internal fun AppState.withRouteRuleEnabled(
    ruleId: Int,
    enabled: Boolean,
): AppState = copy(
    routeRules = routeRules.map { rule ->
        if (rule.id == ruleId) rule.copy(enabled = enabled) else rule
    },
)

internal fun SingBoxRouteRuleState.nextLogicalRouteRuleId(): Int =
    flattenRouteRuleIds().maxOrNull()?.plus(1) ?: 1

internal fun routeRuleOutboundLabel(
    rule: SingBoxRouteRuleState,
    labels: Map<String, String>,
    unavailableLabel: String,
    globalLabel: String,
): String {
    val outbound = rule.outbound.trim()
    return if (outbound.isEmpty()) {
        globalLabel
    } else {
        visibleManagedReference(outbound, labels, unavailableLabel)
    }
}

internal data class RouteRuleCardMatch(
    val field: String,
    val values: List<String> = emptyList(),
)

internal val RouteRuleCardMatch.officialFieldName: String?
    get() = field.takeIf(RouteRuleMatcherLabelResources::containsKey)

internal fun SingBoxRouteRuleState.routeRuleCardMatches(): List<RouteRuleCardMatch> {
    if (type == SingBoxRouteRuleTypeLogical) {
        return buildList {
            add(RouteRuleCardMatch(
                field = "logical",
                values = listOf(logicalMode, logicalRules.size.toString()),
            ))
        }
    }

    val matches = buildList {
        ipVersion.takeIf { it != 0 }?.let {
            add(RouteRuleCardMatch("ip_version", listOf(it.toString())))
        }
        addRouteCardMatch("network", network)
        addRouteCardMatch("inbound", inbound)
        addRouteCardMatch("process_name", processName)
        addRouteCardMatch("process_path", processPath)
        addRouteCardMatch("process_path_regex", processPathRegex)
        addRouteCardMatch("user", user)
        addRouteCardMatch("user_id", userId)

        addRouteCardMatch("auth_user", authUser)
        addRouteCardMatch("client", client)
        addRouteCardMatch("package_name_regex", packageNameRegex)
        addRouteCardMatch("network_interface_address", networkInterfaceAddress)
        addRouteCardMatch("source_mac_address", sourceMacAddress)
        addRouteCardMatch("source_hostname", sourceHostname)
        addRouteCardMatch("preferred_by", preferredBy)
        if (networkIsExpensive) add(RouteRuleCardMatch("network_is_expensive"))

        addRouteCardMatch("protocol", protocol)
        addRouteCardMatch("domain", domain)
        addRouteCardMatch("domain_suffix", domainSuffix)
        addRouteCardMatch("domain_keyword", domainKeyword)
        addRouteCardMatch("domain_regex", domainRegex)
        addRouteCardMatch("ip_cidr", ipCidr)
        if (ipIsPrivate) add(RouteRuleCardMatch("ip_is_private"))
        addRouteCardMatch("port", port)
        addRouteCardMatch("port_range", portRange)
        addRouteCardMatch("rule_set", ruleSet)
        addRouteCardMatch("source_ip_cidr", sourceIpCidr)
        if (sourceIpIsPrivate) add(RouteRuleCardMatch("source_ip_is_private"))
        addRouteCardMatch("source_port", sourcePort)
        addRouteCardMatch("source_port_range", sourcePortRange)
        addRouteCardMatch("package_name", packageName)
        addRouteCardMatch("network_type", networkType)
        addRouteCardMatch("wifi_ssid", wifiSsid)
        addRouteCardMatch("wifi_bssid", wifiBssid)
        addRouteCardMatch("dns_server_address", dnsServerAddress)
        addRouteCardMatch("dns_search_domain", dnsSearchDomain)
    }
    return matches.ifEmpty { listOf(RouteRuleCardMatch(field = "all")) }
}

private fun MutableList<RouteRuleCardMatch>.addRouteCardMatch(
    field: String,
    values: List<String>,
) {
    if (values.isNotEmpty()) {
        add(RouteRuleCardMatch(field = field, values = values))
    }
}

private fun SingBoxRouteRuleState.flattenRouteRuleIds(): List<Int> =
    listOf(id) + logicalRules.flatMap(SingBoxRouteRuleState::flattenRouteRuleIds)

internal fun isRoutePort(value: String): Boolean =
    isSingBoxUnsigned16(value)

internal fun isRoutePortRange(value: String): Boolean =
    isSingBoxPortRange(value)
