// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.resources

import engine.network.isIpAddress

internal sealed interface ResourceTextFileOrigin {
    data object Missing : ResourceTextFileOrigin

    data class Existing(val content: String) : ResourceTextFileOrigin
}

internal data class ResourceTextEditorSnapshot(
    val content: String,
    val origin: ResourceTextFileOrigin,
) {
    val isDraft: Boolean
        get() = origin == ResourceTextFileOrigin.Missing
}

internal fun resourceTextEditorSnapshot(
    existingContent: String?,
    fileName: String,
): ResourceTextEditorSnapshot = ResourceTextEditorSnapshot(
    content = existingContent ?: if (fileName.isHostsResource()) "" else DefaultJsonRuleSetDraft,
    origin = existingContent?.let(ResourceTextFileOrigin::Existing) ?: ResourceTextFileOrigin.Missing,
)

internal class InvalidHostsResourceException(val lineNumber: Int) :
    IllegalArgumentException("Invalid hosts entry at line $lineNumber")

/** Blank lines, comments, IPv4/IPv6 addresses and multiple aliases follow hosts syntax. */
internal fun requireValidHostsResource(content: String) {
    content.lineSequence().forEachIndexed { index, line ->
        val entry = line.substringBefore('#').trim()
        if (entry.isNotEmpty()) {
            val fields = entry.split(Regex("\\s+"))
            if (fields.size < 2 || !isIpAddress(fields.first()) ||
                '%' in fields.first() || fields.any { field -> field.any(Char::isISOControl) }
            ) {
                throw InvalidHostsResourceException(index + 1)
            }
        }
    }
}
