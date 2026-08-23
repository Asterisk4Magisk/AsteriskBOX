// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.outbound

import app.OutboundState
import app.modes.OutboundListSortLatency
import app.modes.OutboundListSortName
import app.modes.OutboundListSortType
import features.singbox.displaySingBoxProtocolName

internal data class OutboundListItem(
    val outbound: OutboundState,
    val pingHost: String?,
    val endpointSummary: String?,
    val searchText: String,
) {
    val id: Int get() = outbound.id
}

internal class OutboundListProjectionCache(
    private val parse: (OutboundState) -> OutboundParsedMetadata = ::parseOutboundListMetadata,
) {
    private val metadataByIdentity = mutableMapOf<OutboundPingIdentity, OutboundParsedMetadata>()

    fun build(outbounds: List<OutboundState>): OutboundListIndex {
        val identities = outbounds.mapTo(mutableSetOf()) { outbound ->
            OutboundPingIdentity(outbound.id, outbound.json)
        }
        metadataByIdentity.keys.retainAll(identities)
        val items = outbounds.map { outbound ->
            val identity = OutboundPingIdentity(outbound.id, outbound.json)
            val metadata = metadataByIdentity.getOrPut(identity) { parse(outbound) }
            OutboundListItem(
                outbound = outbound,
                pingHost = metadata.pingHost,
                endpointSummary = metadata.endpointSummary,
                searchText = listOfNotNull(
                    outbound.remarks,
                    outbound.type,
                    outbound.type.displaySingBoxProtocolName(),
                    metadata.endpointSummary,
                    metadata.pingHost,
                ).joinToString(" ").lowercase(),
            )
        }
        return OutboundListIndex(
            byId = items.associateBy(OutboundListItem::id),
            byGroup = items.groupBy { item -> item.outbound.groupId },
        )
    }
}

internal class OutboundListIndex internal constructor(
    private val byId: Map<Int, OutboundListItem>,
    private val byGroup: Map<Int, List<OutboundListItem>>,
) {
    fun item(id: Int): OutboundListItem? = byId[id]

    fun count(groupId: Int): Int = byGroup[groupId].orEmpty().size

    fun visible(
        groupId: Int,
        query: String,
        sort: Int,
        pingState: OutboundPingRuntimeState,
    ): List<OutboundListItem> {
        val visible = byGroup[groupId].orEmpty().filter { item ->
            query.isBlank() || item.searchText.contains(query.lowercase())
        }
        return when (sort) {
            OutboundListSortName -> visible.sortedWith(
                compareBy(String.CASE_INSENSITIVE_ORDER, OutboundListItem::remarks),
            )

            OutboundListSortLatency -> visible.sortedWith(
                compareBy<OutboundListItem> { item -> item.pingLatencyMillis(pingState).toOutboundPingSortKey() }
                    .thenBy(String.CASE_INSENSITIVE_ORDER, OutboundListItem::remarks),
            )

            OutboundListSortType -> visible.sortedWith(
                compareBy(String.CASE_INSENSITIVE_ORDER, OutboundListItem::type)
                    .thenBy(String.CASE_INSENSITIVE_ORDER, OutboundListItem::remarks),
            )

            else -> visible
        }
    }
}

internal fun OutboundListItem.pingLatencyMillis(pingState: OutboundPingRuntimeState): Long? {
    val entry = pingState.entries[id] ?: return null
    if (entry.identity != OutboundPingIdentity(id, outbound.json)) return null
    return entry.latencyMillis.takeIf { entry.status != OutboundPingStatus.Testing }
}

private val OutboundListItem.remarks: String
    get() = outbound.remarks

private val OutboundListItem.type: String
    get() = outbound.type
