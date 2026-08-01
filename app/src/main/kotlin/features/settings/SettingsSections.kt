// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.settings

import app.modes.RunModeBpf2Socks
import app.modes.RunModeEbpf
import app.modes.RunModeTun
import app.modes.RunModeTun2Socks
import app.modes.RunModeVpnService
import app.modes.isRootRunMode
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import engine.singbox.DefaultSingBoxLogLevel
import org.asterisk.zcc.abox.R
import ui.icons.AsteriskIcons as Icons
import ui.theme.AsteriskMotion

@Composable
internal fun settingsTunStackOptions() = listOf(
    stringResource(R.string.settings_tun_stack_system),
    stringResource(R.string.settings_tun_stack_gvisor),
    stringResource(R.string.settings_tun_stack_mixed),
)

internal fun settingsCoreLogLevelLabels(): List<String> =
    SettingsCoreLogLevelOptions

@Composable
internal fun SettingsThemeSection(
    colorModeOptions: List<String>,
    colorMode: Int,
    keyColorOptions: List<String>,
    seedIndex: Int,
    languageOptions: List<String>,
    languageMode: Int,
    onColorModeChange: (Int) -> Unit,
    onSeedIndexChange: (Int) -> Unit,
    onLanguageModeChange: (Int) -> Unit,
) {
    SmallTitle(text = stringResource(R.string.settings_theme))
    SettingsSectionCard {
        OverlayDropdownPreference(
            title = stringResource(R.string.settings_color_mode),
            icon = Icons.Rounded.DarkMode,
            items = colorModeOptions,
            selectedIndex = colorMode,
            onSelectedIndexChange = onColorModeChange,
        )
        OverlayDropdownPreference(
            title = stringResource(R.string.settings_theme_color),
            icon = Icons.Rounded.Palette,
            items = keyColorOptions,
            selectedIndex = seedIndex,
            onSelectedIndexChange = onSeedIndexChange,
        )
        OverlayDropdownPreference(
            title = stringResource(R.string.settings_language),
            icon = Icons.Rounded.Language,
            items = languageOptions,
            selectedIndex = languageMode,
            onSelectedIndexChange = onLanguageModeChange,
        )
    }
}

@Composable
internal fun SettingsGeneralSection(
    onOpenOutboundGroups: () -> Unit,
    onOpenResourceManagement: () -> Unit,
) {
    SmallTitle(text = stringResource(R.string.settings_general))
    SettingsSectionCard {
        SettingsGeneralItems.forEach { item ->
            when (item) {
                SettingsGeneralItem.OutboundGroups -> ArrowPreference(
                    title = stringResource(R.string.settings_group_management),
                    icon = Icons.Rounded.AccountTree,
                    summary = stringResource(R.string.settings_group_management_summary),
                    onClick = onOpenOutboundGroups,
                )
                SettingsGeneralItem.Resources -> ArrowPreference(
                    title = stringResource(R.string.settings_resource_management),
                    icon = Icons.Rounded.Folder,
                    summary = stringResource(R.string.settings_resource_management_summary),
                    onClick = onOpenResourceManagement,
                )
            }
        }
    }
}

@Composable
internal fun SettingsCoreSection(
    snifferSettingsSummary: String,
    coreLogLevel: String,
    onOpenDnsManagement: () -> Unit,
    onOpenSnifferSettings: () -> Unit,
    onOpenOutbounds: () -> Unit,
    onOpenSelectors: () -> Unit,
    onOpenEndpoints: () -> Unit,
    onOpenRouting: () -> Unit,
    onCoreLogLevelChange: (String) -> Unit,
) {
    val logLevelLabels = settingsCoreLogLevelLabels()
    val selectedLogLevelIndex = SettingsCoreLogLevelOptions
        .indexOf(coreLogLevel)
        .takeIf { index -> index >= 0 }
        ?: SettingsCoreLogLevelOptions.indexOf(DefaultSingBoxLogLevel)
    SmallTitle(text = stringResource(R.string.settings_core))
    SettingsSectionCard {
        SettingsCoreItems.forEach { item ->
            when (item) {
                SettingsCoreItem.DnsManagement -> ArrowPreference(
                    title = stringResource(R.string.settings_dns_management),
                    icon = Icons.Rounded.Dns,
                    summary = stringResource(R.string.settings_dns_summary),
                    onClick = onOpenDnsManagement,
                )
                SettingsCoreItem.Sniffer -> ArrowPreference(
                    title = stringResource(R.string.settings_sniffer),
                    icon = Icons.Rounded.TravelExplore,
                    summary = snifferSettingsSummary,
                    onClick = onOpenSnifferSettings,
                )
                SettingsCoreItem.Outbounds -> ArrowPreference(
                    title = stringResource(R.string.settings_outbound_management),
                    icon = Icons.Rounded.Router,
                    summary = stringResource(R.string.settings_outbound_management_summary),
                    onClick = onOpenOutbounds,
                )
                SettingsCoreItem.Selectors -> ArrowPreference(
                    title = stringResource(R.string.settings_selector_management),
                    icon = Icons.Rounded.Tune,
                    summary = stringResource(R.string.settings_selector_management_summary),
                    onClick = onOpenSelectors,
                )
                SettingsCoreItem.Endpoints -> ArrowPreference(
                    title = stringResource(R.string.settings_endpoint_management),
                    icon = Icons.Rounded.VpnLock,
                    summary = stringResource(R.string.settings_endpoint_management_summary),
                    onClick = onOpenEndpoints,
                )
                SettingsCoreItem.Routing -> ArrowPreference(
                    title = stringResource(R.string.settings_routing_management),
                    icon = Icons.AutoMirrored.Rounded.AltRoute,
                    summary = stringResource(R.string.settings_routing_management_summary),
                    onClick = onOpenRouting,
                )
                SettingsCoreItem.LogLevel -> OverlayDropdownPreference(
                    title = stringResource(R.string.settings_log_level),
                    icon = Icons.Rounded.BugReport,
                    items = logLevelLabels,
                    selectedIndex = selectedLogLevelIndex,
                    onSelectedIndexChange = { index ->
                        SettingsCoreLogLevelOptions.getOrNull(index)?.let(onCoreLogLevelChange)
                    },
                )
            }
        }
    }
}

@Composable
internal fun SettingsAdvancedSection(
    enableBroadcastControl: Boolean,
    enableIpv6: Boolean,
    enableIpv6Prefer: Boolean,
    runModeOptions: List<String>,
    selectedRunModeIndex: Int,
    onEnableBroadcastControlChange: (Boolean) -> Unit,
    onEnableIpv6Change: (Boolean) -> Unit,
    onEnableIpv6PreferChange: (Boolean) -> Unit,
    onRunModeChange: (Int) -> Unit,
) {
    SmallTitle(text = stringResource(R.string.settings_advanced))
    SettingsSectionCard {
        SwitchPreference(
            title = stringResource(R.string.settings_broadcast_control),
            icon = Icons.Rounded.CellTower,
            summary = stringResource(R.string.settings_broadcast_control_summary),
            checked = enableBroadcastControl,
            onCheckedChange = onEnableBroadcastControlChange,
        )
        SwitchPreference(
            title = stringResource(R.string.common_ipv6),
            icon = Icons.Rounded.Public,
            summary = stringResource(R.string.settings_ipv6_summary),
            checked = enableIpv6,
            onCheckedChange = onEnableIpv6Change,
        )
        AnimatedVisibility(
            visible = enableIpv6,
            enter = AsteriskMotion.contentEnter(),
            exit = AsteriskMotion.contentExit(),
        ) {
            SwitchPreference(
                title = stringResource(R.string.settings_ipv6_prefer),
                icon = Icons.Rounded.Route,
                summary = stringResource(R.string.settings_ipv6_prefer_summary),
                checked = enableIpv6Prefer,
                onCheckedChange = onEnableIpv6PreferChange,
            )
        }
        OverlayDropdownPreference(
            title = stringResource(R.string.settings_run_mode),
            icon = Icons.Rounded.AccountTree,
            items = runModeOptions,
            selectedIndex = selectedRunModeIndex.coerceIn(runModeOptions.indices),
            onSelectedIndexChange = onRunModeChange,
        )
    }
}

@Composable
internal fun SettingsProxyModeSections(
    runMode: Int,
    localProxySettingsSummary: String,
    enableTrafficStatsNotification: Boolean,
    enableVpnAppendHttpProxy: Boolean,
    enableVpnHevTun: Boolean,
    tunSettingsSummary: String,
    enableRootBootScript: Boolean,
    enableRootEbpfRules: Boolean,
    enableRootEbpfDirectCidrBypass: Boolean,
    enableIpv6: Boolean,
    enableRootIpv6Disabler: Boolean,
    externalInterfacesSummary: String,
    ignoredInterfacesSummary: String,
    privateAddressCidrsSummary: String,
    onOpenLocalProxySettings: () -> Unit,
    onEnableTrafficStatsNotificationChange: (Boolean) -> Unit,
    onEnableVpnAppendHttpProxyChange: (Boolean) -> Unit,
    onEnableVpnHevTunChange: (Boolean) -> Unit,
    onOpenTunSettings: () -> Unit,
    onEnableRootBootScriptChange: (Boolean) -> Unit,
    onEnableRootEbpfRulesChange: (Boolean) -> Unit,
    onEnableRootEbpfDirectCidrBypassChange: (Boolean) -> Unit,
    onEnableRootIpv6DisablerChange: (Boolean) -> Unit,
    onOpenExternalInterfaces: () -> Unit,
    onOpenIgnoredInterfaces: () -> Unit,
    onOpenPrivateAddresses: () -> Unit,
) {
    AnimatedVisibility(
        visible = runMode == RunModeVpnService,
        enter = AsteriskMotion.contentEnter(),
        exit = ExitTransition.None,
    ) {
        Column {
            SmallTitle(text = stringResource(R.string.settings_proxy_vpn_service))
            SettingsSectionCard {
                ArrowPreference(
                    title = stringResource(R.string.settings_local_proxy),
                    icon = Icons.Rounded.Router,
                    summary = localProxySettingsSummary,
                    onClick = onOpenLocalProxySettings,
                )
                SwitchPreference(
                    title = stringResource(R.string.settings_traffic_stats_notification),
                    icon = Icons.Rounded.Notifications,
                    summary = stringResource(R.string.settings_traffic_stats_notification_summary),
                    checked = enableTrafficStatsNotification,
                    onCheckedChange = onEnableTrafficStatsNotificationChange,
                )
                SwitchPreference(
                    title = stringResource(R.string.settings_vpn_append_http_proxy),
                    icon = Icons.Rounded.Http,
                    summary = stringResource(R.string.settings_vpn_append_http_proxy_summary),
                    checked = enableVpnAppendHttpProxy,
                    onCheckedChange = onEnableVpnAppendHttpProxyChange,
                )
                SwitchPreference(
                    title = stringResource(R.string.settings_vpn_hev_tun),
                    icon = Icons.Rounded.Memory,
                    summary = stringResource(R.string.settings_vpn_hev_tun_summary),
                    checked = enableVpnHevTun,
                    onCheckedChange = onEnableVpnHevTunChange,
                )
                ArrowPreference(
                    title = stringResource(R.string.settings_tun),
                    icon = Icons.Rounded.SettingsInputComponent,
                    summary = tunSettingsSummary,
                    onClick = onOpenTunSettings,
                )
            }
        }
    }
    AnimatedVisibility(
        visible = runMode.isRootRunMode(),
        enter = AsteriskMotion.contentEnter(),
        exit = ExitTransition.None,
    ) {
        Column {
            SmallTitle(
                text = stringResource(
                    when (runMode) {
                        RunModeTun -> R.string.settings_proxy_tun
                        RunModeEbpf -> R.string.settings_proxy_ebpf
                        RunModeTun2Socks -> R.string.settings_proxy_tun2socks
                        RunModeBpf2Socks -> R.string.settings_proxy_bpf2socks
                        else -> R.string.settings_proxy_tproxy
                    },
                ),
            )
            SettingsSectionCard {
                AnimatedVisibility(
                    visible = runMode.isRootRunMode(),
            enter = AsteriskMotion.contentEnter(),
            exit = AsteriskMotion.contentExit(),
                ) {
                    SwitchPreference(
                        title = stringResource(R.string.settings_root_boot_script),
                        icon = Icons.Rounded.PowerSettingsNew,
                        summary = stringResource(R.string.settings_root_boot_script_summary),
                        checked = enableRootBootScript,
                        onCheckedChange = onEnableRootBootScriptChange,
                    )
                }
                AnimatedVisibility(
                    visible = runMode != RunModeBpf2Socks && runMode != RunModeEbpf,
            enter = AsteriskMotion.contentEnter(),
            exit = AsteriskMotion.contentExit(),
                ) {
                    SwitchPreference(
                        title = stringResource(R.string.settings_root_ebpf_matcher),
                        icon = Icons.Rounded.Security,
                        summary = stringResource(R.string.settings_root_ebpf_matcher_summary),
                        checked = enableRootEbpfRules,
                        onCheckedChange = onEnableRootEbpfRulesChange,
                    )
                }
                AnimatedVisibility(
                    visible = enableRootEbpfRules ||
                        runMode == RunModeBpf2Socks ||
                        runMode == RunModeEbpf,
            enter = AsteriskMotion.contentEnter(),
            exit = AsteriskMotion.contentExit(),
                ) {
                    SwitchPreference(
                        title = stringResource(R.string.settings_root_ebpf_bypass_direct_cidrs),
                        icon = Icons.Rounded.Route,
                        summary = stringResource(
                            if (runMode == RunModeEbpf) {
                                R.string.settings_ebpf_bypass_direct_rule_sets_summary
                            } else {
                                R.string.settings_root_ebpf_bypass_direct_cidrs_summary
                            },
                        ),
                        checked = enableRootEbpfDirectCidrBypass,
                        onCheckedChange = onEnableRootEbpfDirectCidrBypassChange,
                    )
                }
                AnimatedVisibility(
                    visible = !enableIpv6,
            enter = AsteriskMotion.contentEnter(),
            exit = AsteriskMotion.contentExit(),
                ) {
                    SwitchPreference(
                        title = stringResource(R.string.settings_root_ipv6_disabler),
                        icon = Icons.Rounded.Public,
                        summary = stringResource(R.string.settings_root_ipv6_disabler_summary),
                        checked = enableRootIpv6Disabler,
                        onCheckedChange = onEnableRootIpv6DisablerChange,
                    )
                }
                ArrowPreference(
                    title = stringResource(R.string.settings_local_proxy),
                    icon = Icons.Rounded.Router,
                    summary = localProxySettingsSummary,
                    onClick = onOpenLocalProxySettings,
                )
                AnimatedVisibility(
                    visible = runMode == RunModeTun || runMode == RunModeTun2Socks,
            enter = AsteriskMotion.contentEnter(),
            exit = AsteriskMotion.contentExit(),
                ) {
                    ArrowPreference(
                        title = stringResource(R.string.settings_tun),
                        icon = Icons.Rounded.SettingsInputComponent,
                        summary = tunSettingsSummary,
                        onClick = onOpenTunSettings,
                    )
                }
                AnimatedVisibility(
                    visible = runMode == RunModeEbpf,
                    enter = AsteriskMotion.contentEnter(),
                    exit = AsteriskMotion.contentExit(),
                ) {
                    ArrowPreference(
                        title = stringResource(R.string.settings_ebpf_shared_network),
                        icon = Icons.Rounded.Cable,
                        summary = externalInterfacesSummary,
                        onClick = onOpenExternalInterfaces,
                    )
                }
                AnimatedVisibility(
                    visible = runMode != RunModeEbpf,
                    enter = AsteriskMotion.contentEnter(),
                    exit = AsteriskMotion.contentExit(),
                ) {
                    ArrowPreference(
                        title = stringResource(R.string.settings_external_interfaces),
                        icon = Icons.Rounded.Cable,
                        summary = externalInterfacesSummary,
                        onClick = onOpenExternalInterfaces,
                    )
                }
                AnimatedVisibility(
                    visible = runMode != RunModeEbpf,
                    enter = AsteriskMotion.contentEnter(),
                    exit = AsteriskMotion.contentExit(),
                ) {
                    ArrowPreference(
                        title = stringResource(R.string.settings_ignored_interfaces),
                        icon = Icons.Rounded.Block,
                        summary = ignoredInterfacesSummary,
                        onClick = onOpenIgnoredInterfaces,
                    )
                }
                AnimatedVisibility(
                    visible = runMode != RunModeEbpf,
                    enter = AsteriskMotion.contentEnter(),
                    exit = AsteriskMotion.contentExit(),
                ) {
                    ArrowPreference(
                        title = stringResource(R.string.settings_private_addresses),
                        icon = Icons.Rounded.HomeWork,
                        summary = privateAddressCidrsSummary,
                        onClick = onOpenPrivateAddresses,
                    )
                }
            }
        }
    }
}

@Composable
internal fun SettingsLogsSection(
    onOpenCoreLogs: () -> Unit,
    onOpenLogcatLogs: () -> Unit,
) {
    SmallTitle(text = stringResource(R.string.settings_logs))
    SettingsSectionCard {
        ArrowPreference(
            title = stringResource(R.string.settings_core_logs),
            icon = Icons.AutoMirrored.Rounded.Article,
            onClick = onOpenCoreLogs,
        )
        ArrowPreference(
            title = stringResource(R.string.settings_logcat),
            icon = Icons.Rounded.Terminal,
            onClick = onOpenLogcatLogs,
        )
    }
}

@Composable
internal fun SettingsAboutSection(
    onOpenAbout: () -> Unit,
    onOpenLicenses: () -> Unit,
) {
    SmallTitle(text = stringResource(R.string.settings_about))
    SettingsSectionCard(bottomPadding = 0.dp) {
        ArrowPreference(
            title = stringResource(R.string.settings_about_project),
            icon = Icons.AutoMirrored.Rounded.Help,
            onClick = onOpenAbout,
        )
        ArrowPreference(
            title = stringResource(R.string.settings_open_source_licenses),
            icon = Icons.Rounded.Policy,
            onClick = onOpenLicenses,
        )
    }
}
