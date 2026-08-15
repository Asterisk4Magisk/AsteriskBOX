// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.selector

import app.AppState
import app.ManagedOutboundChoice
import app.ManagedOutboundChoiceKind
import app.expandSelectorMemberReferences
import app.selectorGroupLockedOutboundTags
import app.selectorGroupMembersByReference

internal fun selectorEffectiveMemberTags(
    state: AppState,
    memberReferences: List<String>,
    targets: List<ManagedOutboundChoice>,
): List<String> = state.expandSelectorMemberReferences(
    references = memberReferences,
    availableMemberTags = targets
        .filterNot { target -> target.kind == ManagedOutboundChoiceKind.Group }
        .map(ManagedOutboundChoice::tag),
)

internal fun selectorInteractiveTargetTags(
    state: AppState,
    memberReferences: List<String>,
    targetTags: List<String>,
): List<String> {
    val locked = state.selectorGroupLockedOutboundTags(memberReferences)
    return targetTags.filterNot(locked::contains)
}

internal fun updateSelectorMemberReferencesForMatches(
    state: AppState,
    memberReferences: List<String>,
    matchedTags: List<String>,
    select: Boolean,
): List<String> {
    val references = memberReferences
        .map(String::trim)
        .filter(String::isNotEmpty)
        .distinct()
    val matches = matchedTags
        .map(String::trim)
        .filter(String::isNotEmpty)
        .distinct()
    if (!select) {
        val locked = state.selectorGroupLockedOutboundTags(references)
        val removable = matches.filterNot(locked::contains).toSet()
        return references.filterNot(removable::contains)
    }
    val groupReferences = state.selectorGroupMembersByReference().keys
    val selectedGroups = matches.filter(groupReferences::contains)
    val updated = (references + selectedGroups).distinct()
    val locked = state.selectorGroupLockedOutboundTags(updated)
    val selectableMatches = matches.filter { target ->
        target !in groupReferences && target !in locked
    }
    return (updated + selectableMatches).distinct()
}

internal val ManagedOutboundChoice.selectorTargetPriority: Int
    get() = when (kind) {
        ManagedOutboundChoiceKind.Group -> 0
        ManagedOutboundChoiceKind.Selector -> 1
        ManagedOutboundChoiceKind.UrlTest -> 2
        ManagedOutboundChoiceKind.Direct -> 3
        else -> 4
    }
