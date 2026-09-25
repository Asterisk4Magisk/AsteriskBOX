// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package engine.singbox.config

import app.OutboundState
import features.outbound.SupportedSingBoxProxyOutboundTypes
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Shared by configuration generation and the reference picker's dependency graph. */
internal fun OutboundState.inheritedGroupDetour(
    groupDetour: String,
    parsed: JsonObject,
): String? {
    if (type !in SupportedSingBoxProxyOutboundTypes) return null
    val target = groupDetour.trim().takeIf(String::isNotEmpty) ?: return null
    if (target == tag) return null
    val explicit = parsed["detour"]
    // Preserve explicit values (including malformed ones for normal core validation).
    if (explicit != null && (explicit as? JsonPrimitive)?.contentOrNull != "") return null
    return target
}
