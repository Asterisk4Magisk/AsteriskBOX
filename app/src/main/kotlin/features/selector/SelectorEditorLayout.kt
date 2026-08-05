// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.selector

import app.SingBoxSelectorTypeSelector
import app.SingBoxSelectorTypeUrlTest
import app.SupportedSingBoxSelectorTypes

internal sealed interface SelectorEditorSection {
    data object Remarks : SelectorEditorSection

    data object Type : SelectorEditorSection

    data class ConnectionOptions(
        val showUrlTestSettings: Boolean,
    ) : SelectorEditorSection

    data class MemberHeader(
        val showDefaultOutbound: Boolean,
    ) : SelectorEditorSection

    data object MemberContent : SelectorEditorSection
}

internal fun selectorEditorSections(type: String): List<SelectorEditorSection> {
    require(type in SupportedSingBoxSelectorTypes) { "Unsupported selector type" }
    return listOf(
        SelectorEditorSection.Remarks,
        SelectorEditorSection.Type,
        SelectorEditorSection.ConnectionOptions(
            showUrlTestSettings = type == SingBoxSelectorTypeUrlTest,
        ),
        SelectorEditorSection.MemberHeader(
            showDefaultOutbound = type == SingBoxSelectorTypeSelector,
        ),
        SelectorEditorSection.MemberContent,
    )
}

internal fun selectorDefaultOptionTags(members: List<String>): List<String?> =
    if (members.isEmpty()) listOf(null) else members
