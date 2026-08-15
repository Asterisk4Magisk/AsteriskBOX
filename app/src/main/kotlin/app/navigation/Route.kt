// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package app.navigation

import androidx.navigation3.runtime.NavKey
import app.SingBoxDnsRuleState
import app.SingBoxRouteRuleState
import kotlinx.serialization.Serializable

/**
 * Type-safe navigation keys for Navigation3.
 * Each destination is a NavKey (data object/data class) and can be saved/restored in the back stack.
 */
sealed interface Route : NavKey {
    @Serializable
    data object Main : Route

    @Serializable
    data object About : Route

    @Serializable
    data object License : Route

    @Serializable
    data object CoreLogs : Route

    @Serializable
    data object LogcatLogs : Route

    @Serializable
    data object ResourceManagement : Route

    @Serializable
    data class ResourceJsonEdit(
        val resourceId: Int,
    ) : Route

    @Serializable
    data object OutboundGroupList : Route

    @Serializable
    data object OutboundList : Route

    @Serializable
    data object SelectorManagement : Route

    @Serializable
    data object EndpointList : Route

    @Serializable
    data object RoutingManagement : Route

    @Serializable
    data class DnsManagement(
        val openSettings: Boolean = false,
    ) : Route

    @Serializable
    data class DnsRuleEdit(
        val ruleId: Int = 0,
        val initialDraft: SingBoxDnsRuleState? = null,
        val resultKey: String = "",
        val nested: Boolean = false,
        val topLevelRuleId: Int = 0,
    ) : Route

    @Serializable
    data class SelectorEdit(
        val selectorId: Int = 0,
    ) : Route

    @Serializable
    data class RouteRuleEdit(
        val ruleId: Int = 0,
        val initialDraft: SingBoxRouteRuleState? = null,
        val resultKey: String = "",
        val nested: Boolean = false,
    ) : Route

    @Serializable
    data class OutboundEdit(
        val outboundId: Int = 0,
        val groupId: Int = 1,
        val type: String = "socks",
    ) : Route

    @Serializable
    data class EndpointEdit(
        val endpointId: Int = 0,
        val type: String = "wireguard",
        val draftRemarks: String = "",
    ) : Route

    @Serializable
    data object ResourceMonitor : Route

    @Serializable
    data object ConnectionsMonitor : Route

    @Serializable
    data object TrafficMonitor : Route

    @Serializable
    data object NetworkMonitor : Route

}
