// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.dns

import app.AppState
import app.SingBoxDnsRuleMatchState
import app.SingBoxDnsRuleState
import app.SingBoxDnsRuleTypeLogical
import app.nextAvailableDnsRuleId
import app.visibleManagedReference
import app.withPrunedDnsEvaluationReferences
import engine.singbox.config.sanitized

internal val DnsRuleMatcherGroups = listOf(
    listOf(
        "query_type",
        "inbound",
        "auth_user",
        "protocol",
        "network_type",
    ),
    listOf(
        "domain",
        "domain_suffix",
        "domain_keyword",
        "domain_regex",
        "rule_set",
        "port",
        "port_range",
    ),
    listOf(
        "source_ip_cidr",
        "source_port",
        "source_port_range",
        "source_mac_address",
        "source_hostname",
    ),
    listOf(
        "process_name",
        "process_path",
        "process_path_regex",
        "package_name",
        "package_name_regex",
    ),
    listOf(
        "interface_address",
        "network_interface_address",
        "default_interface_address",
        "preferred_by",
        "wifi_ssid",
        "wifi_bssid",
    ),
    listOf(
        "match_response",
        "response_rcode",
        "response_answer",
        "response_ns",
        "response_extra",
    ),
)

internal fun List<SingBoxDnsRuleState>.moveDnsRule(
    fromIndex: Int,
    toIndex: Int,
): List<SingBoxDnsRuleState> {
    if (fromIndex !in indices || toIndex !in indices || fromIndex == toIndex) return this
    return toMutableList().apply {
        add(toIndex, removeAt(fromIndex))
    }
}

internal fun AppState.withDnsRuleEnabled(
    ruleId: Int,
    enabled: Boolean,
): AppState = copy(
    dnsRules = dnsRules.map { rule ->
        if (rule.id == ruleId) rule.copy(enabled = enabled) else rule
    },
)

internal fun AppState.withSavedDnsRule(
    rule: SingBoxDnsRuleState,
    isNew: Boolean,
): AppState {
    val savedRule = if (isNew) {
        rule.copy(id = nextAvailableDnsRuleId())
    } else {
        rule
    }
    val exists = !isNew && dnsRules.any { current -> current.id == savedRule.id }
    return copy(
        dnsRules = if (exists) {
            dnsRules.map { current ->
                if (current.id == savedRule.id) savedRule else current
            }
        } else {
            dnsRules + savedRule
        },
        nextDnsRuleId = maxOf(nextDnsRuleId, savedRule.id + 1),
    ).withPrunedDnsEvaluationReferences()
}

internal fun SingBoxDnsRuleState.nextLogicalDnsRuleId(): Int {
    val usedIds = logicalRules.mapTo(mutableSetOf()) { rule -> rule.id }
    var candidate = 1
    while (candidate in usedIds) candidate += 1
    return candidate
}

internal fun dnsPendingMatchersBlockSave(
    ruleType: String,
    hasPendingMatchers: Boolean,
): Boolean = ruleType != SingBoxDnsRuleTypeLogical && hasPendingMatchers

internal fun SingBoxDnsRuleState.dnsMatcherCount(): Int =
    matches.count { match -> match.values.isNotEmpty() } +
        ipVersion.countAsDnsMatcher() +
        network.countAsDnsMatcher()

private fun String.countAsDnsMatcher(): Int = if (isBlank()) 0 else 1

internal fun SingBoxDnsRuleState.withSavedLogicalRule(
    child: SingBoxDnsRuleState,
): SingBoxDnsRuleState {
    val normalized = child.sanitized()
    val exists = logicalRules.any { rule -> rule.id == normalized.id }
    return copy(
        logicalRules = if (exists) {
            logicalRules.map { rule ->
                if (rule.id == normalized.id) normalized else rule
            }
        } else {
            logicalRules + normalized
        },
    )
}

internal fun SingBoxDnsRuleState.withDnsRuleMatchValues(
    field: String,
    values: List<String>,
    encodeAsString: Boolean = false,
): SingBoxDnsRuleState {
    val normalizedValues = values
        .map(String::trim)
        .filter(String::isNotEmpty)
        .distinct()
    val valuesByField = matches
        .filterNot { match -> match.field == field }
        .associateBy(SingBoxDnsRuleMatchState::field)
        .toMutableMap()
    if (normalizedValues.isNotEmpty()) {
        valuesByField[field] = SingBoxDnsRuleMatchState(
            field = field,
            values = normalizedValues,
            encodeAsString = encodeAsString,
        )
    }
    return copy(
        matches = DnsRuleMatcherGroups
            .asSequence()
            .flatten()
            .mapNotNull(valuesByField::get)
            .toList(),
    )
}

internal fun SingBoxDnsRuleState.withVisibleManagedReferences(
    labels: Map<String, String>,
    unavailableLabel: String,
): SingBoxDnsRuleState = copy(
    logicalRules = logicalRules.map { rule ->
        rule.withVisibleManagedReferences(labels, unavailableLabel)
    },
    matches = matches.map { match ->
        if (match.field in ManagedDnsReferenceFields) {
            match.copy(
                values = match.values.map { value ->
                    visibleManagedReference(value, labels, unavailableLabel)
                },
            )
        } else {
            match
        }
    },
    server = if (action == "route" || action == "evaluate") {
        visibleManagedReference(server, labels, unavailableLabel)
    } else {
        server
    },
)

internal data class DnsRuleCardPresentation(
    val type: String,
    val logicalMode: String,
    val logicalChildCount: Int,
    val action: String,
    val target: String?,
    val matchRules: List<SingBoxDnsRuleState>,
)

internal fun SingBoxDnsRuleState.toDnsRuleCardPresentation(): DnsRuleCardPresentation {
    val ruleWithoutTarget = copy(server = "")
    return DnsRuleCardPresentation(
        type = type,
        logicalMode = logicalMode,
        logicalChildCount = logicalRules.size,
        action = action,
        target = server.takeIf {
            (action == "route" || action == "evaluate") && server.isNotBlank()
        },
        matchRules = when {
            type == SingBoxDnsRuleTypeLogical -> emptyList()
            matches.isEmpty() -> listOf(ruleWithoutTarget)
            else -> matches.map { match -> ruleWithoutTarget.copy(matches = listOf(match)) }
        },
    )
}

internal data class DnsMatchResponseChoice(
    val value: String,
    val remarks: String,
)

internal fun selectableDnsMatchResponseValues(
    rules: List<SingBoxDnsRuleState>,
    currentIndex: Int?,
): List<DnsMatchResponseChoice> {
    val precedingRules = rules.take((currentIndex ?: rules.size).coerceIn(0, rules.size))
    return precedingRules
        .asSequence()
        .filter { rule -> rule.enabled && rule.action == "evaluate" }
        .map { rule ->
            DnsMatchResponseChoice(
                value = rule.evaluationTag,
                remarks = rule.remarks,
            )
        }
        .distinctBy(DnsMatchResponseChoice::value)
        .toList()
}

internal suspend fun validateAndCommitDnsRuleState(
    baseState: AppState,
    candidateState: AppState,
    validate: suspend (AppState) -> Unit,
    commit: (AppState, AppState) -> Boolean,
): Boolean {
    validate(candidateState)
    return commit(baseState, candidateState)
}

private val ManagedDnsReferenceFields =
    setOf("inbound", "rule_set", "preferred_by", "match_response")
