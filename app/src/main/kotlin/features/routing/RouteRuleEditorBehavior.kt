// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.routing

import app.AppState
import app.SingBoxRouteRuleActionReject
import app.SingBoxRouteRuleActionRoute
import app.SingBoxRouteRuleClashModes
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
        clashMode = clashMode.takeIf(SingBoxRouteRuleClashModes::contains).orEmpty(),
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
