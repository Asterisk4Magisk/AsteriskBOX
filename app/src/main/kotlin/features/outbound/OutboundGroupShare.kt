// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.outbound

import android.content.res.Resources
import androidx.compose.ui.platform.Clipboard
import app.OutboundState
import app.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ui.clipboard.setPlainText
import ui.feedback.AndroidToastTipNotifier

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
 * Copies [outboundGroupShareLinks] to the clipboard, or tips that the group has none. The outbound
 * list's options menu and the group card's share menu both copy through here.
 */
internal suspend fun copyOutboundGroupShareLinks(
    groupId: Int,
    outbounds: List<OutboundState>,
    clipboard: Clipboard,
    tipNotifier: AndroidToastTipNotifier,
    resources: Resources,
) {
    val links = withContext(Dispatchers.Default) { outboundGroupShareLinks(groupId, outbounds) }
    if (links.isBlank()) {
        tipNotifier.show(resources.getString(R.string.outbound_no_urls))
    } else {
        clipboard.setPlainText(links)
        tipNotifier.show(resources.getString(R.string.common_copied))
    }
}

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
