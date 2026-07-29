// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.dns

internal enum class DnsManagementGridSection {
    Settings,
    EmptyState,
    Rules,
}

internal data class DnsManagementGridLayout(
    val sections: List<DnsManagementGridSection>,
    val ruleIndexOffset: Int,
)

internal fun dnsManagementGridLayout(ruleCount: Int): DnsManagementGridLayout {
    return DnsManagementGridLayout(
        sections = listOf(
            DnsManagementGridSection.Settings,
            if (ruleCount == 0) {
                DnsManagementGridSection.EmptyState
            } else {
                DnsManagementGridSection.Rules
            },
        ),
        ruleIndexOffset = 1,
    )
}
