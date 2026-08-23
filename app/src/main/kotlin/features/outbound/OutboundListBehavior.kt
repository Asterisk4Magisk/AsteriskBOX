// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.outbound

import app.OutboundState
import app.modes.OutboundListLayoutAuto
import app.modes.OutboundListLayoutDouble
import app.modes.OutboundListLayoutMultiple
import app.modes.OutboundListLayoutSingle
import engine.singbox.config.SingBoxJson
import features.importing.ImportOutcome
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

internal const val OutboundListBaseBottomExtraDp = 12
internal const val OutboundGridSpacingDp = 10
internal const val OutboundCardHeightDp = 112

internal class ReorderPreviewOwnership<K> {
    private var nextGeneration = 0L
    private val generations = mutableMapOf<K, Long>()

    fun claim(key: K): Long {
        nextGeneration += 1L
        return nextGeneration.also { generation -> generations[key] = generation }
    }

    fun releaseIfOwned(key: K, generation: Long): Boolean {
        if (generations[key] != generation) return false
        generations.remove(key)
        return true
    }
}

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

internal fun parseOutboundImportContent(
    content: String,
    jsonFormatter: SingBoxOutboundConfigFormatter = LibboxSingBoxOutboundConfigFormatter,
): ImportOutcome<ImportedSingBoxOutbound> {
    return OutboundImportPipeline.parseOutcome(
        content = content,
        jsonFormatter = jsonFormatter,
    )
}

internal data class OutboundParsedMetadata(
    val pingHost: String?,
    val endpointSummary: String?,
)

internal fun parseOutboundListMetadata(outbound: OutboundState): OutboundParsedMetadata {
    val json = runCatching { SingBoxJson.parseToJsonElement(outbound.json) as? JsonObject }.getOrNull()
        ?: return OutboundParsedMetadata(pingHost = null, endpointSummary = null)
    val server = (json["server"] as? JsonPrimitive)?.contentOrNull
        ?: return OutboundParsedMetadata(pingHost = null, endpointSummary = null)
    val pingHost = server.trim().removeSurrounding("[", "]").takeIf(String::isNotEmpty)
    val port = (json["server_port"] as? JsonPrimitive)?.contentOrNull
    val endpointSummary = when {
        port.isNullOrBlank() -> server
        ':' in server && !server.startsWith('[') -> "[$server]:$port"
        else -> "$server:$port"
    }
    return OutboundParsedMetadata(
        pingHost = pingHost,
        endpointSummary = endpointSummary,
    )
}

internal fun Long?.toOutboundPingSortKey(): Long {
    return when {
        this == null -> Long.MAX_VALUE
        this < 0L -> Long.MAX_VALUE - 1L
        else -> this
    }
}
