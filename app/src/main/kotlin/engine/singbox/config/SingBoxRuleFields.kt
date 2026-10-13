// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package engine.singbox.config

import app.SingBoxDnsRuleLogicalModeAnd
import app.SingBoxDnsRuleState
import app.SingBoxDnsRuleTypeLogical
import app.SingBoxRouteRuleActionReject
import app.SingBoxRouteRuleActionRoute
import app.SingBoxRouteRuleLogicalModeAnd
import app.SingBoxRouteRuleState
import app.SingBoxRouteRuleTypeLogical

// Use at editor transitions and save boundaries so hidden values cannot return or block validation.
internal fun SingBoxDnsRuleState.withoutInactiveFields(): SingBoxDnsRuleState {
    val matchRule = if (type == SingBoxDnsRuleTypeLogical) {
        copy(matches = emptyList(), ipVersion = "", network = emptyList())
    } else {
        copy(logicalMode = SingBoxDnsRuleLogicalModeAnd, logicalRules = emptyList())
    }
    val routeOptions = action in setOf("route", "evaluate", "route-options")
    val reject = action == "reject"
    val predefined = action == "predefined"
    return matchRule.copy(
        server = server.takeIf { action == "route" || action == "evaluate" }.orEmpty(),
        disableCache = routeOptions && disableCache,
        rewriteTtl = rewriteTtl.takeIf { routeOptions }.orEmpty(),
        timeout = timeout.takeIf { routeOptions }.orEmpty(),
        clientSubnet = clientSubnet.takeIf { routeOptions }.orEmpty(),
        rejectMethod = if (reject) rejectMethod else "default",
        noDrop = reject && rejectMethod != "drop" && noDrop,
        rcode = rcode.takeIf { predefined }.orEmpty(),
        answer = if (predefined) answer else emptyList(),
        ns = if (predefined) ns else emptyList(),
        extra = if (predefined) extra else emptyList(),
    )
}

internal fun SingBoxRouteRuleState.withoutInactiveFields(): SingBoxRouteRuleState {
    val matchRule = if (type == SingBoxRouteRuleTypeLogical) {
        // Starting from defaults also clears every ordinary matcher when new fields are added.
        SingBoxRouteRuleState(
            id = id,
            remarks = remarks,
            enabled = enabled,
            type = type,
            logicalMode = logicalMode,
            logicalRules = logicalRules,
            invert = invert,
            action = action,
            outbound = outbound,
            rejectMethod = rejectMethod,
            rejectNoDrop = rejectNoDrop,
        )
    } else {
        copy(logicalMode = SingBoxRouteRuleLogicalModeAnd, logicalRules = emptyList())
    }
    return matchRule.copy(
        outbound = outbound.takeIf { action == SingBoxRouteRuleActionRoute }.orEmpty(),
        rejectMethod = if (action == SingBoxRouteRuleActionReject) rejectMethod else "default",
        rejectNoDrop = action == SingBoxRouteRuleActionReject && rejectMethod != "drop" && rejectNoDrop,
    )
}
