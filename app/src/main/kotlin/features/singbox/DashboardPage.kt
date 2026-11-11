// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

@file:OptIn(
    kotlinx.coroutines.FlowPreview::class,
)

package features.singbox

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.AppServices
import app.LocalMainDestinationState
import app.navigation.MainDestination
import app.LocalAppServices
import app.LocalAppStateStore
import app.LocalIsWideScreen
import app.LocalNavigator
import app.LocalUpdateAppState
import app.R
import app.collectAppState
import app.modes.NetworkActivityStyleLines
import app.modes.RunModeBpf2Socks
import app.modes.RunModeEbpf
import app.modes.RunModeTproxy
import app.modes.RunModeTun
import app.modes.RunModeTun2Socks
import app.modes.RunModeVpnService
import app.modes.SingBoxModeDirect
import app.modes.SingBoxModeGlobal
import app.modes.SingBoxModeRule
import engine.singbox.config.APP_DIRECT_OUTBOUND
import engine.singbox.config.APP_GLOBAL_SELECTOR
import engine.singbox.runtime.SingBoxProxiesState
import engine.singbox.runtime.SingBoxTrafficSample
import engine.singbox.runtime.SingBoxTrafficState
import features.home.HomeControllerState
import features.home.HomeModeRuntimeAction
import features.home.HomeMonitoringOverviewState
import features.home.HomeNetworkActivityState
import features.home.HomeNetworkRowKind
import features.home.HomeNetworkSampleLimit
import features.home.HomeServiceStatus
import features.home.buildHomeControllerState
import features.home.buildHomeModeChange
import features.home.buildHomeModeOperationState
import features.home.buildHomeMonitoringOverviewState
import features.home.buildHomeNetworkActivityState
import features.home.buildNetworkActivityLineBands
import features.home.buildNetworkActivityWaveform
import features.home.formatHomeRuntimeBytes
import features.home.homeFocusTone
import features.monitoring.MonitoringIntent
import features.monitoring.ObserveMonitoring
import features.monitoring.resource.ProcessStatsSourceKind
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch
import ui.components.AsteriskExpressiveCard
import ui.components.AsteriskFocusSurface
import ui.components.AsteriskPageCard
import ui.components.AsteriskScaffold
import ui.components.AsteriskSegmentItem
import ui.components.AsteriskSegmentedControl
import ui.components.AsteriskTopAppBar
import ui.charts.DottedWaveformDotRadius
import ui.charts.drawDottedWaveform
import ui.charts.drawSmoothAreaLine
import ui.charts.rememberDottedWaveformRamp
import ui.layout.pageContentPaddingWithCutout
import ui.layout.pageListPadding
import ui.theme.AsteriskShapeTokens
import ui.theme.ExpressiveShapeRole
import ui.theme.FocusDensity
import utils.toReadableBytes
import kotlin.time.Duration.Companion.milliseconds
import app.navigation.Route as AppRoute
import ui.icons.AsteriskIcons as Icons
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import app.LocalHomeServiceControl
import features.home.formatHomeServiceUptime
import kotlinx.coroutines.delay

private data class HomeTrafficPresentation(
    val traffic: SingBoxTrafficState,
    val samples: List<SingBoxTrafficSample>,
)

private fun AppServices.homeTrafficPresentationSnapshot(): HomeTrafficPresentation {
    val runtimeState = singBoxRuntime.state.value
    return HomeTrafficPresentation(
        traffic = runtimeState.traffic,
        samples = singBoxRuntime.trafficHistorySnapshot(HomeNetworkSampleLimit),
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
                    samples = services.singBoxRuntime.trafficHistorySnapshot(HomeNetworkSampleLimit),
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
    val listState = rememberLazyListState()
    ObserveMonitoring(MonitoringIntent.Home)
    val serviceControl = LocalHomeServiceControl.current
    val serviceOperationInProgress = serviceControl.serviceOperationInProgress
    val modeOperationInProgress = serviceControl.modeOperationInProgress
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

    val modeFailedMessage = stringResource(R.string.home_mode_change_failed)
    val mainDestinationState = LocalMainDestinationState.current

    fun changeMode(mode: Int) {
        if (serviceControl.busy) return
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
        if (modeChange.runtimeAction != HomeModeRuntimeAction.None) {
            val operationState = buildHomeModeOperationState(modeChange.runtimeAction)
            serviceControl.modeOperationInProgress = operationState.modeOperationInProgress
            services.appScope.launch {
                try {
                    val failure = when (modeChange.runtimeAction) {
                        HomeModeRuntimeAction.None -> null
                        HomeModeRuntimeAction.PatchRuntime ->
                            services.singBoxRuntime.patchMode(modeChange.runtimeAppState).exceptionOrNull()
                    }
                    failure?.let { error ->
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
                } finally {
                    serviceControl.modeOperationInProgress = false
                }
            }
        }
    }

    AsteriskScaffold(
        topBar = {
            AsteriskTopAppBar(
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
                    serviceOperationInProgress = serviceOperationInProgress,
                    modeOperationInProgress = modeOperationInProgress,
                    onModeSelected = ::changeMode,
                    onOpenProxies = {
                        mainDestinationState?.select(MainDestination.Proxies)
                    },
                )
            }
            item("network_activity") {
                NetworkActivityCard(
                    networkActivityState = networkActivityState,
                    chartStyle = appState.networkActivityStyle,
                )
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
                        summaryMaxLines = 1,
                        metrics = {
                            TrafficMetric(
                                icon = Icons.Rounded.Upload,
                                label = stringResource(R.string.home_accumulated_upload),
                                value = formatHomeRuntimeBytes(networkActivityState.accumulatedUploadBytes),
                            )
                            TrafficMetric(
                                icon = Icons.Rounded.Download,
                                label = stringResource(R.string.home_accumulated_download),
                                value = formatHomeRuntimeBytes(networkActivityState.accumulatedDownloadBytes),
                            )
                        },
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
    serviceOperationInProgress: Boolean,
    modeOperationInProgress: Boolean,
    onModeSelected: (Int) -> Unit,
    onOpenProxies: () -> Unit,
) {
    val uptime = rememberServiceUptimeText()
    val singBoxRuntime = LocalAppServices.current.singBoxRuntime
    // Only the proxy snapshot matters here; the unsampled flow pushes a
    // traffic sample every second and would recompose the whole hero card.
    val runtimeProxies by remember(singBoxRuntime) {
        singBoxRuntime.state
            .map { state -> state.proxies }
            .distinctUntilChanged()
    }.collectAsState(initial = SingBoxProxiesState())
    val currentOutbound = remember(runtimeProxies, controllerState.singBoxMode) {
        if (controllerState.singBoxMode == SingBoxModeDirect) {
            null
        } else {
            activeSingBoxNodeTag(runtimeProxies)
        }
    }
    val appState by LocalAppStateStore.current.collectAppState()
    // The runtime reports managed tags (outbound_7_...), so map the active one
    // back to the alias the user gave the node.
    val currentOutboundAlias = remember(currentOutbound, appState) {
        when (currentOutbound) {
            APP_DIRECT_OUTBOUND -> null
            APP_GLOBAL_SELECTOR -> null
            else -> managedOutboundAlias(appState, currentOutbound)
        }
    }
    val directLabel = stringResource(R.string.routing_direct)
    val globalLabel = stringResource(R.string.routing_global)
    val outboundLabel = when {
        controllerState.singBoxMode == SingBoxModeDirect ->
            stringResource(R.string.sing_box_mode_direct)
        currentOutbound == APP_DIRECT_OUTBOUND -> directLabel
        currentOutbound == APP_GLOBAL_SELECTOR -> globalLabel
        !currentOutboundAlias.isNullOrBlank() -> currentOutboundAlias
        else -> stringResource(R.string.home_current_outbound_none)
    }
    // The runtime line keeps its slot when the service is down, so the status
    // card never changes height between running and stopped states.
    val runtimeText = uptime ?: HomeServiceUptimePlaceholder
    val serviceEnabled = controllerState.serviceStatus == HomeServiceStatus.Enabled
    // Stopped uses the error scheme so the hero reads as "needs attention".
    val heroContainer = if (serviceEnabled) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.error
    }
    val heroContent = if (serviceEnabled) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onError
    }
    // The secondary layer is the base card: the accent card sits inside it and
    // the strip below that inner card is the "chin" holding the mode switch.
    Surface(
        modifier = HomeContentModifier,
        shape = RoundedCornerShape(HomeChinOuterRadius),
        color = homeChinBaseColor(),
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(HomeChinInset),
        ) {
            AsteriskFocusSurface(
                title = if (controllerState.serviceStatus == HomeServiceStatus.Enabled) {
                    stringResource(R.string.home_service_enabled)
                } else {
                    stringResource(R.string.home_service_disabled)
                },
                modifier = Modifier.fillMaxWidth(),
                density = FocusDensity.Large,
                tone = homeFocusTone(controllerState.serviceStatus),
                // The runtime now sits where the mode label used to be: right under
                // the service status line.
                summary = runtimeText,
                // Monospace keeps the --:--:-- placeholder exactly as wide as the
                // running clock, so the card never resizes when the service starts.
                summaryStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                summaryContentDescription = uptime?.let { stringResource(R.string.home_service_uptime, it) }
            ?: stringResource(R.string.home_service_disabled),
                stateIcon = if (serviceEnabled) Icons.Rounded.VpnLock else Icons.Rounded.PublicOff,
                // The inner card is the accent one, matching the accent colour
                // picked in Settings > Personalization.
                containerColor = heroContainer,
                contentColor = heroContent,
                stateIconTint = heroContent,
                // Inset by exactly the radius difference, so the gap to the
                // base card stays even along the straight edges and around the
                // corner arcs alike.
                shapeOverride = RoundedCornerShape(HomeChinInnerRadius),
                primaryAction = {
                    ServiceModeChip(
                        modeLabel = runModeLabel(controllerState.runMode),
                        // On the error card a translucent chip washes out, so the
                        // tag inverts instead: onError fill, error-coloured text.
                        containerColor = if (serviceEnabled) {
                            MaterialTheme.colorScheme.secondaryContainer
                        } else {
                            MaterialTheme.colorScheme.onError
                        },
                        contentColor = if (serviceEnabled) {
                            MaterialTheme.colorScheme.onSecondaryContainer
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                    )
                },
                forcePrimaryActionStacked = shouldStackHomeServiceRuntimeSummary(),
            )
            // Current node: caption + alias, no surface of its own.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(onClick = onOpenProxies)
                    .padding(horizontal = 4.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Dns,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.home_current_node_title),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                    Text(
                        text = outboundLabel,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    // Same side edges as the accent card, so every layer lines
                    // up; the strip only adds the vertical breathing room.
                    .padding(
                        start = 0.dp,
                        end = 0.dp,
                        top = HomeChinStripPadding,
                        bottom = 0.dp,
                    ),
            ) {
                AsteriskSegmentedControl(
                    items = homeModeOptions().map { option ->
                        AsteriskSegmentItem(value = option.mode, label = option.label)
                    },
                    selectedValue = controllerState.singBoxMode,
                    onSelected = onModeSelected,
                    enabled = !serviceOperationInProgress && !modeOperationInProgress,
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    checkedContainerColor = MaterialTheme.colorScheme.primary,
                    checkedContentColor = MaterialTheme.colorScheme.onPrimary,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 5.dp),
                    textStyle = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}

@Composable
private fun homeChinBaseColor(): Color = lerp(
    MaterialTheme.colorScheme.secondaryContainer,
    MaterialTheme.colorScheme.surface,
    0.55f,
)

@Composable
private fun NetworkActivityCard(
    networkActivityState: HomeNetworkActivityState,
    chartStyle: Int,
) {
    // The card wraps its content and only the plot has a fixed height, so a large font scale grows
    // the card instead of squeezing the chart away.
    AsteriskPageCard(modifier = HomeContentModifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
            ) {
                Text(
                    text = stringResource(R.string.home_network_activity),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.width(12.dp))
                // Live speeds keep their heading size and sit side by side in the
                // card's top-right corner; the plot below spans the full width.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    NetworkSpeedValue(
                        icon = Icons.Rounded.ArrowUpward,
                        iconDescription = stringResource(R.string.monitor_connections_upload),
                        tint = MaterialTheme.colorScheme.tertiary,
                        value = formatHomeSpeed(networkActivityState.uploadBytesPerSecond),
                    )
                    Spacer(Modifier.width(NetworkSpeedPairSpacing))
                    NetworkSpeedValue(
                        icon = Icons.Rounded.ArrowDownward,
                        iconDescription = stringResource(R.string.monitor_connections_download),
                        tint = MaterialTheme.colorScheme.primary,
                        value = formatHomeSpeed(networkActivityState.downloadBytesPerSecond),
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            if (networkActivityState.hasNetworkSamples) {
                val chartModifier = Modifier
                    .fillMaxWidth()
                    .height(NetworkActivityChartHeight)
                if (chartStyle == NetworkActivityStyleLines) {
                    NetworkActivityLineChart(
                        state = networkActivityState,
                        modifier = chartModifier,
                    )
                } else {
                    NetworkActivityChart(
                        state = networkActivityState,
                        modifier = chartModifier,
                    )
                }
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(NetworkActivityChartHeight),
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
private fun NetworkActivityAxisLabel(
    scaleBytes: Long,
    modifier: Modifier = Modifier,
) {
    if (scaleBytes <= 0L) return
    Text(
        text = formatHomeSpeed(scaleBytes),
        modifier = modifier.height(NetworkActivityAxisLabelHeight),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Medium,
        lineHeight = 14.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
    )
}

/**
 * Line variant of the network activity chart: download and upload are drawn as two smooth curves on
 * one shared scale (each taller than the other by exactly their ratio, since both are bytes per
 * second), and each curve carries a decorative gradient down to the baseline.
 */
@Composable
private fun NetworkActivityLineChart(
    state: HomeNetworkActivityState,
    modifier: Modifier = Modifier,
) {
    val samples = state.networkSamples
    val bands = remember(samples) { buildNetworkActivityLineBands(samples, NetworkActivitySlotCount) }
    val downloadColor = MaterialTheme.colorScheme.primary
    val uploadColor = MaterialTheme.colorScheme.tertiary
    Column(modifier = modifier) {
        // Both curves share one scale, so the axis carries one label: the top of that scale.
        // It gets a thin row of its own so the plot keeps the full card width.
        NetworkActivityAxisLabel(
            scaleBytes = bands.scaleBytes,
            modifier = Modifier.align(Alignment.End),
        )
        Canvas(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            if (bands.sampleCount == 0) return@Canvas
            val strokeWidth = NetworkActivityAreaStrokeWidth.toPx()
            drawSmoothAreaLine(values = bands.downloadEdge, color = downloadColor, strokeWidth = strokeWidth)
            drawSmoothAreaLine(values = bands.uploadEdge, color = uploadColor, strokeWidth = strokeWidth)
        }
    }
}

@Composable
private fun NetworkSpeedValue(
    icon: ImageVector,
    iconDescription: String,
    tint: Color,
    value: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = iconDescription,
            tint = tint,
            modifier = Modifier.size(NetworkSpeedIconSize),
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun NetworkActivityChart(
    state: HomeNetworkActivityState,
    modifier: Modifier = Modifier,
) {
    val samples = state.networkSamples
    val waveform = remember(samples) { buildNetworkActivityWaveform(samples, NetworkActivitySlotCount) }
    val downloadRamp = rememberDottedWaveformRamp(MaterialTheme.colorScheme.primary)
    val uploadRamp = rememberDottedWaveformRamp(MaterialTheme.colorScheme.tertiary)
    Column(modifier = modifier) {
        NetworkActivityAxisLabel(
            scaleBytes = waveform.downloadScaleBytes,
            modifier = Modifier.align(Alignment.End),
        )
        Canvas(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            if (waveform.sampleCount == 0) return@Canvas
            val dotRadius = DottedWaveformDotRadius.toPx()
            drawDottedWaveform(
                amplitudes = waveform.download,
                slotCount = NetworkActivitySlotCount,
                dotRadius = dotRadius,
                direction = NetworkActivityDownloadDirection,
                ramp = downloadRamp,
            )
            drawDottedWaveform(
                amplitudes = waveform.upload,
                slotCount = NetworkActivitySlotCount,
                dotRadius = dotRadius,
                direction = NetworkActivityUploadDirection,
                ramp = uploadRamp,
            )
        }
        // The far edge of each half owns its own scale, so a slow upload never
        // inherits the download range.
        NetworkActivityAxisLabel(
            scaleBytes = waveform.uploadScaleBytes,
            modifier = Modifier.align(Alignment.End),
        )
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
    summaryMaxLines: Int = 2,
    metrics: (@Composable RowScope.() -> Unit)? = null,
) {
    AsteriskExpressiveCard(
        onClick = onClick,
        modifier = modifier.height(148.dp),
        role = if (prominent) ExpressiveShapeRole.GroupLarge else ExpressiveShapeRole.ContentCard,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Surface(
                modifier = Modifier.size(42.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shape = AsteriskShapeTokens.SmallContainer,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = summaryMaxLines,
                    overflow = TextOverflow.Ellipsis,
                )
                if (metrics != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        content = metrics,
                    )
                }
            }
        }
    }
}

@Composable
private fun TrafficMetric(
    icon: ImageVector,
    label: String,
    value: String,
) {
    Row(
        modifier = Modifier.semantics(mergeDescendants = true) {
            contentDescription = "$label $value"
        },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(13.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
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
            RunModeEbpf -> R.string.settings_run_mode_ebpf
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

private const val HomeServiceUptimePlaceholder = "--:--:--"

// The x axis keeps this many fixed columns no matter how many samples are buffered; the newest
// sample sits in the rightmost column. It mirrors the sample limit of the home traffic state.
// The lattice pitch doubles as the vertical step, so a wide card would only get a few rows; this
// keeps rows at 2.6 dot diameters instead, which is fine enough to read a burst.
private const val NetworkActivityVerticalPitchScale = 2.6f

private const val NetworkActivitySlotCount = HomeNetworkSampleLimit

private val NetworkActivityChartHeight = 120.dp

/** One thin row per axis label; the plot keeps everything else. */
private val NetworkActivityAxisLabelHeight = 14.dp
private val NetworkSpeedIconSize = 18.dp
private val NetworkSpeedPairSpacing = 14.dp
private val NetworkActivityAreaStrokeWidth = 2.dp

// Download grows upwards from the middle, upload grows downwards; both share the same x positions.
private const val NetworkActivityDownloadDirection = -1f
private const val NetworkActivityUploadDirection = 1f

private val HomeContentModifier = Modifier
    .fillMaxWidth()
    .widthIn(max = 840.dp)

// The secondary base card, the accent card nested inside it, and the inset
// that leaves the chin strip visible below the inner card.
private val HomeChinOuterRadius = 28.dp
private val HomeChinInnerRadius = 22.dp
// Equal to outer - inner radius, so the ring between the two cards is even
// along the edges and around the rounded corners.
private val HomeChinInset = 6.dp
private val HomeChinStripPadding = 6.dp

@Composable
private fun shouldStackHomeServiceRuntimeSummary(): Boolean =
    LocalWindowInfo.current.containerSize.width / LocalDensity.current.density < 360f

@Composable
private fun rememberServiceUptimeText(): String? {
    val appState by LocalAppStateStore.current.collectAppState()
    val services = LocalAppServices.current
    val uptimeMillis by produceState<Long?>(null, appState.proxyRunning, appState.runMode) {
        value = null
        if (!appState.proxyRunning) return@produceState
        while (true) {
            value = if (appState.runMode == RunModeVpnService) {
                val resource = services.monitoring.state.value.resource
                resource.uptimeMillis
                    .takeIf { resource.source == ProcessStatsSourceKind.VpnProcess }
                    ?.let { it + (SystemClock.elapsedRealtime() - resource.uptimeMeasuredAtElapsedMillis).coerceAtLeast(0L) }
            } else {
                services.singBoxRuntime.state.value.serviceStartedAtMillis
                    .takeIf { it > 0L }
                    ?.let { (System.currentTimeMillis() - it).coerceAtLeast(0L) }
            }
            delay(1_000L.milliseconds)
        }
    }
    if (!appState.proxyRunning) return null
    return formatHomeServiceUptime(uptimeMillis)
}

@Composable
private fun ServiceModeChip(
    modeLabel: String,
    containerColor: Color,
    contentColor: Color,
) {
    // Same secondaryContainer pairing KernelSU-Next gives its GKI/non-GKI tag.
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = containerColor,
        contentColor = contentColor,
    ) {
        Text(
            text = modeLabel,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
        )
    }
}
