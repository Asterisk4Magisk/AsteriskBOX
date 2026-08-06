// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

@file:OptIn(ExperimentalFoundationApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)

package features.singbox

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import ui.icons.AsteriskIcons as Icons
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.AppServices
import app.LocalAppServices
import app.LocalAppStateStore
import app.LocalIsWideScreen
import app.LocalUpdateAppState
import org.asterisk.zcc.abox.R
import app.collectAppState
import app.isManagedSingBoxTag
import app.managedOutboundGroupSelectorTag
import app.selectableManagedOutbounds
import app.withSelectorSelection
import app.modes.SingBoxProxyLayoutAuto
import app.modes.SingBoxProxyLayoutDouble
import app.modes.SingBoxProxyLayoutMultiple
import app.modes.SingBoxProxyLayoutSingle
import app.modes.SingBoxProxySortDefault
import app.modes.SingBoxProxySortDelay
import app.modes.SingBoxProxySortName
import ui.components.AsteriskFilterChip
import ui.components.AsteriskInfoChip
import ui.components.AsteriskPinnedSearchArea
import ui.components.AsteriskSelectionCard
import ui.components.localizedLabel
import engine.singbox.config.APP_GLOBAL_SELECTOR
import engine.singbox.runtime.SingBoxProxiesState
import engine.singbox.runtime.SingBoxProxyGroup
import engine.singbox.runtime.SingBoxProxyNode
import engine.singbox.runtime.SingBoxRuntimeState
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import ui.layout.pageContentPaddingWithCutout
import ui.layout.pageListPadding
import ui.theme.AsteriskMotion

private data class SingBoxProxyPageRuntimeState(
    val proxies: SingBoxProxiesState,
    val proxiesRefreshing: Boolean,
    val delayTestingTarget: String?,
    val delayTestingBaselines: Map<String, Long>,
    val delayFailedNodes: Set<String>,
    val lastError: String,
)

private fun SingBoxRuntimeState.toProxyPageRuntimeState() = SingBoxProxyPageRuntimeState(
    proxies = proxies,
    proxiesRefreshing = proxiesRefreshing,
    delayTestingTarget = delayTestingTarget,
    delayTestingBaselines = delayTestingBaselines,
    delayFailedNodes = delayFailureBaselines.keys,
    lastError = lastError,
)

private fun AppServices.singBoxProxyPageRuntimeSnapshot() =
    singBoxRuntime.state.value.toProxyPageRuntimeState()

@Composable
fun SingBoxProxyPage(
    padding: PaddingValues,
) {
    val isWideScreen = LocalIsWideScreen.current
    val appState by LocalAppStateStore.current.collectAppState()
    val updateAppState = LocalUpdateAppState.current
    val services = LocalAppServices.current
    val runtimeState by remember(services.singBoxRuntime) {
        services.singBoxRuntime.state
            .map(SingBoxRuntimeState::toProxyPageRuntimeState)
            .distinctUntilChanged()
    }.collectAsState(initial = services.singBoxProxyPageRuntimeSnapshot())
    val scope = rememberCoroutineScope()
    val tipNotifier = services.tipNotifier
    val runtimeUnavailableMessage = stringResource(R.string.sing_box_proxies_runtime_unavailable)
    val selectFailedMessage = stringResource(R.string.sing_box_proxies_select_failed)
    val delayFailedMessage = stringResource(R.string.sing_box_proxies_delay_failed)
    val delayDoneMessage = stringResource(R.string.sing_box_proxies_delay_done)

    LaunchedEffect(
        services.singBoxRuntime,
        appState.proxyRunning,
        appState.runMode,
    ) {
        if (appState.proxyRunning) {
            services.singBoxRuntime.refreshProxies(appState)
        }
    }
    val runtimeProxies = runtimeState.proxies
    val runtimeHasProxySnapshot = runtimeProxies.groups.isNotEmpty()
    val contentState = resolveSingBoxProxyContentState(
        serviceRunning = appState.proxyRunning,
        hasProxyGroups = runtimeHasProxySnapshot,
        refreshing = runtimeState.proxiesRefreshing,
    )
    val proxies = if (contentState == SingBoxProxyContentState.ServiceStopped) {
        SingBoxProxiesState()
    } else {
        runtimeProxies
    }
    val globalGroupName = stringResource(R.string.routing_global)
    val unavailableLabel = stringResource(R.string.common_unavailable)
    val outboundChoices = selectableManagedOutbounds(appState)
    val outboundDisplayNames = outboundChoices.associate { choice ->
        choice.tag to choice.localizedLabel()
    }
    val outboundDisplayNamesWithoutGroup = outboundChoices.associate { choice ->
        choice.tag to choice.localizedLabel(includeGroupName = false)
    }
    val managedGroupNames = appState.outboundGroups.associate { group ->
        managedOutboundGroupSelectorTag(group.id, group.name) to group.name
    }
    val visibleProxies = remember(
        proxies,
        managedGroupNames,
        globalGroupName,
        outboundDisplayNames,
        unavailableLabel,
    ) {
        val prioritized = prioritizeGlobalSingBoxProxyGroup(proxies)
        prioritized.copy(
            groups = prioritized.groups.map { group ->
                group.copy(
                    displayName = managedGroupNames[group.name]
                        ?: globalGroupName.takeIf { group.name == APP_GLOBAL_SELECTOR }
                        ?: outboundDisplayNames[group.name]
                        ?: group.name.visibleRuntimeName(unavailableLabel),
                )
            },
        )
    }
    val runtimeAvailable = contentState == SingBoxProxyContentState.Ready
    val runtimeErrorDetails = stringResource(
        R.string.common_error_details,
        runtimeUnavailableMessage,
        runtimeState.lastError,
    )
    val groupNames = visibleProxies.groups.map(SingBoxProxyGroup::name)
    var selectedGroupName by rememberSaveable { mutableStateOf(groupNames.firstOrNull().orEmpty()) }
    val resolvedSelectedGroupName = selectedGroupName.takeIf { groupName -> groupName in groupNames }
        ?: groupNames.firstOrNull().orEmpty()
    val testingTarget = runtimeState.delayTestingTarget
    var searchQuery by rememberSaveable { mutableStateOf("") }
    val selectedGroup = visibleProxies.groups.firstOrNull { group -> group.name == resolvedSelectedGroupName }
    val proxyLayout = resolveSingBoxProxyLayout(appState.singBoxProxyLayout, isWideScreen)
    val columns = resolveSingBoxProxyColumns(proxyLayout)
    var pendingSelections by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    val resolvedSelectedGroupIndex = groupNames.indexOf(resolvedSelectedGroupName).coerceAtLeast(0)
    val groupPagerState = key(groupNames) {
        rememberPagerState(
            initialPage = resolvedSelectedGroupIndex,
            pageCount = { groupNames.size.coerceAtLeast(1) },
        )
    }
    val pagerMotion = AsteriskMotion.spatial<Float>()
    val contentEffectsMotion = AsteriskMotion.effects<Float>()

    LaunchedEffect(groupNames) {
        pendingSelections = pendingSelections.filterKeys { groupName -> groupName in groupNames }
        if (selectedGroupName !in groupNames) {
            selectedGroupName = groupNames.firstOrNull().orEmpty()
        }
        val lastIndex = groupNames.lastIndex
        if (lastIndex >= 0 && groupPagerState.currentPage > lastIndex) {
            groupPagerState.scrollToPage(lastIndex)
        }
    }

    LaunchedEffect(visibleProxies.groups) {
        if (pendingSelections.isEmpty()) return@LaunchedEffect
        pendingSelections = pendingSelections.filter { (groupName, proxyName) ->
            val group = visibleProxies.groups.firstOrNull { item -> item.name == groupName } ?: return@filter false
            group.now != proxyName && proxyName in group.all
        }
    }

    LaunchedEffect(resolvedSelectedGroupName, groupNames) {
        val selectedIndex = groupNames.indexOf(resolvedSelectedGroupName)
        if (
            selectedIndex >= 0 &&
            !groupPagerState.isScrollInProgress &&
            groupPagerState.currentPage != selectedIndex
        ) {
            groupPagerState.animateScrollToPage(
                page = selectedIndex,
                animationSpec = pagerMotion,
            )
        }
    }

    LaunchedEffect(groupPagerState, groupNames) {
        snapshotFlow { groupPagerState.targetPage }
            .collect { page ->
                groupNames.getOrNull(page)?.let { groupName ->
                    if (selectedGroupName != groupName) {
                        selectedGroupName = groupName
                    }
                }
            }
    }

    fun requireRuntime(): Boolean {
        if (!runtimeAvailable) {
            val message = runtimeState.lastError.takeIf(String::isNotBlank)
                ?.let { runtimeErrorDetails }
                ?: runtimeUnavailableMessage
            scope.launch { tipNotifier.show(message) }
            return false
        }
        return true
    }

    fun selectProxy(group: SingBoxProxyGroup, node: SingBoxProxyNode) {
        if (!isSingBoxProxyGroupSelectable(group)) return
        if (!requireRuntime()) return
        val pendingProxyName = pendingSelections[group.name]
        if (pendingProxyName == node.name || (pendingProxyName == null && group.now == node.name)) return
        pendingSelections = pendingSelections + (group.name to node.name)
        scope.launch {
            services.singBoxRuntime.selectProxy(appState, group.name, node.name)
                .onSuccess {
                    if (isSingBoxProxySelectionPersistent(group)) {
                        updateAppState { state ->
                            state.withSelectorSelection(group.name, node.name)
                        }
                    }
                }
                .onFailure { error ->
                    if (pendingSelections[group.name] == node.name) {
                        pendingSelections = pendingSelections - group.name
                    }
                    tipNotifier.showError(error, selectFailedMessage)
                }
        }
    }

    fun testGroup(group: SingBoxProxyGroup) {
        if (!requireRuntime() || testingTarget != null) return
        val stateSnapshot = appState
        val groupName = group.name
        services.appScope.launch {
            services.singBoxRuntime.testGroupDelay(stateSnapshot, groupName)
                .onSuccess { tipNotifier.show(delayDoneMessage) }
                .onFailure { error -> tipNotifier.showError(error, delayFailedMessage) }
        }
    }

    Scaffold(
        topBar = {
            Column(modifier = Modifier.background(MaterialTheme.colorScheme.surface)) {
                TopAppBar(
                    title = { Text(stringResource(R.string.sing_box_proxies_title)) },
                    actions = {
                        AnimatedVisibility(
                            visible = contentState != SingBoxProxyContentState.ServiceStopped,
                            enter = AsteriskMotion.fadeEnter(contentEffectsMotion),
                            exit = AsteriskMotion.fadeExit(contentEffectsMotion),
                        ) {
                            SingBoxProxyOptionsMenu(
                                layout = appState.singBoxProxyLayout,
                                sort = resolveSingBoxProxySort(appState.singBoxProxySort),
                                onLayoutChange = { layout ->
                                    updateAppState { state ->
                                        state.copy(singBoxProxyLayout = layout)
                                    }
                                },
                                onSortChange = { sort ->
                                    updateAppState { state ->
                                        state.copy(singBoxProxySort = sort)
                                    }
                                },
                            )
                        }
                    },
                )
                AnimatedVisibility(
                    visible = contentState != SingBoxProxyContentState.ServiceStopped,
                    enter = AsteriskMotion.contentEnter(),
                    exit = AsteriskMotion.contentExit(),
                ) {
                    AsteriskPinnedSearchArea(
                        query = searchQuery,
                        onQueryChange = { searchQuery = it },
                        placeholder = stringResource(R.string.sing_box_proxies_search),
                        clearContentDescription = stringResource(R.string.common_clear),
                    ) {
                        if (visibleProxies.groups.size > 1) {
                            ProxyGroupTabs(
                                groups = visibleProxies.groups,
                                selectedGroupName = resolvedSelectedGroupName,
                                onSelectedGroupNameChange = { selectedGroupName = it },
                            )
                        }
                    }
                }
            }
        },
    ) { innerPadding ->
        val contentPadding = pageContentPaddingWithCutout(
            innerPadding = innerPadding,
            outerPadding = padding,
            isWideScreen = isWideScreen,
        )
        val listPadding = pageListPadding(contentPadding, bottomExtra = 104.dp)
        val layoutDirection = LocalLayoutDirection.current
        val pageListContentPadding = PaddingValues(
            start = listPadding.calculateStartPadding(layoutDirection),
            end = listPadding.calculateEndPadding(layoutDirection),
            bottom = listPadding.calculateBottomPadding(),
        )

        AnimatedContent(
            targetState = contentState == SingBoxProxyContentState.ServiceStopped,
            modifier = Modifier.fillMaxSize(),
            transitionSpec = AsteriskMotion.fadeThrough(contentEffectsMotion),
            label = "proxy-service-availability",
        ) { serviceStopped ->
            if (serviceStopped) {
                SingBoxProxyServiceStoppedState(
                    modifier = Modifier.padding(contentPadding),
                )
            } else {
                Box {
                    HorizontalPager(
                        state = groupPagerState,
                        modifier = Modifier.fillMaxSize(),
                        verticalAlignment = Alignment.Top,
                    ) { page ->
                        val group = visibleProxies.groups.getOrNull(page)
                        val pageDisplayNames = if (
                            group != null && group.name in managedGroupNames
                        ) {
                            outboundDisplayNamesWithoutGroup
                        } else {
                            outboundDisplayNames
                        }
                        val pageNodes = remember(
                            group,
                            visibleProxies,
                            searchQuery,
                            appState.singBoxProxySort,
                            pageDisplayNames,
                        ) {
                            reduceSingBoxProxyNodeNames(
                                group = group,
                                proxies = visibleProxies,
                                query = searchQuery,
                                sort = resolveSingBoxProxySort(appState.singBoxProxySort),
                                displayNames = pageDisplayNames,
                            )
                        }
                        val pageGridState = rememberLazyGridState()

                        Box(Modifier.fillMaxSize()) {
                            LazyVerticalGrid(
                                columns = GridCells.Fixed(columns),
                                state = pageGridState,
                                modifier = Modifier.padding(top = listPadding.calculateTopPadding()),
                                contentPadding = pageListContentPadding,
                                verticalArrangement = Arrangement.spacedBy(SingBoxProxyNodeGridSpacing),
                                horizontalArrangement = Arrangement.spacedBy(SingBoxProxyNodeGridSpacing),
                            ) {
                                if (group == null) {
                                    item(
                                        key = "empty",
                                        span = { GridItemSpan(maxLineSpan) },
                                    ) {
                                        if (contentState == SingBoxProxyContentState.Loading) {
                                            SingBoxProxyLoadingCard()
                                        } else {
                                            SingBoxProxyEmptyCard()
                                        }
                                    }
                                } else if (pageNodes.isEmpty()) {
                                    item(
                                        key = "group_empty:${group.name}",
                                        span = { GridItemSpan(maxLineSpan) },
                                    ) {
                                        SingBoxProxyEmptyCard()
                                    }
                                } else {
                                    items(
                                        items = pageNodes,
                                        key = { nodeName -> "${group.name}:$nodeName" },
                                    ) { nodeName ->
                                        val node = proxies.node(nodeName)
                                        val selectionEnabled =
                                            isSingBoxProxyGroupSelectable(group) && runtimeAvailable
                                        SingBoxProxyNodeCard(
                                            modifier = Modifier
                                                .animateItem()
                                                .fillMaxWidth(),
                                            node = node,
                                            displayName = pageDisplayNames[node.name]
                                                ?: node.name.visibleRuntimeName(unavailableLabel),
                                            selected = isSingBoxProxyNodeCurrent(
                                                group = group,
                                                nodeName = node.name,
                                                pendingSelections = pendingSelections,
                                            ),
                                            selectionEnabled = selectionEnabled,
                                            compact = columns > 1,
                                            delayStatus = resolveSingBoxProxyDelayStatus(
                                                nodeName = node.name,
                                                delay = node.delay,
                                                delayUpdatedAtEpochSeconds =
                                                    node.delayUpdatedAtEpochSeconds,
                                                testingBaselines =
                                                    runtimeState.delayTestingBaselines,
                                                failedNodes = runtimeState.delayFailedNodes,
                                            ),
                                            onSelect = { selectProxy(group, node) },
                                        )
                                    }
                                }
                            }
                        }
                    }
                    selectedGroup?.let { group ->
                        ProxyDelayToolbar(
                            enabled = runtimeAvailable && testingTarget == null,
                            testing = testingTarget == group.name,
                            onDelayTest = { testGroup(group) },
                            bottomPadding = contentPadding.calculateBottomPadding(),
                            modifier = Modifier.align(Alignment.BottomEnd),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SingBoxProxyServiceStoppedState(
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Surface(
            modifier = Modifier.size(72.dp),
            shape = ui.theme.AsteriskShapeTokens.Pill,
            color = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Rounded.PowerSettingsNew,
                    contentDescription = null,
                    modifier = Modifier.size(36.dp),
                )
            }
        }
        Text(
            text = stringResource(R.string.sing_box_proxies_service_stopped_title),
            modifier = Modifier.padding(top = 20.dp),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
        Text(
            text = stringResource(R.string.sing_box_proxies_service_stopped_summary),
            modifier = Modifier.padding(top = 8.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun ProxyGroupTabs(
    groups: List<SingBoxProxyGroup>,
    selectedGroupName: String,
    onSelectedGroupNameChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (groups.isEmpty()) return
    val tabScrollState = rememberScrollState()
    var viewportWidthPx by remember { mutableIntStateOf(0) }
    var tabBounds by remember { mutableStateOf<Map<String, ProxyGroupTabBounds>>(emptyMap()) }
    val selectedBounds = tabBounds[selectedGroupName]

    LaunchedEffect(selectedGroupName, selectedBounds, viewportWidthPx, tabScrollState.maxValue) {
        if (selectedBounds == null || viewportWidthPx <= 0) return@LaunchedEffect
        val targetScroll = resolveProxyTabScrollTarget(
            visibleStart = tabScrollState.value,
            viewportWidth = viewportWidthPx,
            tabStart = selectedBounds.leftPx,
            tabEnd = selectedBounds.leftPx + selectedBounds.widthPx,
            maxScroll = tabScrollState.maxValue,
        )
        if (targetScroll != tabScrollState.value) {
            tabScrollState.animateScrollTo(targetScroll)
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .onSizeChanged { size -> viewportWidthPx = size.width }
            .horizontalScroll(tabScrollState),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            groups.forEach { group ->
                AsteriskFilterChip(
                    selected = group.name == selectedGroupName,
                    onClick = { onSelectedGroupNameChange(group.name) },
                    label = group.displayName,
                    modifier = Modifier.onGloballyPositioned { coordinates ->
                        val bounds = ProxyGroupTabBounds(
                            leftPx = coordinates.positionInParent().x.roundToInt(),
                            widthPx = coordinates.size.width,
                        )
                        if (tabBounds[group.name] != bounds) {
                            tabBounds = tabBounds + (group.name to bounds)
                        }
                    },
                )
            }
        }
    }
}

internal fun resolveProxyTabScrollTarget(
    visibleStart: Int,
    viewportWidth: Int,
    tabStart: Int,
    tabEnd: Int,
    maxScroll: Int,
): Int {
    val visibleEnd = visibleStart + viewportWidth
    return when {
        tabStart < visibleStart -> tabStart
        tabEnd > visibleEnd -> tabEnd - viewportWidth
        else -> visibleStart
    }.coerceIn(0, maxScroll)
}

private data class ProxyGroupTabBounds(
    val leftPx: Int,
    val widthPx: Int,
)

@Composable
private fun SingBoxProxyOptionsMenu(
    layout: Int,
    sort: Int,
    onLayoutChange: (Int) -> Unit,
    onSortChange: (Int) -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    var level by rememberSaveable { mutableStateOf(ProxyOptionsLevel.Main) }
    val dismissMenu = {
        expanded = false
        level = ProxyOptionsLevel.Main
    }
    val layoutLabel = stringResource(
        when (layout) {
            SingBoxProxyLayoutSingle -> R.string.sing_box_proxies_option_layout_single
            SingBoxProxyLayoutDouble -> R.string.sing_box_proxies_option_layout_double
            SingBoxProxyLayoutMultiple -> R.string.sing_box_proxies_option_layout_multiple
            else -> R.string.sing_box_proxies_option_layout_auto
        },
    )
    val sortLabel = stringResource(
        when (sort) {
            SingBoxProxySortName -> R.string.sing_box_proxies_option_sort_name
            SingBoxProxySortDelay -> R.string.sing_box_proxies_option_sort_delay
            else -> R.string.sing_box_proxies_option_sort_default
        },
    )
    val menuSpatialMotion = AsteriskMotion.fastSpatial<IntOffset>()
    val menuSizeMotion = AsteriskMotion.fastSpatial<IntSize>()
    val menuEffectsMotion = AsteriskMotion.fastEffects<Float>()
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.Rounded.MoreVert, stringResource(R.string.sing_box_proxies_options))
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = dismissMenu,
            modifier = Modifier.width(SingBoxProxyOptionsMenuWidth),
        ) {
            AnimatedContent(
                targetState = level,
                modifier = Modifier.fillMaxWidth(),
                transitionSpec = AsteriskMotion.horizontalSlideFade(
                    spatialSpec = menuSpatialMotion,
                    effectsSpec = menuEffectsMotion,
                    sizeSpec = menuSizeMotion,
                ) {
                    if (targetState == ProxyOptionsLevel.Main) -1 else 1
                },
                contentAlignment = Alignment.TopStart,
                label = "proxy-options-level",
            ) { currentLevel ->
                Column(modifier = Modifier.fillMaxWidth()) {
                    when (currentLevel) {
                ProxyOptionsLevel.Main -> {
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(stringResource(R.string.sing_box_proxies_option_layout))
                                Text(
                                    layoutLabel,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        },
                        onClick = { level = ProxyOptionsLevel.Layout },
                        leadingIcon = { Icon(Icons.Rounded.ViewModule, contentDescription = null) },
                        trailingIcon = { Icon(Icons.Rounded.ChevronRight, contentDescription = null) },
                    )
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(stringResource(R.string.sing_box_proxies_option_sort))
                                Text(
                                    sortLabel,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        },
                        onClick = { level = ProxyOptionsLevel.Sort },
                        leadingIcon = { Icon(Icons.AutoMirrored.Rounded.Sort, contentDescription = null) },
                        trailingIcon = { Icon(Icons.Rounded.ChevronRight, contentDescription = null) },
                    )
                }

                ProxyOptionsLevel.Layout -> {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.sing_box_proxies_option_layout)) },
                        onClick = { level = ProxyOptionsLevel.Main },
                        leadingIcon = { Icon(Icons.Rounded.ChevronLeft, contentDescription = null) },
                    )
                    HorizontalDivider()
                    listOf(
                        Triple(
                            SingBoxProxyLayoutAuto,
                            R.string.sing_box_proxies_option_layout_auto,
                            Icons.Rounded.AutoAwesome,
                        ),
                        Triple(
                            SingBoxProxyLayoutSingle,
                            R.string.sing_box_proxies_option_layout_single,
                            Icons.Rounded.ViewAgenda,
                        ),
                        Triple(
                            SingBoxProxyLayoutDouble,
                            R.string.sing_box_proxies_option_layout_double,
                            Icons.Rounded.ViewColumn,
                        ),
                        Triple(
                            SingBoxProxyLayoutMultiple,
                            R.string.sing_box_proxies_option_layout_multiple,
                            Icons.Rounded.GridView,
                        ),
                    ).forEach { (value, label, icon) ->
                        DropdownMenuItem(
                            text = { Text(stringResource(label)) },
                            onClick = {
                                dismissMenu()
                                onLayoutChange(value)
                            },
                            leadingIcon = { Icon(icon, contentDescription = null) },
                            trailingIcon = { RadioButton(selected = layout == value, onClick = null) },
                        )
                    }
                }

                ProxyOptionsLevel.Sort -> {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.sing_box_proxies_option_sort)) },
                        onClick = { level = ProxyOptionsLevel.Main },
                        leadingIcon = { Icon(Icons.Rounded.ChevronLeft, contentDescription = null) },
                    )
                    HorizontalDivider()
                    listOf(
                        Triple(
                            SingBoxProxySortDefault,
                            R.string.sing_box_proxies_option_sort_default,
                            Icons.AutoMirrored.Rounded.Sort,
                        ),
                        Triple(
                            SingBoxProxySortName,
                            R.string.sing_box_proxies_option_sort_name,
                            Icons.Rounded.SortByAlpha,
                        ),
                        Triple(
                            SingBoxProxySortDelay,
                            R.string.sing_box_proxies_option_sort_delay,
                            Icons.Rounded.Speed,
                        ),
                    ).forEach { (value, label, icon) ->
                        DropdownMenuItem(
                            text = { Text(stringResource(label)) },
                            onClick = {
                                dismissMenu()
                                onSortChange(value)
                            },
                            leadingIcon = { Icon(icon, contentDescription = null) },
                            trailingIcon = { RadioButton(selected = sort == value, onClick = null) },
                        )
                    }
                }
                    }
                }
            }
        }
    }
}

private enum class ProxyOptionsLevel {
    Main,
    Layout,
    Sort,
}

private val SingBoxProxyOptionsMenuWidth = 224.dp

@Composable
private fun SingBoxProxyNodeCard(
    node: SingBoxProxyNode,
    displayName: String,
    selected: Boolean,
    selectionEnabled: Boolean,
    compact: Boolean,
    delayStatus: SingBoxProxyDelayStatus,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val content: @Composable () -> Unit = {
        Column(
            modifier = Modifier.fillMaxSize().padding(SingBoxProxyNodeCardPadding),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = displayName,
                    modifier = Modifier.weight(1f),
                    style = if (compact) {
                        MaterialTheme.typography.titleSmall.copy(
                            fontSize = 13.sp,
                            lineHeight = 17.sp,
                        )
                    } else {
                        MaterialTheme.typography.titleMedium
                    },
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (selected) {
                    Icon(
                        imageVector = Icons.Rounded.CheckCircle,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            ProtocolDelayLine(
                protocol = node.type,
                delay = node.delay,
                delayStatus = delayStatus,
                selected = selected,
                compact = compact,
            )
        }
    }
    val cardModifier = modifier
        .height(SingBoxProxyNodeCardHeight)
        .semantics { this.selected = selected }
    AsteriskSelectionCard(
        selected = selected,
        enabled = selectionEnabled,
        onClick = onSelect,
        modifier = cardModifier,
    ) {
        content()
    }
}

@Composable
private fun ProtocolDelayLine(
    protocol: String,
    delay: Int?,
    delayStatus: SingBoxProxyDelayStatus,
    selected: Boolean,
    compact: Boolean,
) {
    val delayText = when (delayStatus) {
        SingBoxProxyDelayStatus.NotTested ->
            stringResource(R.string.sing_box_proxies_delay_not_tested)
        SingBoxProxyDelayStatus.Testing ->
            stringResource(R.string.sing_box_proxies_delay_testing)
        SingBoxProxyDelayStatus.Measured -> delay?.let { measuredDelay ->
            stringResource(R.string.monitor_milliseconds, measuredDelay)
        } ?: stringResource(R.string.sing_box_proxies_delay_not_tested)
        SingBoxProxyDelayStatus.Failed ->
            stringResource(R.string.sing_box_proxies_delay_status_failed)
    }.trim()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(28.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsteriskInfoChip(
            text = protocol.displaySingBoxProtocolName(compact = compact),
            modifier = Modifier.weight(1f, fill = false),
            emphasized = selected,
            textStyle = if (compact) {
                MaterialTheme.typography.labelSmall.copy(
                    fontSize = 10.sp,
                    lineHeight = 14.sp,
                )
            } else {
                MaterialTheme.typography.labelSmall
            },
        )
        Box(
            modifier = Modifier.padding(end = 8.dp).height(28.dp),
            contentAlignment = Alignment.CenterEnd,
        ) {
            Text(
                text = delayText,
                style = if (compact) {
                    MaterialTheme.typography.labelSmall.copy(
                        fontSize = 10.sp,
                        lineHeight = 14.sp,
                    )
                } else {
                    MaterialTheme.typography.labelMedium
                },
                fontWeight = FontWeight.Medium,
                color = delayColor(delayStatus, delay),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.End,
            )
        }
    }
}

private fun String.visibleRuntimeName(unavailableLabel: String): String {
    return if (isManagedSingBoxTag(this)) {
        unavailableLabel
    } else {
        this
    }
}

@Composable
private fun ProxyDelayToolbar(
    enabled: Boolean,
    testing: Boolean,
    onDelayTest: () -> Unit,
    bottomPadding: Dp,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.padding(
            end = 20.dp,
            bottom = bottomPadding + SingBoxFloatingToolbarBottomSpacing,
        ),
    ) {
        ExtendedFloatingActionButton(
            onClick = { if (enabled) onDelayTest() },
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer.copy(
                alpha = if (enabled && !testing) 1f else 0.45f,
            ),
            icon = {
                if (testing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.5.dp,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                } else {
                    DelayToolbarGlyph()
                }
            },
            text = { Text(stringResource(R.string.sing_box_proxies_group_test)) },
        )
    }
}

@Composable
private fun DelayToolbarGlyph(
) {
    Icon(
        imageVector = Icons.Rounded.Speed,
        contentDescription = stringResource(R.string.sing_box_proxies_group_test),
    )
}

@Composable
private fun SingBoxProxyEmptyCard() {
    Text(
        text = stringResource(R.string.common_empty),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 16.dp),
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun SingBoxProxyLoadingCard() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(28.dp),
            strokeWidth = 3.dp,
        )
        Text(
            text = stringResource(R.string.sing_box_proxies_loading),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun delayColor(
    delayStatus: SingBoxProxyDelayStatus,
    delay: Int?,
): Color {
    return when (delayStatus) {
        SingBoxProxyDelayStatus.NotTested -> MaterialTheme.colorScheme.onSurfaceVariant
        SingBoxProxyDelayStatus.Testing -> MaterialTheme.colorScheme.primary
        SingBoxProxyDelayStatus.Failed -> MaterialTheme.colorScheme.error
        SingBoxProxyDelayStatus.Measured -> when {
            delay == null -> MaterialTheme.colorScheme.onSurfaceVariant
            delay < 300 -> MaterialTheme.colorScheme.primary
            delay < 500 -> MaterialTheme.colorScheme.tertiary
            else -> MaterialTheme.colorScheme.error
        }
    }
}

private val SingBoxProxyNodeCardHeight = 112.dp
private val SingBoxProxyNodeCardPadding = PaddingValues(start = 10.dp, top = 14.dp, end = 10.dp, bottom = 10.dp)
private val SingBoxProxyNodeGridSpacing = 12.dp
private val SingBoxFloatingToolbarBottomSpacing = 16.dp
