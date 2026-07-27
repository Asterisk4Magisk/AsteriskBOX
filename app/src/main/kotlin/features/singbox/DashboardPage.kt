// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    kotlinx.coroutines.FlowPreview::class,
)

package features.singbox

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.AppServices
import app.LocalAppServices
import app.LocalAppStateStore
import app.LocalIsWideScreen
import app.LocalNavigator
import app.LocalUpdateAppState
import org.asterisk.zcc.abox.R
import app.collectAppState
import app.modes.SingBoxModeDirect
import app.modes.SingBoxModeGlobal
import app.modes.SingBoxModeRule
import app.modes.RunModeBpf2Socks
import app.modes.RunModeTproxy
import app.modes.RunModeTun
import app.modes.RunModeTun2Socks
import engine.singbox.runtime.SingBoxTrafficSample
import engine.singbox.runtime.SingBoxTrafficState
import engine.proxy.ProxyServiceResult
import features.home.HomeControllerState
import features.home.HomeMonitoringOverviewState
import features.home.HomeNetworkActivityState
import features.home.HomeNetworkRowKind
import features.home.HomeServiceStatus
import features.home.buildHomeControllerState
import features.home.buildHomeModeChange
import features.home.buildHomeMonitoringOverviewState
import features.home.buildHomeNetworkActivityState
import features.home.formatHomeRuntimeBytes
import features.home.homeFocusTone
import features.monitoring.MonitoringIntent
import features.monitoring.ObserveMonitoring
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch
import ui.components.AsteriskExpressiveCard
import ui.components.AsteriskFocusSurface
import ui.components.AsteriskPageCard
import ui.components.AsteriskSegmentItem
import ui.components.AsteriskSegmentedControl
import ui.layout.pageContentPaddingWithCutout
import ui.layout.pageListPadding
import ui.theme.AsteriskMotion
import ui.theme.AsteriskShapeTokens
import ui.theme.ExpressiveShapeRole
import ui.theme.FocusDensity
import utils.toReadableBytes
import kotlin.time.Duration.Companion.milliseconds
import app.navigation.Route as AppRoute
import ui.icons.AsteriskIcons as Icons

private data class HomeTrafficPresentation(
    val traffic: SingBoxTrafficState,
    val samples: List<SingBoxTrafficSample>,
)

private fun AppServices.homeTrafficPresentationSnapshot(): HomeTrafficPresentation {
    val runtimeState = singBoxRuntime.state.value
    return HomeTrafficPresentation(
        traffic = runtimeState.traffic,
        samples = singBoxRuntime.trafficHistorySnapshot(HomeNetworkHistoryLimit),
    )
}

private fun AppServices.homeMonitoringOverviewSnapshot() =
    buildHomeMonitoringOverviewState(monitoring.state.value)

@Composable
fun SingBoxDashboardPage(
    padding: PaddingValues,
) {
    val appState by LocalAppStateStore.current.collectAppState()
    val updateAppState = LocalUpdateAppState.current
    val services = LocalAppServices.current
    val trafficPresentation by remember(services.singBoxRuntime) {
        services.singBoxRuntime.state
            .map { state -> state.traffic }
            .sample(HomeUiSampleIntervalMillis.milliseconds)
            .map { traffic ->
                HomeTrafficPresentation(
                    traffic = traffic,
                    samples = services.singBoxRuntime.trafficHistorySnapshot(HomeNetworkHistoryLimit),
                )
            }
            .distinctUntilChanged()
    }.collectAsState(initial = services.homeTrafficPresentationSnapshot())
    val monitoringOverviewState by remember(services.monitoring) {
        services.monitoring.state
            .map(::buildHomeMonitoringOverviewState)
            .distinctUntilChanged()
            .sample(HomeUiSampleIntervalMillis.milliseconds)
    }.collectAsState(initial = services.homeMonitoringOverviewSnapshot())
    val navigator = LocalNavigator.current
    val isWideScreen = LocalIsWideScreen.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    ObserveMonitoring(MonitoringIntent.Home)
    var operationInProgress by rememberSaveable { mutableStateOf(false) }
    val controllerState = remember(appState.proxyRunning, appState.runMode, appState.singBoxMode) {
        buildHomeControllerState(appState)
    }
    val networkActivityState = remember(appState.proxyRunning, trafficPresentation) {
        buildHomeNetworkActivityState(
            appState = appState,
            traffic = trafficPresentation.traffic,
            networkSamples = trafficPresentation.samples,
        )
    }
    val latestAppState = rememberUpdatedState(appState)

    val startFailedMessage = stringResource(R.string.sing_box_dashboard_start_failed)
    val stopFailedMessage = stringResource(R.string.sing_box_dashboard_stop_failed)
    val serviceStartedMessage = stringResource(R.string.proxy_service_started)
    val serviceStoppedMessage = stringResource(R.string.proxy_service_stopped)
    val modeFailedMessage = stringResource(R.string.home_mode_change_failed)

    suspend fun handleProxyServiceResult(result: ProxyServiceResult, wasRunning: Boolean) {
        when (result) {
            is ProxyServiceResult.Success -> {
                updateAppState { state ->
                    state.copy(
                        proxyRunning = result.proxyRunning,
                        localProxyPort = result.appState?.localProxyPort ?: state.localProxyPort,
                        singBoxControlPort = result.appState?.singBoxControlPort ?: state.singBoxControlPort,
                    )
                }
                services.tipNotifier.show(if (result.proxyRunning) serviceStartedMessage else serviceStoppedMessage)
            }

            is ProxyServiceResult.Failed -> {
                updateAppState { state -> state.copy(proxyRunning = false) }
                services.tipNotifier.showError(
                    result.error,
                    if (wasRunning) stopFailedMessage else startFailedMessage,
                )
            }
        }
    }

    fun toggleService() {
        if (operationInProgress) return
        val stateSnapshot = appState
        val wasRunning = stateSnapshot.proxyRunning
        operationInProgress = true
        val operationJob = services.appScope.launch {
            handleProxyServiceResult(services.proxyServiceUseCase.toggle(stateSnapshot), wasRunning)
        }
        scope.launch {
            try {
                operationJob.join()
            } finally {
                operationInProgress = false
            }
        }
    }

    fun changeMode(mode: Int) {
        if (operationInProgress) return
        val stateSnapshot = latestAppState.value
        val modeChange = buildHomeModeChange(
            appState = stateSnapshot,
            currentMode = stateSnapshot.singBoxMode,
            requestedMode = mode,
        ) ?: return
        val previousMode = stateSnapshot.singBoxMode
        if (modeChange.persistSelection) {
            updateAppState { state -> state.copy(singBoxMode = mode) }
        }
        if (modeChange.patchRuntime) {
            operationInProgress = true
            val operationJob = services.appScope.launch {
                services.singBoxRuntime.patchMode(modeChange.runtimeAppState)
                    .onFailure { error ->
                        if (modeChange.persistSelection) {
                            updateAppState { state ->
                                if (state.singBoxMode == mode) {
                                    state.copy(singBoxMode = previousMode)
                                } else {
                                    state
                                }
                            }
                        }
                        services.tipNotifier.showError(error, modeFailedMessage)
                    }
            }
            scope.launch {
                try {
                    operationJob.join()
                } finally {
                    operationInProgress = false
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
            )
        },
    ) { innerPadding ->
        val contentPadding = pageContentPaddingWithCutout(
            innerPadding = innerPadding,
            outerPadding = padding,
            isWideScreen = isWideScreen,
        )
        val listPadding = pageListPadding(contentPadding, bottomExtra = 24.dp)

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = listPadding,
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item("controller") {
                HomeControllerCard(
                    controllerState = controllerState,
                    networkActivityState = networkActivityState,
                    operationInProgress = operationInProgress,
                    onToggleService = ::toggleService,
                    onModeSelected = ::changeMode,
                )
            }
            item("network_activity") {
                NetworkActivityCard(networkActivityState)
            }
            item("monitoring_row_one") {
                Row(
                    modifier = HomeContentModifier,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    MonitoringEntryCard(
                        title = stringResource(R.string.home_monitor_resource),
                        summary = homeResourceSummary(monitoringOverviewState),
                        icon = Icons.Rounded.Memory,
                        prominent = true,
                        onClick = { navigator.push(AppRoute.ResourceMonitor) },
                        modifier = Modifier.weight(1f),
                    )
                    MonitoringEntryCard(
                        title = stringResource(R.string.home_monitor_connections),
                        summary = monitoringOverviewState.activeConnectionCount?.let { count ->
                            pluralStringResource(R.plurals.home_connections_summary, count, count)
                        } ?: stringResource(R.string.home_value_unavailable),
                        icon = Icons.Rounded.Lan,
                        onClick = { navigator.push(AppRoute.ConnectionsMonitor) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            item("monitoring_row_two") {
                Row(
                    modifier = HomeContentModifier,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    MonitoringEntryCard(
                        title = stringResource(R.string.home_monitor_traffic),
                        summary = stringResource(
                            R.string.home_traffic_summary,
                            monitoringOverviewState.todayTrafficBytes.toReadableBytes(),
                        ),
                        icon = Icons.Rounded.DataUsage,
                        onClick = { navigator.push(AppRoute.TrafficMonitor) },
                        modifier = Modifier.weight(1f),
                    )
                    MonitoringEntryCard(
                        title = stringResource(R.string.home_monitor_network),
                        summary = homeNetworkSummary(monitoringOverviewState),
                        icon = Icons.Rounded.Public,
                        prominent = true,
                        onClick = { navigator.push(AppRoute.NetworkMonitor) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun HomeControllerCard(
    controllerState: HomeControllerState,
    networkActivityState: HomeNetworkActivityState,
    operationInProgress: Boolean,
    onToggleService: () -> Unit,
    onModeSelected: (Int) -> Unit,
) {
    val serviceMotion = AsteriskMotion.fastEffects<Float>()
    val serviceSwitchAlpha by animateFloatAsState(
        targetValue = if (operationInProgress) 0f else 1f,
        animationSpec = serviceMotion,
        label = "home-service-switch-alpha",
    )
    AsteriskFocusSurface(
        title = if (controllerState.serviceStatus == HomeServiceStatus.Enabled) {
            stringResource(R.string.home_service_enabled)
        } else {
            stringResource(R.string.home_service_disabled)
        },
        modifier = HomeContentModifier,
        density = FocusDensity.Large,
        tone = homeFocusTone(controllerState.serviceStatus),
        summary = runModeLabel(controllerState.runMode),
        stateIcon = Icons.Rounded.PowerSettingsNew,
        metrics = {
            HomeFocusMetric(
                icon = Icons.Rounded.Upload,
                label = stringResource(R.string.home_accumulated_upload),
                value = formatHomeRuntimeBytes(networkActivityState.accumulatedUploadBytes),
                modifier = Modifier.weight(1f),
            )
            HomeFocusMetric(
                icon = Icons.Rounded.Download,
                label = stringResource(R.string.home_accumulated_download),
                value = formatHomeRuntimeBytes(networkActivityState.accumulatedDownloadBytes),
                modifier = Modifier.weight(1f),
            )
        },
        primaryAction = {
            Box(
                modifier = Modifier.size(52.dp),
                contentAlignment = Alignment.Center,
            ) {
                Switch(
                    checked = controllerState.serviceStatus == HomeServiceStatus.Enabled,
                    onCheckedChange = { onToggleService() },
                    modifier = Modifier.alpha(serviceSwitchAlpha),
                    enabled = !operationInProgress,
                )
                AnimatedVisibility(
                    visible = operationInProgress,
                    enter = fadeIn(animationSpec = serviceMotion),
                    exit = fadeOut(animationSpec = serviceMotion),
                    label = "home-service-loading",
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
                }
            }
        },
    ) {
        Box(modifier = Modifier.offset(y = HomeModeControlOffset)) {
            AsteriskSegmentedControl(
                items = homeModeOptions().map { option ->
                    AsteriskSegmentItem(value = option.mode, label = option.label)
                },
                selectedValue = controllerState.singBoxMode,
                onSelected = onModeSelected,
                enabled = !operationInProgress,
            )
        }
    }
}

@Composable
private fun HomeFocusMetric(
    icon: ImageVector,
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    HomeControllerItemContent(
        modifier = modifier.offset(y = HomeAccumulatedTrafficOffset),
        icon = icon,
        iconSize = HomeControllerTrafficIconSize,
        iconOffsetY = HomeControllerTrafficIconOffsetY,
        label = label,
        value = value,
    )
}

@Composable
private fun HomeControllerItemContent(
    icon: ImageVector,
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    iconSize: Dp = HomeControllerItemIconSize,
    iconOffsetY: Dp = 0.dp,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(HomeControllerItemIconSlotSize),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(iconSize).offset(y = iconOffsetY),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = HomeControllerItemTextSpacing),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun NetworkActivityCard(networkActivityState: HomeNetworkActivityState) {
    AsteriskPageCard(modifier = HomeContentModifier.height(180.dp)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.home_network_activity),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(
                        R.string.home_network_speed_summary,
                        formatHomeSpeed(networkActivityState.uploadBytesPerSecond),
                        formatHomeSpeed(networkActivityState.downloadBytesPerSecond),
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(12.dp))
            if (networkActivityState.hasNetworkSamples) {
                NetworkActivityChart(
                    state = networkActivityState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.home_no_network_activity),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}

@Composable
private fun NetworkActivityChart(
    state: HomeNetworkActivityState,
    modifier: Modifier = Modifier,
) {
    val uploadColor = MaterialTheme.colorScheme.tertiary
    val downloadColor = MaterialTheme.colorScheme.primary
    val baselineColor = MaterialTheme.colorScheme.outlineVariant
    Canvas(modifier = modifier) {
        val samples = state.networkSamples
        val maxValue = samples.maxOfOrNull { sample -> maxOf(sample.up, sample.down) }?.coerceAtLeast(1L) ?: 1L
        val baseline = size.height - 2.dp.toPx()
        drawLine(
            color = baselineColor,
            start = Offset(0f, baseline),
            end = Offset(size.width, baseline),
            strokeWidth = 2.dp.toPx(),
            cap = StrokeCap.Round,
        )
        if (samples.size < 2) return@Canvas
        val step = size.width / (samples.lastIndex.coerceAtLeast(1))
        fun point(index: Int, value: Long): Offset {
            val fraction = value.toFloat() / maxValue.toFloat()
            return Offset(index * step, baseline - fraction.coerceIn(0f, 1f) * baseline)
        }
        samples.zipWithNext().forEachIndexed { index, (first, second) ->
            drawLine(
                color = uploadColor,
                start = point(index, first.up),
                end = point(index + 1, second.up),
                strokeWidth = 2.5.dp.toPx(),
                cap = StrokeCap.Round,
            )
            drawLine(
                color = downloadColor,
                start = point(index, first.down),
                end = point(index + 1, second.down),
                strokeWidth = 2.5.dp.toPx(),
                cap = StrokeCap.Round,
            )
        }
    }
}

@Composable
private fun MonitoringEntryCard(
    title: String,
    summary: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    prominent: Boolean = false,
) {
    AsteriskExpressiveCard(
        onClick = onClick,
        modifier = modifier.height(148.dp),
        role = if (prominent) ExpressiveShapeRole.GroupLarge else ExpressiveShapeRole.ContentCard,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Surface(
                modifier = Modifier.size(42.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                shape = AsteriskShapeTokens.SmallContainer,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Column {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun runModeLabel(runMode: Int): String {
    return stringResource(
        when (runMode) {
            RunModeTproxy -> R.string.settings_run_mode_tproxy
            RunModeTun -> R.string.settings_run_mode_tun
            RunModeTun2Socks -> R.string.settings_run_mode_tun2socks
            RunModeBpf2Socks -> R.string.settings_run_mode_bpf2socks
            else -> R.string.settings_run_mode_vpn_service
        },
    )
}

@Composable
private fun homeResourceSummary(monitoringState: HomeMonitoringOverviewState): String {
    if (!monitoringState.serviceRunning) return stringResource(R.string.home_value_unavailable)
    val cpu = monitoringState.resourceCpuPercent
        ?.let { value -> "%.1f%%".format(value) }
        ?: stringResource(R.string.home_value_unavailable)
    val memory = monitoringState.resourceMemoryBytes
        ?.toReadableBytes()
        ?: stringResource(R.string.home_value_unavailable)
    return stringResource(R.string.home_resource_summary, cpu, memory)
}

@Composable
private fun homeNetworkSummary(homeState: HomeMonitoringOverviewState): String {
    val unavailable = stringResource(R.string.home_value_unavailable)
    val ipv4 = homeState.networkRows
        .firstOrNull { row -> row.kind == HomeNetworkRowKind.Ipv4 }
        ?.value ?: unavailable
    val ipv6 = homeState.networkRows
        .firstOrNull { row -> row.kind == HomeNetworkRowKind.Ipv6 }
        ?.value ?: unavailable
    return stringResource(R.string.home_network_summary, ipv4, ipv6)
}

@Composable
private fun homeModeOptions(): List<HomeModeOption> {
    return listOf(
        HomeModeOption(
            SingBoxModeRule,
            stringResource(R.string.sing_box_mode_rule),
        ),
        HomeModeOption(
            SingBoxModeGlobal,
            stringResource(R.string.sing_box_mode_global),
        ),
        HomeModeOption(
            SingBoxModeDirect,
            stringResource(R.string.sing_box_mode_direct),
        ),
    )
}

@Composable
private fun formatHomeSpeed(bytes: Long?): String {
    return if (bytes == null) {
        formatHomeRuntimeBytes(null)
    } else {
        stringResource(R.string.monitor_speed_per_second, formatHomeRuntimeBytes(bytes))
    }
}

private data class HomeModeOption(
    val mode: Int,
    val label: String,
)

private const val HomeUiSampleIntervalMillis = 1_000L
private const val HomeNetworkHistoryLimit = 60

private val HomeContentModifier = Modifier
    .fillMaxWidth()
    .widthIn(max = 840.dp)

private val HomeControllerItemIconSlotSize = 28.dp
private val HomeControllerItemIconSize = 24.dp
private val HomeControllerTrafficIconSize = 28.dp
private val HomeControllerTrafficIconOffsetY = 0.5.dp
private val HomeAccumulatedTrafficOffset = 6.dp
private val HomeModeControlOffset = 10.dp
private val HomeControllerItemTextSpacing = 14.dp
