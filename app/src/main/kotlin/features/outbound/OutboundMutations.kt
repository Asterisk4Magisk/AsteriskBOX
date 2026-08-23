// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.outbound

import app.AppState
import app.OutboundState
import app.managedOutboundTag
import app.withReplacedManagedTag
import engine.singbox.config.SingBoxJson
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal data class OutboundDraft(
    val groupId: Int,
    val remarks: String,
    val type: String,
    val json: String,
)

internal sealed interface OutboundStateMutation {
    data class Applied(
        val state: AppState,
        val outbound: OutboundState,
    ) : OutboundStateMutation

    data object Conflict : OutboundStateMutation
}

internal fun AppState.withSavedOutbound(
    expected: OutboundState?,
    draft: OutboundDraft,
): OutboundStateMutation {
    if (outboundGroups.none { group -> group.id == draft.groupId }) {
        return OutboundStateMutation.Conflict
    }
    val current = expected?.let { previous ->
        outbounds.firstOrNull { outbound -> outbound.id == previous.id }
            ?.takeIf { outbound -> outbound == previous }
            ?: return OutboundStateMutation.Conflict
    }
    val id = current?.id ?: nextAvailableOutboundId()
    val remarks = draft.remarks.trim()
    val tag = managedOutboundTag(id, remarks)
    val json = draft.json.jsonObjectWithTag(tag) ?: return OutboundStateMutation.Conflict
    val saved = OutboundState(
        id = id,
        groupId = draft.groupId,
        remarks = remarks,
        type = draft.type,
        json = json,
    )
    val updatedOutbounds = when {
        current == null -> outbounds.insertAtGroupSegmentEnd(
            outbound = saved,
            groupOrder = outboundGroups.map { group -> group.id },
        )
        current.groupId == draft.groupId -> outbounds.map { outbound ->
            if (outbound.id == current.id) saved else outbound
        }
        else -> outbounds
            .filterNot { outbound -> outbound.id == current.id }
            .insertAtGroupSegmentEnd(
                outbound = saved,
                groupOrder = outboundGroups.map { group -> group.id },
            )
    }
    val savedState = copy(
        outbounds = updatedOutbounds,
        nextOutboundId = if (current == null) id + 1 else nextOutboundId,
    )
    val next = current
        ?.takeIf { outbound -> outbound.tag != tag }
        ?.let { outbound -> savedState.withReplacedManagedTag(outbound.tag, tag) }
        ?: savedState
    val finalOutbound = next.outbounds.first { outbound -> outbound.id == id }
    return OutboundStateMutation.Applied(next, finalOutbound)
}

internal fun AppState.withReorderedOutboundIds(
    groupId: Int,
    orderedIds: List<Int>,
): AppState {
    if (outboundGroups.none { group -> group.id == groupId }) return this
    val groupOutbounds = outbounds.filter { outbound -> outbound.groupId == groupId }
    val groupIds = groupOutbounds.mapTo(mutableSetOf(), OutboundState::id)
    if (
        orderedIds.size != groupOutbounds.size ||
            orderedIds.toSet().size != orderedIds.size ||
            orderedIds.toSet() != groupIds
    ) {
        return this
    }
    val reordered = groupOutbounds.associateBy(OutboundState::id)
    val replacement = orderedIds.iterator()
    val nextOutbounds = outbounds.map { outbound ->
        if (outbound.groupId == groupId) reordered.getValue(replacement.next()) else outbound
    }
    return if (nextOutbounds == outbounds) this else copy(outbounds = nextOutbounds)
}

private fun AppState.nextAvailableOutboundId(): Int {
    val usedIds = outbounds.mapTo(mutableSetOf(), OutboundState::id)
    var candidate = nextOutboundId.coerceAtLeast(1)
    while (candidate in usedIds) candidate += 1
    return candidate
}

private fun List<OutboundState>.insertAtGroupSegmentEnd(
    outbound: OutboundState,
    groupOrder: List<Int>,
): List<OutboundState> {
    val groupIndex = groupOrder.indexOf(outbound.groupId)
    check(groupIndex >= 0)
    val laterGroupIds = groupOrder.drop(groupIndex + 1).toSet()
    val insertionIndex = indexOfFirst { item -> item.groupId in laterGroupIds }
        .takeIf { index -> index >= 0 }
        ?: size
    return toMutableList().apply { add(insertionIndex, outbound) }
}

private fun String.jsonObjectWithTag(tag: String): String? {
    val root = runCatching {
        SingBoxJson.parseToJsonElement(this) as? JsonObject
    }.getOrNull() ?: return null
    return SingBoxJson.encodeToString(
        JsonElement.serializer(),
        JsonObject(root + ("tag" to JsonPrimitive(tag))),
    )
}
