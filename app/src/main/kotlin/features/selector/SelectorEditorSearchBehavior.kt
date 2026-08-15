// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.selector

internal data class SelectorTargetSearchCandidate(
    val tag: String,
    val label: String,
    val groupName: String?,
)

internal sealed interface SelectorTargetSearchResult {
    val matchedTags: List<String>

    data class Valid(
        override val matchedTags: List<String>,
    ) : SelectorTargetSearchResult

    data object InvalidRegex : SelectorTargetSearchResult {
        override val matchedTags: List<String> = emptyList()
    }
}

internal enum class SelectorTargetSelectionState {
    None,
    Partial,
    All,
}

internal fun filterSelectorTargets(
    targets: List<SelectorTargetSearchCandidate>,
    query: String,
    regexEnabled: Boolean,
): SelectorTargetSearchResult {
    val normalized = query.trim()
    if (normalized.isEmpty()) {
        return SelectorTargetSearchResult.Valid(
            targets.map(SelectorTargetSearchCandidate::tag),
        )
    }
    val regex = if (regexEnabled) {
        runCatching { Regex(normalized, RegexOption.IGNORE_CASE) }.getOrNull()
            ?: return SelectorTargetSearchResult.InvalidRegex
    } else {
        null
    }
    val matchedTags = targets
        .filter { target ->
            listOfNotNull(target.tag, target.label, target.groupName).any { value ->
                regex?.containsMatchIn(value)
                    ?: value.contains(normalized, ignoreCase = true)
            }
        }
        .map(SelectorTargetSearchCandidate::tag)
    return SelectorTargetSearchResult.Valid(matchedTags)
}

internal fun selectorTargetSelectionState(
    selectedTags: Collection<String>,
    matchedTags: Collection<String>,
): SelectorTargetSelectionState {
    val uniqueMatches = matchedTags.toSet()
    val selected = selectedTags.toSet()
    val selectedCount = uniqueMatches.count(selected::contains)
    return when {
        selectedCount == 0 -> SelectorTargetSelectionState.None
        selectedCount == uniqueMatches.size -> SelectorTargetSelectionState.All
        else -> SelectorTargetSelectionState.Partial
    }
}
