// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.outbound

import app.OutboundState
import app.modes.OutboundListLayoutAuto
import app.modes.OutboundListLayoutDouble
import app.modes.OutboundListLayoutMultiple
import app.modes.OutboundListLayoutSingle
import app.modes.OutboundListSortLatency
import app.modes.OutboundListSortName
import app.modes.OutboundListSortType
import engine.singbox.config.SingBoxJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

internal const val OutboundListBaseBottomExtraDp = 12
internal const val OutboundGridSpacingDp = 10
internal const val OutboundCardHeightDp = 112

internal data class OutboundCardMenuIconOffsetDp(
    val x: Int,
    val y: Int,
)

internal fun outboundCardMenuIconOffsetDp(
    touchTargetDp: Int,
    iconSizeDp: Int,
): OutboundCardMenuIconOffsetDp {
    require(touchTargetDp >= iconSizeDp)
    val centeredInsetDp = (touchTargetDp - iconSizeDp) / 2
    return OutboundCardMenuIconOffsetDp(
        x = centeredInsetDp,
        y = -centeredInsetDp,
    )
}

internal fun resolveOutboundListColumns(
    layout: Int,
    isWideScreen: Boolean,
): Int = when (layout) {
    OutboundListLayoutSingle -> 1
    OutboundListLayoutDouble -> 2
    OutboundListLayoutMultiple -> 3
    OutboundListLayoutAuto -> if (isWideScreen) 3 else 2
    else -> if (isWideScreen) 3 else 2
}

internal fun outboundListBottomExtraDp(): Int {
    return OutboundListBaseBottomExtraDp + OutboundCardHeightDp + OutboundGridSpacingDp
}

internal fun List<OutboundState>.sortedForOutboundList(sort: Int): List<OutboundState> {
    return when (sort) {
        OutboundListSortName -> sortedWith(
            compareBy(String.CASE_INSENSITIVE_ORDER, OutboundState::remarks),
        )
        OutboundListSortLatency -> sortedWith(
            compareBy<OutboundState> { outbound -> outbound.pingMillis.toOutboundPingSortKey() }
                .thenBy(String.CASE_INSENSITIVE_ORDER, OutboundState::remarks),
        )
        OutboundListSortType -> sortedWith(
            compareBy(String.CASE_INSENSITIVE_ORDER, OutboundState::type)
                .thenBy(String.CASE_INSENSITIVE_ORDER, OutboundState::remarks),
        )
        else -> this
    }
}

internal fun List<OutboundState>.reorderVisibleOutbounds(
    visibleOutbounds: List<OutboundState>,
    fromIndex: Int,
    toIndex: Int,
): List<OutboundState> {
    val fromId = visibleOutbounds.getOrNull(fromIndex)?.id ?: return this
    val toId = visibleOutbounds.getOrNull(toIndex)?.id ?: return this
    val sourceIndex = indexOfFirst { outbound -> outbound.id == fromId }
    val destinationIndex = indexOfFirst { outbound -> outbound.id == toId }
    if (sourceIndex !in indices || destinationIndex !in indices || sourceIndex == destinationIndex) {
        return this
    }
    return toMutableList().apply {
        add(destinationIndex, removeAt(sourceIndex))
    }
}

internal fun OutboundState.cardEndpointSummary(compact: Boolean): String? {
    return if (compact) null else endpointSummary()
}

internal fun OutboundState.endpointSummary(): String? {
    val outbound = runCatching { SingBoxJson.parseToJsonElement(json) as? JsonObject }.getOrNull()
        ?: return null
    val server = (outbound["server"] as? JsonPrimitive)?.contentOrNull ?: return null
    val port = (outbound["server_port"] as? JsonPrimitive)?.contentOrNull
    if (port.isNullOrBlank()) return server
    val displayServer = if (':' in server && !server.startsWith('[')) "[$server]" else server
    return "$displayServer:$port"
}

private fun Long?.toOutboundPingSortKey(): Long {
    return when {
        this == null -> Long.MAX_VALUE
        this < 0L -> Long.MAX_VALUE - 1L
        else -> this
    }
}
