// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.dns

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import app.SingBoxDnsRuleLogicalModeOr
import app.SingBoxDnsRuleState
import app.SingBoxDnsRuleTypeLogical
import engine.singbox.config.hasValidDnsRuleStructure
import app.R
import features.routing.LogicalRuleConditionsCard

@Composable
internal fun DnsLogicalChildrenCard(
    rules: List<SingBoxDnsRuleState>,
    onAdd: () -> Unit,
    onEdit: (SingBoxDnsRuleState) -> Unit,
    onEnabledChange: (SingBoxDnsRuleState, Boolean) -> Unit,
    onDelete: (SingBoxDnsRuleState) -> Unit,
) {
    LogicalRuleConditionsCard(
        rules = rules,
        stableId = SingBoxDnsRuleState::id,
        animationLabel = "dns-logical-conditions",
        isEnabled = SingBoxDnsRuleState::enabled,
        isLogical = { rule -> rule.type == SingBoxDnsRuleTypeLogical },
        remarks = SingBoxDnsRuleState::remarks,
        summary = { rule -> dnsRuleMatchSummary(rule) },
        onAdd = onAdd,
        onEdit = onEdit,
        onEnabledChange = onEnabledChange,
        onDelete = onDelete,
        structureError = { visibleRules ->
            when {
                visibleRules.none(SingBoxDnsRuleState::enabled) ->
                    R.string.settings_dns_logical_enabled_condition_required
                visibleRules.any { child ->
                    !child.hasValidDnsRuleStructure(nested = true)
                } -> R.string.settings_dns_logical_invalid_condition
                else -> null
            }
        },
    )
}

@Composable
internal fun dnsRuleMatchSummary(rule: SingBoxDnsRuleState): String {
    if (rule.type == SingBoxDnsRuleTypeLogical) {
        val modeLabel = stringResource(
            if (rule.logicalMode == SingBoxDnsRuleLogicalModeOr) {
                R.string.routing_logical_mode_or_short
            } else {
                R.string.routing_logical_mode_and_short
            },
        )
        val conditionCount = pluralStringResource(
            R.plurals.routing_condition_count,
            rule.logicalRules.size,
            rule.logicalRules.size,
        )
        return stringResource(R.string.routing_logical_summary, modeLabel, conditionCount)
    }
    val matcherCount = rule.dnsMatcherCount()
    return if (matcherCount == 0) {
        stringResource(R.string.settings_dns_rule_match_all)
    } else {
        pluralStringResource(R.plurals.routing_match_count, matcherCount, matcherCount)
    }
}
