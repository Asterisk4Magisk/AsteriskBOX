// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.outbound

import app.OutboundState

/** Extension of the document a group's share links are exported to. */
internal const val OutboundShareFileExtension = "txt"

/**
 * Share links of every outbound in the group, one per line.
 *
 * Outbounds without a standard share URL are skipped: the internal-link form is not exported,
 * which also keeps this equal to what the single-node "copy URL" action would produce.
 */
internal fun outboundGroupShareLinks(
    groupId: Int,
    outbounds: List<OutboundState>,
): String = outboundUrls(outbounds.filter { outbound -> outbound.groupId == groupId })

/**
 * Suggested document name for [groupName]. Letters and digits survive, so a localized group
 * name stays readable; everything a document provider may reject collapses into a dash. The
 * sanitized part is bounded because CJK names reach a provider's byte limit long before their
 * character count looks large.
 */
internal fun outboundGroupShareFileName(groupName: String): String {
    val safeName = groupName.trim()
        .map { char -> if (char.isLetterOrDigit() || char == '-' || char == '_') char else '-' }
        .joinToString("")
        .trim('-')
        .take(OutboundShareFileNameMaxLength)
        .trimEnd('-')
        .ifBlank { "outbounds" }
    return "$safeName.$OutboundShareFileExtension"
}

private const val OutboundShareFileNameMaxLength = 64
