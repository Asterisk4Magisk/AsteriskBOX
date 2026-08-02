// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package data.backup

import app.OutboundGroupUpdateStatus
import app.SingBoxDnsRuleState
import app.SingBoxDnsServerState
import app.SingBoxRouteRuleState

internal fun AppBackupData.validateForRestore() {
    requireValidIds("outbound group", outboundGroups, AppBackupOutboundGroup::id)
    requireValidIds("outbound", outbounds, AppBackupOutbound::id)
    requireValidIds("endpoint", endpoints, AppBackupEndpoint::id)
    requireValidIds("selector", selectors, AppBackupSelector::id)
    requireValidIds("route rule", routeRules, SingBoxRouteRuleState::id)
    requireValidIds("DNS server", dnsServers, SingBoxDnsServerState::id)
    requireValidIds("DNS rule", dnsRules, SingBoxDnsRuleState::id)
    requireValidIds("custom resource file", customResourceFiles, AppBackupCustomResourceFile::id)

    val groupIds = outboundGroups.mapTo(mutableSetOf(), AppBackupOutboundGroup::id)
    outbounds.forEach { outbound ->
        require(outbound.groupId in groupIds) {
            "Outbound ${outbound.id} references missing group ${outbound.groupId}"
        }
    }
    outboundGroups.forEach { group ->
        require(
            OutboundGroupUpdateStatus.entries.any { status -> status.name == group.lastUpdateStatus },
        ) {
            "Unknown outbound group update status: ${group.lastUpdateStatus}"
        }
    }
}

private inline fun <T> requireValidIds(
    label: String,
    items: List<T>,
    idOf: (T) -> Int,
) {
    val ids = mutableSetOf<Int>()
    items.forEach { item ->
        val id = idOf(item)
        require(id > 0) { "$label ID must be positive" }
        require(id < Int.MAX_VALUE) { "$label ID is too large" }
        require(ids.add(id)) { "Duplicate $label ID: $id" }
    }
}
