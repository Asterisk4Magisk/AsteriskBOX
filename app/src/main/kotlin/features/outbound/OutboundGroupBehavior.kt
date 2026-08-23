// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.outbound

import app.AppState
import app.OutboundGroupState
import app.managedOutboundGroupSelectorTag
import app.nextAvailableOutboundGroupId
import app.withRemovedManagedOutboundTags
import app.withReplacedManagedTag

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
    val previousSelectorTag = previous?.let { group ->
        managedOutboundGroupSelectorTag(group.id, group.name)
    }
    val savedSelectorTag = managedOutboundGroupSelectorTag(savedGroup.id, savedGroup.name)
    val referenceUpdated = if (
        previousSelectorTag != null && previousSelectorTag != savedSelectorTag
    ) {
        updated.withReplacedManagedTag(previousSelectorTag, savedSelectorTag)
    } else updated
    if (previous?.enabled != true || savedGroup.enabled) return referenceUpdated

    val removedTags = outbounds
        .filter { outbound -> outbound.groupId == savedGroup.id }
        .mapTo(mutableSetOf()) { outbound -> outbound.tag }
    removedTags += managedOutboundGroupSelectorTag(savedGroup.id, savedGroup.name)
    removedTags += managedOutboundGroupSelectorTag(previous.id, previous.name)
    return referenceUpdated.withRemovedManagedOutboundTags(removedTags)
}
