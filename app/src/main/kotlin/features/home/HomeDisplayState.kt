// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.home

import app.AppState
import app.modes.isRootRunMode
import engine.singbox.runtime.SingBoxTrafficSample
import engine.singbox.runtime.SingBoxTrafficState
import features.monitoring.MonitoringState
import ui.theme.FocusTone
import utils.toReadableBytes

internal enum class HomeServiceStatus {
    Enabled,
    Disabled,
    Error,
}

internal fun homeFocusTone(status: HomeServiceStatus): FocusTone = when (status) {
    HomeServiceStatus.Enabled -> FocusTone.Primary
    HomeServiceStatus.Disabled -> FocusTone.Inactive
    HomeServiceStatus.Error -> FocusTone.Error
}

internal enum class HomeNetworkRowKind {
    Ipv4,
    Ipv6,
}

internal data class HomeNetworkRow(
    val kind: HomeNetworkRowKind,
    val value: String?,
)

internal data class HomeControllerState(
    val serviceStatus: HomeServiceStatus,
    val runMode: Int,
    val singBoxMode: Int,
)

internal data class HomeModeChange(
    val runtimeAppState: AppState,
    val persistSelection: Boolean,
    val runtimeAction: HomeModeRuntimeAction,
)

internal enum class HomeModeRuntimeAction {
    None,
    PatchRuntime,
    RestartService,
}

internal data class HomeNetworkActivityState(
    val accumulatedUploadBytes: Long?,
    val accumulatedDownloadBytes: Long?,
    val uploadBytesPerSecond: Long?,
    val downloadBytesPerSecond: Long?,
    val networkSamples: List<SingBoxTrafficSample>,
) {
    val hasNetworkSamples: Boolean
        get() = networkSamples.isNotEmpty()
}

internal data class HomeMonitoringOverviewState(
    val serviceRunning: Boolean,
    val resourceCpuPercent: Double?,
    val resourceMemoryBytes: Long?,
    val activeConnectionCount: Int?,
    val todayTrafficBytes: Long,
    val networkRows: List<HomeNetworkRow>,
)

internal fun buildHomeControllerState(
    appState: AppState,
): HomeControllerState = HomeControllerState(
    serviceStatus = if (appState.proxyRunning) HomeServiceStatus.Enabled else HomeServiceStatus.Disabled,
    runMode = appState.runMode,
    singBoxMode = appState.singBoxMode,
)

internal fun buildHomeModeChange(
    appState: AppState,
    currentMode: Int,
    requestedMode: Int,
): HomeModeChange? {
    if (requestedMode == currentMode) return null
    return HomeModeChange(
        runtimeAppState = appState.copy(singBoxMode = requestedMode),
        persistSelection = true,
        runtimeAction = when {
            !appState.proxyRunning -> HomeModeRuntimeAction.None
            appState.runMode.isRootRunMode() -> HomeModeRuntimeAction.RestartService
            else -> HomeModeRuntimeAction.PatchRuntime
        },
    )
}

internal fun buildHomeNetworkActivityState(
    appState: AppState,
    traffic: SingBoxTrafficState,
    networkSamples: List<SingBoxTrafficSample>,
): HomeNetworkActivityState {
    val trafficAvailable = appState.proxyRunning && traffic.connected
    return HomeNetworkActivityState(
        accumulatedUploadBytes = traffic.totalUp.takeIf { appState.proxyRunning },
        accumulatedDownloadBytes = traffic.totalDown.takeIf { appState.proxyRunning },
        uploadBytesPerSecond = traffic.latest.up.takeIf { trafficAvailable },
        downloadBytesPerSecond = traffic.latest.down.takeIf { trafficAvailable },
        networkSamples = networkSamples.takeLast(HomeNetworkSampleLimit).takeIf { trafficAvailable }.orEmpty(),
    )
}

internal fun buildHomeMonitoringOverviewState(
    monitoringState: MonitoringState,
): HomeMonitoringOverviewState = HomeMonitoringOverviewState(
    serviceRunning = monitoringState.serviceRunning,
    resourceCpuPercent = monitoringState.resource.cpuPercent,
    resourceMemoryBytes = monitoringState.resource.memoryBytes,
    activeConnectionCount = monitoringState.connections.activeCount,
    todayTrafficBytes = monitoringState.traffic.today.total,
    networkRows = listOf(
        HomeNetworkRow(HomeNetworkRowKind.Ipv4, monitoringState.network.local.ipv4Addresses.firstOrNull()),
        HomeNetworkRow(HomeNetworkRowKind.Ipv6, monitoringState.network.local.ipv6Addresses.firstOrNull()),
    ),
)

internal fun formatHomeRuntimeBytes(bytes: Long?): String {
    return bytes?.toReadableBytes(keepTrailingZero = false) ?: HomeUnavailableValue
}

private const val HomeNetworkSampleLimit = 60
internal const val HomeUnavailableValue = "—"
