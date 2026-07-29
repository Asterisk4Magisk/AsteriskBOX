// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.routing

import app.AppState
import app.SingBoxRouteNetworkStrategies
import app.SingBoxRouteNetworkTypes
import engine.singbox.isNonNegativeSingBoxDuration

internal data class RoutingSettingsDraft(
    val routeAutoDetectInterface: Boolean = false,
    val routeOverrideAndroidVpn: Boolean = false,
    val routeDefaultNetworkStrategy: String = "",
    val routeDefaultNetworkTypes: List<String> = emptyList(),
    val routeDefaultFallbackNetworkTypes: List<String> = emptyList(),
    val routeDefaultFallbackDelay: String = "",
    val routeFindProcess: Boolean = false,
) {
    val showNetworkSettings: Boolean
        get() = routeAutoDetectInterface

    val showFallbackSettings: Boolean
        get() = routeAutoDetectInterface && routeDefaultNetworkStrategy == "fallback"

    fun sanitized(): RoutingSettingsDraft =
        copy(
            routeDefaultNetworkStrategy = routeDefaultNetworkStrategy
                .trim()
                .lowercase()
                .takeIf(SingBoxRouteNetworkStrategies::contains)
                .orEmpty(),
            routeDefaultNetworkTypes = routeDefaultNetworkTypes.sanitizedRouteNetworkTypes(),
            routeDefaultFallbackNetworkTypes =
                routeDefaultFallbackNetworkTypes.sanitizedRouteNetworkTypes(),
            routeDefaultFallbackDelay = routeDefaultFallbackDelay.trim(),
        )

    fun hasValidFallbackDelay(): Boolean =
        !showFallbackSettings ||
            routeDefaultFallbackDelay.isBlank() ||
            isNonNegativeSingBoxDuration(routeDefaultFallbackDelay)
}

internal fun AppState.toRoutingSettingsDraft(): RoutingSettingsDraft =
    RoutingSettingsDraft(
        routeAutoDetectInterface = routeAutoDetectInterface,
        routeOverrideAndroidVpn = routeOverrideAndroidVpn,
        routeDefaultNetworkStrategy = routeDefaultNetworkStrategy,
        routeDefaultNetworkTypes = routeDefaultNetworkTypes,
        routeDefaultFallbackNetworkTypes = routeDefaultFallbackNetworkTypes,
        routeDefaultFallbackDelay = routeDefaultFallbackDelay,
        routeFindProcess = routeFindProcess,
    ).sanitized()

internal fun AppState.withRoutingSettings(draft: RoutingSettingsDraft): AppState {
    val sanitized = draft.sanitized()
    return copy(
        routeAutoDetectInterface = sanitized.routeAutoDetectInterface,
        routeOverrideAndroidVpn = sanitized.routeOverrideAndroidVpn,
        routeDefaultNetworkStrategy = sanitized.routeDefaultNetworkStrategy,
        routeDefaultNetworkTypes = sanitized.routeDefaultNetworkTypes,
        routeDefaultFallbackNetworkTypes = sanitized.routeDefaultFallbackNetworkTypes,
        routeDefaultFallbackDelay = sanitized.routeDefaultFallbackDelay,
        routeFindProcess = sanitized.routeFindProcess,
    )
}

private fun List<String>.sanitizedRouteNetworkTypes(): List<String> {
    val selected = map { value -> value.trim().lowercase() }.toSet()
    return SingBoxRouteNetworkTypes.filter(selected::contains)
}
