// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.settings

import app.SingBoxDnsRuleState
import app.updateManagedMatchReferences

internal fun Map<String, String>.withDnsServerTagReplacement(
    previousTag: String,
    replacementTag: String,
): Map<String, String> {
    val previous = previousTag.trim()
    if (previous.isEmpty()) return this
    val replacement = replacementTag.trim()
    return buildMap {
        this@withDnsServerTagReplacement.forEach { (source, target) ->
            val resolvedTarget = if (target == previous) replacement else target
            if (source != resolvedTarget) put(source, resolvedTarget)
        }
        if (previous != replacement) put(previous, replacement)
    }
}

internal fun List<SingBoxDnsRuleState>.replaceDnsServerTagReferences(
    replacements: Map<String, String>,
): List<SingBoxDnsRuleState> = mapNotNull { rule ->
    val server = rule.server.trim()
    val replacement = replacements[server]
    val updatedRule = when {
        rule.action !in DnsActionsWithServer || !replacements.containsKey(server) -> rule
        replacement.isNullOrBlank() -> null
        else -> rule.copy(server = replacement)
    }
    updatedRule
}

internal fun List<SingBoxDnsRuleState>.replaceDnsPreferredByTagReferences(
    replacements: Map<String, String>,
): List<SingBoxDnsRuleState> = map { rule ->
    rule.updateManagedMatchReferences("preferred_by") { tag ->
        if (replacements.containsKey(tag)) {
            replacements[tag]?.takeIf(String::isNotBlank)
        } else {
            tag
        }
    }
}

private val DnsActionsWithServer = setOf("route", "evaluate")
