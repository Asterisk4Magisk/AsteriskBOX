// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.settings

internal enum class SettingsSectionId {
    Theme,
    General,
    Core,
    Advanced,
    Vpn,
    Tproxy,
    Tun,
    Tun2Socks,
    Bpf2Socks,
    Logs,
    BackupRestore,
    About,
}

internal data class SettingsSearchItem(
    val section: SettingsSectionId,
    val title: String,
    val summary: String = "",
    val value: String = "",
    val optionText: List<String> = emptyList(),
)

internal enum class SettingsSearchFocusStatus {
    Idle,
    Matches,
    NoResults,
}

internal data class SettingsSearchFocusState(
    val status: SettingsSearchFocusStatus,
    val matchCount: Int,
)

internal fun reduceSettingsSearchFocusState(
    query: String,
    matchCount: Int,
): SettingsSearchFocusState = when {
    query.isBlank() -> SettingsSearchFocusState(SettingsSearchFocusStatus.Idle, 0)
    matchCount > 0 -> SettingsSearchFocusState(SettingsSearchFocusStatus.Matches, matchCount)
    else -> SettingsSearchFocusState(SettingsSearchFocusStatus.NoResults, 0)
}

internal fun SettingsSearchItem.matchesSettingsQuery(query: String): Boolean {
    val normalized = query.trim()
    if (normalized.isEmpty()) return true
    return sequenceOf(title, summary, value).plus(optionText.asSequence()).any { candidate ->
        candidate.contains(normalized, ignoreCase = true)
    }
}

internal fun filterSettingsItems(
    items: List<SettingsSearchItem>,
    query: String,
): List<SettingsSearchItem> = items.filter { item -> item.matchesSettingsQuery(query) }
