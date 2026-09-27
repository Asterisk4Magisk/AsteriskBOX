// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package app

import engine.singbox.config.DnsConfigurationMatchFields
import engine.singbox.config.dnsConfigurationEntryTag
import engine.singbox.config.isDnsConfigurationEntry

internal fun SingBoxRouteRuleState.hasValidDnsConfigurationMatchers(tags: Set<String>): Boolean =
    if (type == SingBoxRouteRuleTypeLogical) {
        logicalRules.all { it.hasValidDnsConfigurationMatchers(tags) }
    } else {
        validDnsConfigurationEntries("dns_server_address", dnsServerAddress, tags) &&
            validDnsConfigurationEntries("dns_search_domain", dnsSearchDomain, tags)
    }

internal fun SingBoxDnsRuleState.hasValidDnsConfigurationMatchers(tags: Set<String>): Boolean =
    if (type == SingBoxDnsRuleTypeLogical) {
        logicalRules.all { it.hasValidDnsConfigurationMatchers(tags) }
    } else {
        matches.filter { it.field in DnsConfigurationMatchFields }.all {
            validDnsConfigurationEntries(it.field, it.values, tags)
        }
    }

private fun validDnsConfigurationEntries(field: String, entries: List<String>, tags: Set<String>): Boolean =
    entries.all { dnsConfigurationEntryTag(it) in tags && isDnsConfigurationEntry(field, it) }

internal fun SingBoxRouteRuleState.disableUnavailableDnsConfigurationReferences(
    availableTags: Set<String>,
): SingBoxRouteRuleState {
    val children = logicalRules.map { it.disableUnavailableDnsConfigurationReferences(availableTags) }
    return copy(enabled = enabled && !hasUnavailableDnsConfigurationReferences(availableTags), logicalRules = children)
}

internal fun SingBoxDnsRuleState.disableUnavailableDnsConfigurationReferences(
    availableTags: Set<String>,
): SingBoxDnsRuleState {
    val children = logicalRules.map { it.disableUnavailableDnsConfigurationReferences(availableTags) }
    return copy(enabled = enabled && !hasUnavailableDnsConfigurationReferences(availableTags), logicalRules = children)
}

// Keep this check independent of enabled state: re-enabling a parent must not
// omit a child that was disabled by an earlier reference-pruning pass.
private fun SingBoxRouteRuleState.hasUnavailableDnsConfigurationReferences(tags: Set<String>): Boolean =
    if (type == SingBoxRouteRuleTypeLogical) {
        logicalRules.any { it.hasUnavailableDnsConfigurationReferences(tags) }
    } else {
        (dnsServerAddress + dnsSearchDomain).any { dnsConfigurationEntryTag(it) !in tags }
    }

private fun SingBoxDnsRuleState.hasUnavailableDnsConfigurationReferences(tags: Set<String>): Boolean =
    if (type == SingBoxDnsRuleTypeLogical) {
        logicalRules.any { it.hasUnavailableDnsConfigurationReferences(tags) }
    } else {
        matches.filter { it.field in DnsConfigurationMatchFields }.any { match ->
            match.values.any { dnsConfigurationEntryTag(it) !in tags }
        }
    }
