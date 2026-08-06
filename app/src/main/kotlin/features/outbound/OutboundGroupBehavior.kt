// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.outbound

import app.AppState
import app.OutboundGroupState
import app.managedOutboundGroupSelectorTag
import app.nextAvailableOutboundGroupId
import app.withCanonicalManagedTagReferences
import app.withRemovedManagedOutboundTags

internal fun AppState.withSavedOutboundGroup(group: OutboundGroupState): AppState {
    val previous = outboundGroups.firstOrNull { item -> item.id == group.id }
    val savedGroup: OutboundGroupState
    val updated = if (previous != null) {
        savedGroup = group
        copy(
            outboundGroups = outboundGroups.map { item ->
                if (item.id == group.id) group else item
            },
        )
    } else {
        val id = nextAvailableOutboundGroupId()
        savedGroup = group.copy(id = id)
        copy(
            outboundGroups = outboundGroups + savedGroup,
            nextOutboundGroupId = id + 1,
        )
    }
    val canonicalUpdated = updated.withCanonicalManagedTagReferences()
    if (previous?.enabled != true || savedGroup.enabled) return canonicalUpdated

    val removedTags = outbounds
        .filter { outbound -> outbound.groupId == savedGroup.id }
        .mapTo(mutableSetOf()) { outbound -> outbound.tag }
    removedTags += managedOutboundGroupSelectorTag(savedGroup.id, savedGroup.name)
    removedTags += managedOutboundGroupSelectorTag(previous.id, previous.name)
    return canonicalUpdated.withRemovedManagedOutboundTags(removedTags)
}

internal fun List<OutboundGroupState>.moveOutboundGroup(
    fromIndex: Int,
    toIndex: Int,
): List<OutboundGroupState> {
    if (fromIndex !in indices || toIndex !in indices || fromIndex == toIndex) return this
    return toMutableList().apply {
        add(toIndex, removeAt(fromIndex))
    }
}
