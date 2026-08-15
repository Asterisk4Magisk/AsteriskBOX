// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package app

internal fun AppState.selectorGroupMembersByReference(): Map<String, List<String>> = buildMap {
    outboundGroups
        .filter(OutboundGroupState::enabled)
        .forEach { group ->
            put(
                managedOutboundGroupSelectorTag(group.id, group.name),
                outbounds
                    .filter { outbound -> outbound.groupId == group.id }
                    .map(OutboundState::tag)
                    .distinct(),
            )
        }
}

internal fun AppState.expandSelectorMemberReferences(
    references: Iterable<String>,
    availableMemberTags: Iterable<String>,
): List<String> {
    val requested = references
        .map(String::trim)
        .filterTo(mutableSetOf(), String::isNotEmpty)
    val availableMembers = availableMemberTags
        .map(String::trim)
        .filter(String::isNotEmpty)
        .distinct()
    val available = availableMembers.toSet()
    val groups = selectorGroupMembersByReference()
    val groupMembers = groups
        .asSequence()
        .filter { (reference, _) -> reference in requested }
        .flatMap { (_, members) -> members.asSequence() }
    val independentMembers = availableMembers.asSequence()
        .filter { member -> member in requested && member !in groups }
    return (groupMembers + independentMembers)
        .filter(available::contains)
        .distinct()
        .toList()
}

internal fun AppState.selectorGroupLockedOutboundTags(
    references: Iterable<String>,
): Set<String> {
    val requested = references
        .map(String::trim)
        .filterTo(mutableSetOf(), String::isNotEmpty)
    return selectorGroupMembersByReference()
        .asSequence()
        .filter { (reference, _) -> reference in requested }
        .flatMap { (_, members) -> members.asSequence() }
        .toSet()
}
