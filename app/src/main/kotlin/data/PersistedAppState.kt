// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package data

import app.AppState

internal data class PersistedAppState(
    val metadata: AppStateMetadataEntity? = null,
    val outboundGroups: List<OutboundGroupEntity> = emptyList(),
    val outbounds: List<OutboundEntity> = emptyList(),
    val endpoints: List<EndpointEntity> = emptyList(),
    val routeRules: List<RouteRuleEntity> = emptyList(),
    val dnsServers: List<DnsServerEntity> = emptyList(),
    val dnsRules: List<DnsRuleEntity> = emptyList(),
    val customResourceFiles: List<CustomResourceFileEntity> = emptyList(),
    val proxyAppListSelectedApps: List<ProxyAppListSelectedAppEntity> = emptyList(),
    val selectors: List<SelectorEntity> = emptyList(),
) {
    fun hasRoomContent(): Boolean = metadata != null

    fun toAppState(settings: AppState): AppState {
        val identity = requireNotNull(metadata)
        val restoredOutboundGroups = outboundGroups
            .map(OutboundGroupEntity::toState)
        val restoredOutbounds = outbounds
            .map(OutboundEntity::toState)
            .filter { outbound -> restoredOutboundGroups.any { group -> group.id == outbound.groupId } }
        val restoredEndpoints = endpoints.map(EndpointEntity::toState)
        val restoredSelectors = selectors.map(SelectorEntity::toState)

        return settings.copy(
            outboundGroups = restoredOutboundGroups,
            nextOutboundGroupId = maxOf(
                identity.nextOutboundGroupId,
                (restoredOutboundGroups.maxOfOrNull { group -> group.id } ?: 0) + 1,
            ),
            outbounds = restoredOutbounds,
            nextOutboundId = maxOf(
                identity.nextOutboundId,
                (restoredOutbounds.maxOfOrNull { outbound -> outbound.id } ?: 0) + 1,
            ),
            endpoints = restoredEndpoints,
            nextEndpointId = maxOf(
                identity.nextEndpointId,
                (restoredEndpoints.maxOfOrNull { endpoint -> endpoint.id } ?: 0) + 1,
            ),
            selectors = restoredSelectors,
            nextSelectorId = maxOf(
                identity.nextSelectorId,
                (restoredSelectors.maxOfOrNull { selector -> selector.id } ?: 0) + 1,
            ),
            routeRules = routeRules.map(RouteRuleEntity::toState),
            nextRouteRuleId = maxOf(
                identity.nextRouteRuleId,
                (routeRules.maxOfOrNull(RouteRuleEntity::id) ?: 0) + 1,
            ),
            dnsServers = dnsServers.map(DnsServerEntity::toState),
            nextDnsServerId = maxOf(
                identity.nextDnsServerId,
                (dnsServers.maxOfOrNull(DnsServerEntity::id) ?: 0) + 1,
            ),
            dnsRules = dnsRules.map(DnsRuleEntity::toState),
            nextDnsRuleId = maxOf(
                identity.nextDnsRuleId,
                (dnsRules.maxOfOrNull(DnsRuleEntity::id) ?: 0) + 1,
            ),
            customResourceFiles = customResourceFiles.map(CustomResourceFileEntity::toState),
            nextCustomResourceFileId = maxOf(
                identity.nextCustomResourceFileId,
                (customResourceFiles.maxOfOrNull(CustomResourceFileEntity::id) ?: 0) + 1,
            ),
            proxyRunning = false,
            proxyAppListSelectedApps = proxyAppListSelectedApps.map { app -> app.packageKey },
        )
    }
}
