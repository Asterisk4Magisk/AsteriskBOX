// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package app

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.LayoutDirection
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberDecoratedNavEntries
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import app.navigation.MainDestination
import app.navigation.MainDestinationState
import app.navigation.Navigator
import app.navigation.Route
import app.navigation.rememberMainDestinationState
import features.about.AboutPage
import features.about.LicensePage
import features.dns.DnsManagementPage
import features.dns.DnsRuleEditorPage
import features.endpoint.EndpointEditorPage
import features.endpoint.EndpointListPage
import features.logs.CoreLogsPage
import features.logs.LogcatLogsPage
import features.monitoring.connections.ConnectionsMonitorPage
import features.monitoring.network.NetworkMonitorPage
import features.monitoring.resource.ResourceMonitorPage
import features.monitoring.traffic.TrafficMonitorPage
import features.outbound.OutboundEditorPage
import features.outbound.OutboundGroupListPage
import features.outbound.OutboundListPage
import features.proxy.app.ProxyAppListPage
import features.resources.ResourceJsonEditorPage
import features.resources.ResourceManagementPage
import features.routing.RouteRuleEditorPage
import features.routing.RoutingManagementPage
import features.selector.SelectorEditorPage
import features.selector.SelectorManagementPage
import features.settings.SettingsPage
import features.singbox.HomeDashboardPage
import features.singbox.SingBoxProxyDestination
import ui.components.AsteriskFloatingNavigationBar
import ui.components.AsteriskFloatingNavigationItem
import ui.components.AsteriskScaffold
import ui.layout.pageWindowPadding
import ui.layout.shouldShowNavigationRail
import ui.layout.shouldShowSplitPane
import ui.navigation.AsteriskNavDisplay
import ui.theme.AsteriskMotion
import ui.icons.AsteriskIcons as Icons

private data class MainNavigationItem(
    val destination: MainDestination,
    val label: String,
    val icon: ImageVector,
)

@Composable
private fun mainNavigationItems(): List<MainNavigationItem> {
    val home = stringResource(R.string.nav_dashboard)
    val proxies = stringResource(R.string.nav_proxies)
    val groups = stringResource(R.string.nav_groups)
    val settings = stringResource(R.string.nav_settings)

    return remember(home, proxies, groups, settings) {
        listOf(
            MainNavigationItem(MainDestination.Home, home, Icons.Rounded.Home),
            MainNavigationItem(MainDestination.Proxies, proxies, Icons.AutoMirrored.Rounded.AltRoute),
            MainNavigationItem(MainDestination.Groups, groups, Icons.AutoMirrored.Rounded.Wysiwyg),
            MainNavigationItem(MainDestination.Settings, settings, Icons.Rounded.Settings),
        )
    }
}

val LocalNavigator = staticCompositionLocalOf<Navigator> { error("No navigator found!") }
val LocalIsWideScreen = staticCompositionLocalOf { false }

val LocalSupportsSplitPane = staticCompositionLocalOf { false }
internal val LocalMainDestinationState = staticCompositionLocalOf<MainDestinationState?> { null }

@Composable
fun AppContent(
    padding: PaddingValues,
) {
    val mainDestinationState = rememberMainDestinationState()

    val backStack = remember { mutableStateListOf<NavKey>().apply { add(Route.Main) } }
    val navigator = remember { Navigator(backStack) }

    MainScreenBackHandler(mainDestinationState, navigator)

    val isWideScreen = shouldShowNavigationRail()
    val supportsSplitPane = shouldShowSplitPane()

    CompositionLocalProvider(
        LocalNavigator provides navigator,
        LocalIsWideScreen provides isWideScreen,
        LocalSupportsSplitPane provides supportsSplitPane,
        LocalMainDestinationState provides mainDestinationState,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface)
                .imePadding(),
        ) {
            val entryProvider = remember(backStack) {
                entryProvider<NavKey> {
                entry<Route.Main> {
                    Home(
                        padding = padding,
                        mainDestinationState = mainDestinationState,
                    )
                }
                entry<Route.About> {
                    AboutPage(padding = padding)
                }
                entry<Route.License> {
                    LicensePage(padding = padding)
                }
                entry<Route.CoreLogs> {
                    CoreLogsPage(padding = padding)
                }
                entry<Route.LogcatLogs> {
                    LogcatLogsPage(padding = padding)
                }
                entry<Route.ResourceManagement> {
                    ResourceManagementPage(padding = padding)
                }
                entry<Route.ResourceJsonEdit> { route ->
                    key(route.resourceId) {
                        ResourceJsonEditorPage(
                            padding = padding,
                            resourceId = route.resourceId,
                        )
                    }
                }
                entry<Route.OutboundGroupCreate> {
                    OutboundGroupListPage(
                        padding = padding,
                        createOnOpen = true,
                        onBack = navigator::pop,
                    )
                }
                entry<Route.ProxyAppList> {
                    ProxyAppListPage(
                        padding = padding,
                        onBack = navigator::pop,
                    )
                }
                entry<Route.OutboundList> {
                    OutboundListPage(padding = padding)
                }
                entry<Route.SelectorManagement> {
                    SelectorManagementPage(padding = padding)
                }
                entry<Route.SelectorEdit> { route ->
                    key(route.selectorId) {
                        SelectorEditorPage(
                            padding = padding,
                            selectorId = route.selectorId,
                        )
                    }
                }
                entry<Route.EndpointList> {
                    EndpointListPage(padding = padding)
                }
                entry<Route.RoutingManagement> {
                    RoutingManagementPage(padding = padding)
                }
                entry<Route.RouteRuleEdit> { route ->
                    key(route.ruleId, route.initialDraft, route.resultKey, route.nested) {
                        RouteRuleEditorPage(
                            padding = padding,
                            ruleId = route.ruleId,
                            initialDraft = route.initialDraft,
                            resultKey = route.resultKey,
                            nested = route.nested,
                        )
                    }
                }
                entry<Route.DnsManagement> { route ->
                    DnsManagementPage(
                        padding = padding,
                        initiallyOpenDnsSettings = route.openSettings,
                    )
                }
                entry<Route.DnsRuleEdit> { route ->
                    key(
                        route.ruleId,
                        route.initialDraft,
                        route.resultKey,
                        route.nested,
                        route.topLevelRuleId,
                    ) {
                        DnsRuleEditorPage(
                            padding = padding,
                            ruleId = route.ruleId,
                            initialDraft = route.initialDraft,
                            resultKey = route.resultKey,
                            nested = route.nested,
                            topLevelRuleId = route.topLevelRuleId,
                        )
                    }
                }
                entry<Route.OutboundEdit> { route ->
                    key(route.outboundId, route.groupId, route.type) {
                        OutboundEditorPage(
                            padding = padding,
                            outboundId = route.outboundId,
                            initialGroupId = route.groupId,
                            initialType = route.type,
                        )
                    }
                }
                entry<Route.EndpointEdit> { route ->
                    key(route.endpointId, route.type, route.draftRemarks) {
                        EndpointEditorPage(
                            padding = padding,
                            endpointId = route.endpointId,
                            initialType = route.type,
                            draftRemarks = route.draftRemarks,
                        )
                    }
                }
                entry<Route.ResourceMonitor> {
                    ResourceMonitorPage(padding = padding)
                }
                entry<Route.ConnectionsMonitor> {
                    ConnectionsMonitorPage(padding = padding)
                }
                entry<Route.TrafficMonitor> {
                    TrafficMonitorPage(padding = padding)
                }
                entry<Route.NetworkMonitor> {
                    NetworkMonitorPage(padding = padding)
                }
            }
            }

            val entries = rememberDecoratedNavEntries(
                backStack = backStack,
                entryDecorators = listOf(rememberSaveableStateHolderNavEntryDecorator()),
                entryProvider = entryProvider,
            )
            AsteriskNavDisplay(
                entries = entries,
                onBack = { navigator.pop() },
                transitionSpec = AsteriskMotion.navigationForward(),
                popTransitionSpec = AsteriskMotion.navigationBack(),
                predictivePopTransitionSpec = AsteriskMotion.predictiveNavigationBack(),
            )

            // Global error dialog — visible across every nav destination so the user sees the
            // diagnostic immediately on the home page after a failed proxy start, regardless of
            // which tab they are on.
            features.singbox.ProxyErrorHost()
        }
    }
}

@Composable
private fun Home(
    padding: PaddingValues,
    mainDestinationState: MainDestinationState,
) {
    val isWideScreen = LocalIsWideScreen.current
    val layoutDirection = LocalLayoutDirection.current
    val navigationItems = mainNavigationItems()
    if (isWideScreen) {
        WideScreenContent(
            navigationItems = navigationItems,
            layoutDirection = layoutDirection,
            mainDestinationState = mainDestinationState,
        )
    } else {
        CompactScreenLayout(
            navigationItems = navigationItems,
            padding = padding,
            mainDestinationState = mainDestinationState,
        )
    }
}

@Composable
private fun WideScreenContent(
    navigationItems: List<MainNavigationItem>,
    layoutDirection: LayoutDirection,
    mainDestinationState: MainDestinationState,
) {
    val selectedDestination = mainDestinationState.current
    Row {
        NavigationRail {
            navigationItems.forEach { item ->
                NavigationRailItem(
                    selected = selectedDestination == item.destination,
                    onClick = { mainDestinationState.select(item.destination) },
                    icon = { Icon(imageVector = item.icon, contentDescription = null) },
                    label = { Text(item.label) },
                )
            }
        }
        AsteriskScaffold(
            modifier = Modifier
                .fillMaxSize(),
            contentWindowInsets =
                WindowInsets.systemBars.union(
                    WindowInsets.displayCutout.exclude(
                        WindowInsets.displayCutout.only(WindowInsetsSides.Start),
                    ),
                ),
        ) { padding ->
            MainDestinationContent(
                padding = PaddingValues(top = padding.calculateTopPadding()),
                mainDestinationState = mainDestinationState,
                modifier = Modifier
                    .padding(end = padding.calculateEndPadding(layoutDirection)),
            )
        }
    }
}

@Composable
private fun CompactScreenLayout(
    navigationItems: List<MainNavigationItem>,
    padding: PaddingValues,
    mainDestinationState: MainDestinationState,
) {
    AsteriskScaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            MainNavigationBar(
                navigationItems = navigationItems,
                mainDestinationState = mainDestinationState,
            )
        },
    ) { innerPadding ->
        MainDestinationContent(
            padding = innerPadding,
            mainDestinationState = mainDestinationState,
            modifier = Modifier.pageWindowPadding(padding),
        )
    }
}

@Composable
private fun MainNavigationBar(
    navigationItems: List<MainNavigationItem>,
    mainDestinationState: MainDestinationState,
    modifier: Modifier = Modifier,
) {
    val selectedDestination = mainDestinationState.current
    AsteriskFloatingNavigationBar(modifier = modifier) {
        navigationItems.forEach { item ->
            AsteriskFloatingNavigationItem(
                selected = selectedDestination == item.destination,
                onClick = { mainDestinationState.select(item.destination) },
                icon = item.icon,
                label = item.label,
            )
        }
    }
}

@Composable
private fun MainDestinationContent(
    padding: PaddingValues,
    mainDestinationState: MainDestinationState,
    modifier: Modifier = Modifier,
) {
    val stateHolder = rememberSaveableStateHolder()
    AnimatedContent(
        targetState = mainDestinationState.current,
        modifier = modifier,
        transitionSpec = AsteriskMotion.destinationChange { it.index },
        label = "main-destination",
    ) { destination ->
        stateHolder.SaveableStateProvider(destination.id) {
            key(destination) {
                when (destination) {
                    MainDestination.Home -> HomeDashboardPage(padding = padding)
                    MainDestination.Proxies -> SingBoxProxyDestination(padding = padding)
                    MainDestination.Groups -> OutboundGroupListPage(padding = padding)
                    MainDestination.Settings -> SettingsPage(padding = padding)
                }
            }
        }
    }
}

@Composable
private fun MainScreenBackHandler(
    mainState: MainDestinationState,
    navigator: Navigator,
) {
    val isMainDestinationBackHandlerEnabled by remember {
        derivedStateOf {
            navigator.current() is Route.Main &&
                navigator.backStackSize() == 1 &&
                mainState.current != MainDestination.Home
        }
    }

    val navEventState = rememberNavigationEventState(NavigationEventInfo.None)

    NavigationBackHandler(
        state = navEventState,
        isBackEnabled = isMainDestinationBackHandlerEnabled,
        onBackCompleted = {
            mainState.select(MainDestination.Home)
        },
    )
}
