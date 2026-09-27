// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.routing

import engine.singbox.singBoxRuleMatcherValueError
import app.AppState
import app.SingBoxRouteRuleActionReject
import app.SingBoxRouteRuleActionRoute
import engine.singbox.config.hasLegacyRouteModeMatcher
import app.SingBoxRouteRuleLogicalModeAnd
import app.SingBoxRouteRuleLogicalModeOr
import app.SingBoxRouteRuleState
import app.SingBoxRouteRuleTypeDefault
import app.SingBoxRouteRuleTypeLogical
import app.nextAvailableRouteRuleId
import engine.singbox.config.APP_GLOBAL_SELECTOR

internal fun AppState.withSavedRouteRule(
    rule: SingBoxRouteRuleState,
    isNew: Boolean,
): AppState {
    val normalized = rule.sanitized().let { savedRule ->
        if (isNew) {
            savedRule.copy(id = nextAvailableRouteRuleId())
        } else {
            savedRule
        }
    }
    val exists = !isNew && routeRules.any { current -> current.id == normalized.id }
    return copy(
        routeRules = if (exists) {
            routeRules.map { current ->
                if (current.id == normalized.id) normalized else current
            }
        } else {
            routeRules + normalized
        },
        nextRouteRuleId = maxOf(nextRouteRuleId, normalized.id + 1),
    )
}

internal fun SingBoxRouteRuleState.withSavedLogicalRule(
    child: SingBoxRouteRuleState,
): SingBoxRouteRuleState {
    val normalized = child.sanitized()
    val exists = logicalRules.any { current -> current.id == normalized.id }
    return copy(
        logicalRules = if (exists) {
            logicalRules.map { current ->
                if (current.id == normalized.id) normalized else current
            }
        } else {
            logicalRules + normalized
        },
    )
}

internal fun SingBoxRouteRuleState.sanitized(): SingBoxRouteRuleState {
    val sanitizedRejectMethod = rejectMethod.takeIf {
        it in setOf("default", "drop", "reply")
    } ?: "default"
    return copy(
        remarks = remarks.trim(),
        type = type.takeIf {
            it == SingBoxRouteRuleTypeDefault || it == SingBoxRouteRuleTypeLogical
        } ?: SingBoxRouteRuleTypeDefault,
        logicalMode = logicalMode.takeIf {
            it == SingBoxRouteRuleLogicalModeAnd || it == SingBoxRouteRuleLogicalModeOr
        } ?: SingBoxRouteRuleLogicalModeAnd,
        logicalRules = logicalRules.map(SingBoxRouteRuleState::sanitized),
        inbound = inbound.normalized(),
        processName = processName.normalized(),
        processPath = processPath.normalized(),
        processPathRegex = processPathRegex.normalized(),
        user = user.normalized(),
        userId = userId.normalized(),

        authUser = authUser.normalized(),
        client = client.normalized(),
        packageNameRegex = packageNameRegex.normalized(),
        networkInterfaceAddress = networkInterfaceAddress.normalized(),
        sourceMacAddress = sourceMacAddress.normalized(),
        sourceHostname = sourceHostname.normalized(),
        preferredBy = preferredBy.normalized(),

        enabled = enabled && !hasLegacyRouteModeMatcher(),
        clashMode = "",
        network = network.normalized(),
        protocol = protocol.normalized(),
        domain = domain.normalized(),
        domainSuffix = domainSuffix.normalized(),
        domainKeyword = domainKeyword.normalized(),
        domainRegex = domainRegex.normalized(),
        sourceIpCidr = sourceIpCidr.normalized(),
        ipCidr = ipCidr.normalized(),
        sourcePort = sourcePort.normalized(),
        sourcePortRange = sourcePortRange.normalized(),
        port = port.normalized(),
        portRange = portRange.normalized(),
        packageName = packageName.normalized(),
        networkType = networkType.normalized(),
        wifiSsid = wifiSsid.normalized(),
        wifiBssid = wifiBssid.normalized(),
        dnsServerAddress = dnsServerAddress.normalized(),
        dnsSearchDomain = dnsSearchDomain.normalized(),
        ruleSet = ruleSet.normalized(),
        action = action.takeIf {
            it == SingBoxRouteRuleActionRoute || it == SingBoxRouteRuleActionReject
        } ?: SingBoxRouteRuleActionRoute,
        outbound = outbound.trim().ifBlank { APP_GLOBAL_SELECTOR },
        rejectMethod = sanitizedRejectMethod,
        rejectNoDrop = rejectNoDrop && sanitizedRejectMethod != "drop",
    )
}

private fun List<String>.normalized(): List<String> =
    map(String::trim).filter(String::isNotEmpty).distinct()

internal fun SingBoxRouteRuleState.hasValidAdditionalRouteMatchers(preferredByTags: Set<String>): Boolean {
    if (type == SingBoxRouteRuleTypeLogical) {
        return logicalRules.all { it.hasValidAdditionalRouteMatchers(preferredByTags) }
    }
    if (preferredBy.any { it !in preferredByTags }) return false
    return listOf(
        "process_name" to processName,
        "process_path" to processPath,
        "process_path_regex" to processPathRegex,
        "user" to user,
        "user_id" to userId,
        "auth_user" to authUser,
        "client" to client,
        "package_name_regex" to packageNameRegex,
        "network_interface_address" to networkInterfaceAddress,
        "source_mac_address" to sourceMacAddress,
        "source_hostname" to sourceHostname,
    ).all { (field, values) ->
        values.all { singBoxRuleMatcherValueError(field, it, "invalid") == null }
    }
}
