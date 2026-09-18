// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    kotlinx.coroutines.FlowPreview::class,
)

package features.singbox

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.AppServices
import app.AppState
import app.LocalAppServices
import app.LocalAppStateStore
import app.LocalIsWideScreen
import app.LocalNavigator
import app.LocalUpdateAppState
import app.R
import app.collectAppState
import app.modes.RunModeBpf2Socks
import app.modes.RunModeEbpf
import app.modes.RunModeTproxy
import app.modes.RunModeTun
import app.modes.RunModeTun2Socks
import app.modes.SingBoxModeDirect
import app.modes.SingBoxModeGlobal
import app.modes.SingBoxModeRule
import app.navigation.Route as AppRoute
import engine.proxy.ProxyServiceResult
import engine.singbox.runtime.SingBoxTrafficSample
import engine.singbox.runtime.SingBoxTrafficState
import features.home.BindHomeDashboardController
import features.home.HomeCardInstance
import features.home.HomeCardKind
import features.home.HomeCardSize
import features.home.HomeControllerState
import features.home.HomeDashboardController
import features.home.HomeDashboardLayout
import features.home.HomeMonitoringOverviewState
import features.home.HomeNetworkActivityState
import features.home.HomeNetworkRowKind
import features.home.HomeServiceStatus
import features.home.HomeModeRuntimeAction
import features.home.LocalHomeShellSheetState
import features.home.allowsResize
import features.home.buildHomeControllerState
import features.home.buildHomeModeChange
import features.home.buildHomeModeOperationState
import features.home.buildHomeMonitoringOverviewState
import features.home.buildHomeNetworkActivityState
import features.home.defaultUsageGroupId
import features.home.findById
import features.home.formatHomeRuntimeBytes
import features.home.hiddenCards
import features.home.homeFocusTone
import features.home.rememberHomeDashboardController
import features.home.visibleCards
import features.monitoring.MonitoringIntent
import features.monitoring.ObserveMonitoring
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
import ui.icons.AsteriskIcons as Icons
import ui.layout.pageContentPaddingWithCutout
import ui.layout.pageListPadding
import ui.theme.AsteriskShapeTokens
import ui.theme.ExpressiveShapeRole
import ui.theme.FocusDensity
import utils.toReadableBytes
import kotlin.time.Duration.Companion.milliseconds

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

@Composable
fun HomeDashboardPage(
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
    }.collectAsState(
        initial = remember(services.monitoring) {
            buildHomeMonitoringOverviewState(services.monitoring.state.value)
        },
    )
    val navigator = LocalNavigator.current
    val isWideScreen = LocalIsWideScreen.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val controller = rememberHomeDashboardController()
    BindHomeDashboardController(controller)
    ObserveMonitoring(MonitoringIntent.Home)
    var serviceOperationInProgress by remember { mutableStateOf(false) }
    var modeOperationInProgress by remember { mutableStateOf(false) }
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
    val latestAppState by rememberUpdatedState(appState)
    val showDiscardDialog = remember { mutableStateOf(false) }
    val homeShellSheets = LocalHomeShellSheetState.current

    val startFailedMessage = stringResource(R.string.sing_box_dashboard_start_failed)
    val stopFailedMessage = stringResource(R.string.sing_box_dashboard_stop_failed)
    val serviceStartedMessage = stringResource(R.string.proxy_service_started)
    val serviceStoppedMessage = stringResource(R.string.proxy_service_stopped)
    val modeFailedMessage = stringResource(R.string.home_mode_change_failed)

    val visibleCards = controller.layout.visibleCards()
    val hiddenCardsList = controller.layout.hiddenCards()

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
        if (serviceOperationInProgress || modeOperationInProgress) return
        val stateSnapshot = latestAppState
        val wasRunning = stateSnapshot.proxyRunning
        serviceOperationInProgress = true
        val operationJob = services.appScope.launch {
            handleProxyServiceResult(services.proxyServiceUseCase.toggle(stateSnapshot), wasRunning)
        }
        scope.launch {
            try {
                operationJob.join()
            } finally {
                serviceOperationInProgress = false
            }
        }
    }

    fun changeMode(mode: Int) {
        if (serviceOperationInProgress || modeOperationInProgress) return
        val stateSnapshot = latestAppState
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
            serviceOperationInProgress = operationState.serviceOperationInProgress
            modeOperationInProgress = operationState.modeOperationInProgress
            val operationJob = services.appScope.launch {
                val failure = when (modeChange.runtimeAction) {
                    HomeModeRuntimeAction.None -> null
                    HomeModeRuntimeAction.PatchRuntime ->
                        services.singBoxRuntime.patchMode(modeChange.runtimeAppState).exceptionOrNull()
                    HomeModeRuntimeAction.RestartService ->
                        when (val result = services.proxyServiceUseCase.restart(modeChange.runtimeAppState)) {
                            is ProxyServiceResult.Success -> {
                                updateAppState { state ->
                                    state.copy(
                                        proxyRunning = result.proxyRunning,
                                        localProxyPort = result.appState?.localProxyPort ?: state.localProxyPort,
                                        singBoxControlPort =
                                            result.appState?.singBoxControlPort ?: state.singBoxControlPort,
                                    )
                                }
                                null
                            }
                            is ProxyServiceResult.Failed -> result.error
                        }
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
            }
            scope.launch {
                try {
                    operationJob.join()
                } finally {
                    serviceOperationInProgress = false
                    modeOperationInProgress = false
                }
            }
        }
    }

    AsteriskScaffold(
        topBar = {
            AsteriskTopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    if (controller.isEditing) {
                        IconButton(onClick = { showDiscardDialog.value = true }) {
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = stringResource(R.string.home_dashboard_discard),
                            )
                        }
                        IconButton(onClick = { controller.commit() }) {
                            Icon(
                                imageVector = Icons.Rounded.Check,
                                contentDescription = stringResource(R.string.home_dashboard_done),
                            )
                        }
                    } else {
                        IconButton(onClick = { controller.beginEdit() }) {
                            Icon(
                                imageVector = Icons.Rounded.Edit,
                                contentDescription = stringResource(R.string.home_dashboard_edit),
                            )
                        }
                    }
                },
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
            items(
                items = visibleCards,
                key = { it.id },
                contentType = { it.kind.name + ":" + it.size.name },
            ) { card ->
                key(card.id, card.kind, card.size) {
                    HomeDashboardCardSlot(
                        card = card,
                        appState = appState,
                        controller = controller,
                        networkActivityState = networkActivityState,
                        monitoringOverviewState = monitoringOverviewState,
                        controllerState = controllerState,
                        serviceOperationInProgress = serviceOperationInProgress,
                        modeOperationInProgress = modeOperationInProgress,
                        isEditing = controller.isEditing,
                        onToggleService = ::toggleService,
                        onModeSelected = ::changeMode,
                        onOpenGroups = { navigator.push(AppRoute.OutboundGroupCreate) },
                        onOpenMonitor = { route -> navigator.push(route) },
                        onOpenNetworkQualityTest = { homeShellSheets.openNetworkQualityTest() },
                    )
                }
            }
            if (controller.isEditing) {
                item(key = "hidden-divider") {
                    HomeHiddenSectionDivider()
                }
                if (hiddenCardsList.isEmpty()) {
                    item(key = "hidden-empty") {
                        HomeHiddenEmptyState()
                    }
                } else {
                    items(
                        items = hiddenCardsList,
                        key = { "hidden:" + it.id },
                        contentType = { "hidden:" + it.kind.name + ":" + it.size.name },
                    ) { card ->
                        key(card.id, card.kind, card.size) {
                            HomeDashboardCardSlot(
                                card = card,
                                appState = appState,
                                controller = controller,
                                networkActivityState = networkActivityState,
                                monitoringOverviewState = monitoringOverviewState,
                                controllerState = controllerState,
                                serviceOperationInProgress = serviceOperationInProgress,
                                modeOperationInProgress = modeOperationInProgress,
                                isEditing = controller.isEditing,
                                onToggleService = ::toggleService,
                                onModeSelected = ::changeMode,
                                onOpenGroups = { navigator.push(AppRoute.OutboundGroupCreate) },
                                onOpenMonitor = { route -> navigator.push(route) },
                                onOpenNetworkQualityTest = { homeShellSheets.openNetworkQualityTest() },
                            )
                        }
                    }
                }
                item(key = "add-card") {
                    HomeAddCardRow(
                        enabledGroups = appState.outboundGroups.map { it.id to it.name },
                        defaultGroupId = defaultUsageGroupId(appState),
                        onAddUsage = { groupId -> controller.addUsage(groupId) },
                        onAddStacked = { controller.addStacked() },
                    )
                }
            }
        }
    }

    if (showDiscardDialog.value) {
        HomeDiscardConfirmDialog(
            onConfirm = {
                showDiscardDialog.value = false
                controller.cancelEdit()
            },
            onCancel = { showDiscardDialog.value = false },
        )
    }
}

@Composable
private fun HomeHiddenSectionDivider() {
    Surface(
        modifier = HomeContentModifier,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = AsteriskShapeTokens.Pill,
    ) {
        Text(
            text = stringResource(R.string.home_dashboard_hidden_section),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun HomeHiddenEmptyState() {
    Surface(
        modifier = HomeContentModifier,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = AsteriskShapeTokens.PageCard,
    ) {
        Text(
            text = stringResource(R.string.home_dashboard_hidden_empty),
            modifier = Modifier.padding(20.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun HomeDashboardCardSlot(
    card: HomeCardInstance,
    appState: AppState,
    controller: HomeDashboardController,
    networkActivityState: HomeNetworkActivityState,
    monitoringOverviewState: HomeMonitoringOverviewState,
    controllerState: HomeControllerState,
    serviceOperationInProgress: Boolean,
    modeOperationInProgress: Boolean,
    isEditing: Boolean,
    onToggleService: () -> Unit,
    onModeSelected: (Int) -> Unit,
    onOpenGroups: () -> Unit,
    onOpenMonitor: (AppRoute) -> Unit,
    onOpenNetworkQualityTest: () -> Unit,
) {
    Box(modifier = HomeContentModifier) {
        val content: @Composable () -> Unit = {
            when (card.kind) {
                HomeCardKind.Controller -> HomeControllerCard(
                    controllerState = controllerState,
                    networkActivityState = networkActivityState,
                    serviceOperationInProgress = serviceOperationInProgress,
                    modeOperationInProgress = modeOperationInProgress,
                    onToggleService = onToggleService,
                    onModeSelected = onModeSelected,
                )
                HomeCardKind.NetworkActivity -> NetworkActivityCard(
                    state = networkActivityState,
                    size = card.size,
                )
                HomeCardKind.NetworkQualityTest -> NetworkQualityTestCard(
                    size = card.size,
                    onOpen = onOpenNetworkQualityTest,
                )
                HomeCardKind.Usage -> UsageCard(
                    card = card,
                    appState = appState,
                    size = card.size,
                    isEditing = isEditing,
                    onClick = onOpenGroups,
                    onPickGroup = { newGroupId ->
                        controller.updateConfig(card.id) { it.copy(groupId = newGroupId) }
                    },
                )
                HomeCardKind.Stacked -> StackedCard(
                    card = card,
                    layout = controller.layout,
                    appState = appState,
                    size = card.size,
                    isEditing = isEditing,
                    controller = controller,
                    onClickChild = { child ->
                        when (child.kind) {
                            HomeCardKind.NetworkQualityTest -> onOpenNetworkQualityTest()
                            HomeCardKind.Usage -> onOpenGroups()
                            HomeCardKind.MonitorResource -> onOpenMonitor(AppRoute.ResourceMonitor)
                            HomeCardKind.MonitorConnections -> onOpenMonitor(AppRoute.ConnectionsMonitor)
                            HomeCardKind.MonitorTraffic -> onOpenMonitor(AppRoute.TrafficMonitor)
                            HomeCardKind.MonitorNetwork -> onOpenMonitor(AppRoute.NetworkMonitor)
                            else -> Unit
                        }
                    },
                    onToggleAutoRotate = { value ->
                        controller.stackSetAutoRotate(card.id, value)
                    },
                    onRemoveChild = { childId -> controller.stackRemove(card.id, childId) },
                    onAddChild = { childId -> controller.stackInclude(card.id, childId) },
                )
                HomeCardKind.MonitorResource -> MonitoringEntryCard(
                    title = stringResource(R.string.home_monitor_resource),
                    summary = homeResourceSummary(monitoringOverviewState),
                    icon = Icons.Rounded.Memory,
                    onClick = { onOpenMonitor(AppRoute.ResourceMonitor) },
                )
                HomeCardKind.MonitorConnections -> MonitoringEntryCard(
                    title = stringResource(R.string.home_monitor_connections),
                    summary = monitoringOverviewState.activeConnectionCount?.let { count ->
                        pluralStringResource(R.plurals.home_connections_summary, count, count)
                    } ?: stringResource(R.string.home_value_unavailable),
                    icon = Icons.Rounded.Lan,
                    onClick = { onOpenMonitor(AppRoute.ConnectionsMonitor) },
                )
                HomeCardKind.MonitorTraffic -> MonitoringEntryCard(
                    title = stringResource(R.string.home_monitor_traffic),
                    summary = stringResource(
                        R.string.home_traffic_summary,
                        monitoringOverviewState.todayTrafficBytes.toReadableBytes(),
                    ),
                    icon = Icons.Rounded.DataUsage,
                    onClick = { onOpenMonitor(AppRoute.TrafficMonitor) },
                )
                HomeCardKind.MonitorNetwork -> MonitoringEntryCard(
                    title = stringResource(R.string.home_monitor_network),
                    summary = homeNetworkSummary(monitoringOverviewState),
                    icon = Icons.Rounded.Public,
                    prominent = true,
                    onClick = { onOpenMonitor(AppRoute.NetworkMonitor) },
                )
            }
        }
        if (isEditing) {
            CardEditChrome(
                card = card,
                controller = controller,
                content = content,
            )
        } else {
            content()
        }
    }
}

@Composable
private fun CardEditChrome(
    card: HomeCardInstance,
    controller: HomeDashboardController,
    content: @Composable () -> Unit,
) {
    val supportsResize = card.kind.allowsResize
    val showSettingsPen = card.kind == HomeCardKind.Usage || card.kind == HomeCardKind.Stacked
    var showGroupPicker by remember { mutableStateOf(false) }
    var showStackedManager by remember { mutableStateOf(false) }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        shape = AsteriskShapeTokens.PageCard,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column {
            content()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Icon(
                    imageVector = Icons.Rounded.DragIndicator,
                    contentDescription = stringResource(R.string.home_dashboard_drag_handle),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (showSettingsPen) {
                        IconButton(
                            onClick = {
                                if (card.kind == HomeCardKind.Usage) {
                                    showGroupPicker = true
                                } else {
                                    showStackedManager = true
                                }
                            },
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.EditNote,
                                contentDescription = stringResource(R.string.home_dashboard_card_settings),
                                tint = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                    if (supportsResize) {
                        IconButton(onClick = { controller.resize(card.id) }) {
                            val next = when (card.size) {
                                HomeCardSize.Small -> Icons.Rounded.ZoomInMap
                                HomeCardSize.Large -> Icons.Rounded.ZoomOutMap
                            }
                            val cd = when (card.size) {
                                HomeCardSize.Small -> stringResource(R.string.home_dashboard_resize_large)
                                HomeCardSize.Large -> stringResource(R.string.home_dashboard_resize_small)
                            }
                            Icon(
                                imageVector = next,
                                contentDescription = cd,
                                tint = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                    IconButton(onClick = { controller.hide(card.id) }) {
                        Icon(
                            imageVector = Icons.Rounded.Delete,
                            contentDescription = stringResource(R.string.home_dashboard_remove),
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
    }
    if (showGroupPicker) {
        HomeGroupPickerSheet(
            currentGroupId = card.config.groupId,
            onSelect = { groupId ->
                controller.updateConfig(card.id) { it.copy(groupId = groupId) }
                showGroupPicker = false
            },
            onDismiss = { showGroupPicker = false },
        )
    }
    if (showStackedManager) {
        HomeStackedManageSheet(
            card = card,
            controller = controller,
            onDismiss = { showStackedManager = false },
        )
    }
}

@Composable
private fun HomeAddCardRow(
    enabledGroups: List<Pair<Int, String>>,
    defaultGroupId: Int,
    onAddUsage: (Int) -> Unit,
    onAddStacked: () -> Unit,
) {
    var showGroupPicker by remember { mutableStateOf(false) }
    Surface(
        modifier = HomeContentModifier,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = AsteriskShapeTokens.PageCard,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.home_dashboard_add_card),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                AddCardButton(
                    label = stringResource(R.string.home_dashboard_add_usage),
                    icon = Icons.Rounded.PieChart,
                    enabled = true,
                    onClick = {
                        if (enabledGroups.isEmpty()) {
                            onAddUsage(0)
                        } else {
                            showGroupPicker = true
                        }
                    },
                )
                AddCardButton(
                    label = stringResource(R.string.home_dashboard_add_stacked),
                    icon = Icons.Rounded.Layers,
                    enabled = true,
                    onClick = onAddStacked,
                )
            }
        }
    }
    if (showGroupPicker) {
        HomeGroupPickerSheet(
            currentGroupId = defaultGroupId,
            onSelect = { groupId ->
                onAddUsage(groupId)
                showGroupPicker = false
            },
            onDismiss = { showGroupPicker = false },
        )
    }
}

@Composable
private fun AddCardButton(
    label: String,
    icon: ImageVector,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = AsteriskShapeTokens.Pill,
        color = if (enabled) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = if (enabled) MaterialTheme.colorScheme.onPrimaryContainer
        else MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(imageVector = icon, contentDescription = null)
            Text(text = label, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun HomeDiscardConfirmDialog(
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.home_dashboard_discard_confirm_title)) },
        text = { Text(stringResource(R.string.home_dashboard_discard_confirm_message)) },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.home_dashboard_discard_confirm_confirm))
            }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onCancel) {
                Text(stringResource(R.string.home_dashboard_discard_confirm_cancel))
            }
        },
    )
}

private const val HomeUiSampleIntervalMillis = 1_000L
private const val HomeNetworkHistoryLimit = 60

private val HomeContentModifier = Modifier
    .fillMaxWidth()
    .widthIn(max = 840.dp)

private val HomeCardMotion = tween<Float>(150)

// ===== Card implementations =====

@Composable
private fun HomeControllerCard(
    controllerState: HomeControllerState,
    networkActivityState: HomeNetworkActivityState,
    serviceOperationInProgress: Boolean,
    modeOperationInProgress: Boolean,
    onToggleService: () -> Unit,
    onModeSelected: (Int) -> Unit,
) {
    val serviceSwitchAlpha by animateFloatAsState(
        targetValue = if (serviceOperationInProgress) 0f else 1f,
        animationSpec = HomeCardMotion,
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
                    enabled = !serviceOperationInProgress,
                )
                AnimatedVisibility(
                    visible = serviceOperationInProgress,
                    enter = fadeIn(HomeCardMotion),
                    exit = fadeOut(HomeCardMotion),
                    label = "home-service-loading",
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
                }
            }
        },
        keepPrimaryActionInline = true,
    ) {
        Box(modifier = Modifier.offset(y = 10.dp)) {
            AsteriskSegmentedControl(
                items = homeModeOptions().map { option ->
                    AsteriskSegmentItem(value = option.mode, label = option.label)
                },
                selectedValue = controllerState.singBoxMode,
                onSelected = onModeSelected,
                enabled = !serviceOperationInProgress && !modeOperationInProgress,
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
    Row(
        modifier = modifier.offset(y = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(28.dp),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(24.dp).offset(y = 0.5.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 14.dp),
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
private fun NetworkActivityCard(
    state: HomeNetworkActivityState,
    size: HomeCardSize,
) {
    val height = when (size) {
        HomeCardSize.Large -> 180.dp
        HomeCardSize.Small -> 96.dp
    }
    AsteriskPageCard(modifier = HomeContentModifier.height(height)) {
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
                        formatHomeSpeed(state.uploadBytesPerSecond),
                        formatHomeSpeed(state.downloadBytesPerSecond),
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(12.dp))
            if (size == HomeCardSize.Large) {
                if (state.hasNetworkSamples) {
                    NetworkActivityChart(
                        state = state,
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
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
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
        HomeModeOption(SingBoxModeRule, stringResource(R.string.sing_box_mode_rule)),
        HomeModeOption(SingBoxModeGlobal, stringResource(R.string.sing_box_mode_global)),
        HomeModeOption(SingBoxModeDirect, stringResource(R.string.sing_box_mode_direct)),
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

private data class HomeModeOption(val mode: Int, val label: String)

@Composable
private fun HomeGroupPickerSheet(
    currentGroupId: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val stateStore = LocalAppStateStore.current
    val state by stateStore.collectAppState()
    val groups = state.outboundGroups
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.home_dashboard_add_group_picker_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(8.dp))
            if (groups.isEmpty()) {
                Text(
                    text = stringResource(R.string.home_dashboard_add_group_unavailable),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                groups.forEach { option ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        androidx.compose.material3.RadioButton(
                            selected = option.id == currentGroupId,
                            onClick = { onSelect(option.id) },
                        )
                        Spacer(Modifier.size(8.dp))
                        Text(text = option.name, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun HomeStackedManageSheet(
    card: HomeCardInstance,
    controller: HomeDashboardController,
    onDismiss: () -> Unit,
) {
    val candidates = controller.layout.cards
        .filter { it.id != card.id && it.kind != HomeCardKind.Stacked }
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.home_dashboard_stacked_manage_pages),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(8.dp))
            if (card.config.stackedCardIds.isEmpty()) {
                Text(
                    text = stringResource(R.string.home_dashboard_stacked_empty),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            card.config.stackedCardIds.forEach { childId ->
                val child = controller.layout.findById(childId)
                val title = child?.let { cardTitle(it) } ?: childId.take(8)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(text = title, modifier = Modifier.weight(1f))
                    androidx.compose.material3.TextButton(
                        onClick = { controller.stackRemove(card.id, childId) },
                    ) { Text(stringResource(R.string.home_dashboard_stacked_remove_page)) }
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.home_dashboard_stacked_add_page),
                style = MaterialTheme.typography.titleSmall,
            )
            candidates.forEach { child ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(text = cardTitle(child), modifier = Modifier.weight(1f))
                    androidx.compose.material3.TextButton(
                        onClick = { controller.stackInclude(card.id, child.id) },
                    ) { Text(stringResource(R.string.home_dashboard_stacked_add_page)) }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}
