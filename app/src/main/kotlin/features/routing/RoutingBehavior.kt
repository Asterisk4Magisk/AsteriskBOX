// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.routing

import app.SingBoxRouteRuleState
import app.SingBoxRouteRuleTypeLogical
import app.visibleManagedReference
import engine.singbox.isSingBoxPortRange
import engine.singbox.isSingBoxUnsigned16
import org.asterisk.zcc.abox.R

internal val RouteRuleMatcherLabelResources = linkedMapOf(
    "clash_mode" to R.string.routing_clash_mode,
    "ip_version" to R.string.routing_ip_version,
    "network" to R.string.routing_network,
    "inbound" to R.string.routing_inbound,
    "protocol" to R.string.routing_protocol,
    "domain" to R.string.routing_domain,
    "domain_suffix" to R.string.routing_domain_suffix,
    "domain_keyword" to R.string.routing_domain_keyword,
    "domain_regex" to R.string.routing_domain_regex,
    "ip_cidr" to R.string.routing_ip_cidr,
    "ip_is_private" to R.string.routing_ip_is_private,
    "port" to R.string.routing_port,
    "port_range" to R.string.routing_port_range,
    "rule_set" to R.string.routing_rule_sets,
    "source_ip_cidr" to R.string.routing_source_ip_cidr,
    "source_ip_is_private" to R.string.routing_source_ip_is_private,
    "source_port" to R.string.routing_source_port,
    "source_port_range" to R.string.routing_source_port_range,
    "package_name" to R.string.routing_package_name,
    "network_type" to R.string.routing_network_type,
    "wifi_ssid" to R.string.routing_wifi_ssid,
    "wifi_bssid" to R.string.routing_wifi_bssid,
)

internal fun List<SingBoxRouteRuleState>.moveRouteRule(
    fromIndex: Int,
    toIndex: Int,
): List<SingBoxRouteRuleState> {
    if (fromIndex !in indices || toIndex !in indices || fromIndex == toIndex) return this
    return toMutableList().apply {
        add(toIndex, removeAt(fromIndex))
    }
}

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
        return listOf(
            RouteRuleCardMatch(
                field = "logical",
                values = listOf(logicalMode, logicalRules.size.toString()),
            ),
        )
    }

    val matches = buildList {
        clashMode.takeIf(String::isNotEmpty)?.let {
            add(RouteRuleCardMatch("clash_mode", listOf(it)))
        }
        ipVersion.takeIf { it != 0 }?.let {
            add(RouteRuleCardMatch("ip_version", listOf(it.toString())))
        }
        addRouteCardMatch("network", network)
        addRouteCardMatch("inbound", inbound)
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
