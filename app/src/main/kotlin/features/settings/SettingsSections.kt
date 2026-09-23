// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.settings

import app.modes.RunModeBpf2Socks
import app.modes.RunModeEbpf
import app.modes.RunModeTun
import app.modes.RunModeTun2Socks
import app.modes.RunModeVpnService
import app.modes.isRootRunMode
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntSize
import engine.singbox.DefaultSingBoxLogLevel
import engine.singbox.EbpfLocalDataPlanes
import engine.singbox.EbpfDnsModes
import app.R
import ui.components.IconAccent
import ui.icons.AsteriskIcons as Icons
import ui.theme.AsteriskMotion

internal fun settingsCoreLogLevelLabels(): List<String> =
    SettingsCoreLogLevelOptions

@Composable
internal fun SettingsAppSection(
    languageOptions: List<String>,
    languageMode: Int,
    colorModeOptions: List<String>,
    colorMode: Int,
    keyColorOptions: List<String>,
    seedIndex: Int,
    onColorModeChange: (Int) -> Unit,
    onSeedIndexChange: (Int) -> Unit,
    onLanguageModeChange: (Int) -> Unit,
) {
    SmallTitle(text = stringResource(R.string.settings_app))
    SettingsSectionCard {
        OverlayDropdownPreference(
            title = stringResource(R.string.settings_language),
            icon = Icons.Rounded.Language,
            items = languageOptions,
            selectedIndex = languageMode,
            onSelectedIndexChange = onLanguageModeChange,
            accent = IconAccent.MaskBlue,
        )
    }
    ThemeSettingsContent(
        colorModeOptions = colorModeOptions,
        colorMode = colorMode,
        keyColorOptions = keyColorOptions,
        seedIndex = seedIndex,
        onColorModeChange = onColorModeChange,
        onSeedIndexChange = onSeedIndexChange,
    )
}

@Composable
internal fun SettingsToolsSection(
    onOpenNetworkQualityTest: () -> Unit,
) {
    SmallTitle(text = stringResource(R.string.settings_tools))
    SettingsSectionCard {
        SettingsToolsItems.forEach { item ->
            when (item) {
                SettingsToolsItem.NetworkQualityTest -> ArrowPreference(
                    title = stringResource(R.string.settings_network_quality_test),
                    icon = Icons.Rounded.Speed,
                    summary = stringResource(R.string.settings_network_quality_test_summary),
                    onClick = onOpenNetworkQualityTest,
                    accent = IconAccent.MaskBlueVariant,
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
    onOpenApps: () -> Unit,
    onOpenResourceManagement: () -> Unit,
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
                    accent = IconAccent.MaskBlueVariant,
                )
                SettingsCoreItem.Sniffer -> ArrowPreference(
                    title = stringResource(R.string.settings_sniffer),
                    icon = Icons.Rounded.TravelExplore,
                    summary = snifferSettingsSummary,
                    onClick = onOpenSnifferSettings,
                    accent = IconAccent.MaskGreen,
                )
                SettingsCoreItem.Outbounds -> ArrowPreference(
                    title = stringResource(R.string.settings_outbound_management),
                    icon = Icons.Rounded.Router,
                    summary = stringResource(R.string.settings_outbound_management_summary),
                    onClick = onOpenOutbounds,
                    accent = IconAccent.MaskBlue,
                )
                SettingsCoreItem.AppManagement -> ArrowPreference(
                    title = stringResource(R.string.proxy_app_list_title),
                    icon = Icons.Rounded.Apps,
                    summary = stringResource(R.string.settings_app_management_summary),
                    onClick = onOpenApps,
                    accent = IconAccent.MaskPurple,
                )
                SettingsCoreItem.Resources -> ArrowPreference(
                    title = stringResource(R.string.settings_resource_management),
                    icon = Icons.Rounded.Folder,
                    summary = stringResource(R.string.settings_resource_management_summary),
                    onClick = onOpenResourceManagement,
                    accent = IconAccent.MaskOrange,
                )
                SettingsCoreItem.Selectors -> ArrowPreference(
                    title = stringResource(R.string.settings_selector_management),
                    icon = Icons.Rounded.Tune,
                    summary = stringResource(R.string.settings_selector_management_summary),
                    onClick = onOpenSelectors,
                    accent = IconAccent.MaskGrey,
                )
                SettingsCoreItem.Endpoints -> ArrowPreference(
                    title = stringResource(R.string.settings_endpoint_management),
                    icon = Icons.Rounded.VpnLock,
                    summary = stringResource(R.string.settings_endpoint_management_summary),
                    onClick = onOpenEndpoints,
                    accent = IconAccent.MaskRed,
                )
                SettingsCoreItem.Routing -> ArrowPreference(
                    title = stringResource(R.string.settings_routing_management),
                    icon = Icons.AutoMirrored.Rounded.AltRoute,
                    summary = stringResource(R.string.settings_routing_management_summary),
                    onClick = onOpenRouting,
                    accent = IconAccent.MaskGreen,
                )
                SettingsCoreItem.LogLevel -> OverlayDropdownPreference(
                    title = stringResource(R.string.settings_log_level),
                    icon = Icons.Rounded.BugReport,
                    items = logLevelLabels,
                    selectedIndex = selectedLogLevelIndex,
                    onSelectedIndexChange = { index ->
                        SettingsCoreLogLevelOptions.getOrNull(index)?.let(onCoreLogLevelChange)
                    },
                    accent = IconAccent.MaskRed,
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
            accent = IconAccent.MaskYellow,
        )
        SwitchPreference(
            title = stringResource(R.string.common_ipv6),
            icon = Icons.Rounded.Public,
            summary = stringResource(R.string.settings_ipv6_summary),
            checked = enableIpv6,
            onCheckedChange = onEnableIpv6Change,
            accent = IconAccent.MaskBlue,
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
                accent = IconAccent.MaskGreen,
            )
        }
        OverlayDropdownPreference(
            title = stringResource(R.string.settings_run_mode),
            icon = Icons.Rounded.SettingsEthernet,
            items = runModeOptions,
            selectedIndex = selectedRunModeIndex.coerceIn(runModeOptions.indices),
            onSelectedIndexChange = onRunModeChange,
            accent = IconAccent.MaskPurple,
        )
    }
}

@Composable
internal fun SettingsProxyModeSections(
    runMode: Int,
    localProxySettingsSummary: String,
    ebpfLocalDataPlane: String,
    ebpfLocalDnsMode: String,
    enableLocalDns: Boolean,
    enableTrafficStatsNotification: Boolean,
    enableVpnAppendHttpProxy: Boolean,
    enableVpnHevTun: Boolean,
    tunSettingsSummary: String,
    enableRootBootScript: Boolean,
    enableRootEbpfRules: Boolean,
    enableRootEbpfDirectCidrBypass: Boolean,
    tunBypassRuleSetsSummary: String,
    enableIpv6: Boolean,
    enableRootIpv6Disabler: Boolean,
    externalInterfacesSummary: String,
    ignoredInterfacesSummary: String,
    privateAddressCidrsSummary: String,
    onOpenLocalProxySettings: () -> Unit,
    onEbpfLocalDataPlaneChange: (String) -> Unit,
    onEbpfLocalDnsModeChange: (String) -> Unit,
    onEnableTrafficStatsNotificationChange: (Boolean) -> Unit,
    onEnableVpnAppendHttpProxyChange: (Boolean) -> Unit,
    onEnableVpnHevTunChange: (Boolean) -> Unit,
    onOpenTunSettings: () -> Unit,
    onEnableRootBootScriptChange: (Boolean) -> Unit,
    onEnableRootEbpfRulesChange: (Boolean) -> Unit,
    onEnableRootEbpfDirectCidrBypassChange: (Boolean) -> Unit,
    onOpenTunBypassRuleSets: () -> Unit,
    onEnableRootIpv6DisablerChange: (Boolean) -> Unit,
    onOpenExternalInterfaces: () -> Unit,
    onOpenServiceControl: () -> Unit,
    onOpenIgnoredInterfaces: () -> Unit,
    onOpenPrivateAddresses: () -> Unit,
) {
    val bypassControlEffectsMotion = AsteriskMotion.fastEffects<Float>()
    val bypassControlSizeMotion = AsteriskMotion.fastSpatial<IntSize>()
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
                    accent = IconAccent.MaskBlue,
                )
                SwitchPreference(
                    title = stringResource(R.string.settings_traffic_stats_notification),
                    icon = Icons.Rounded.Notifications,
                    summary = stringResource(R.string.settings_traffic_stats_notification_summary),
                    checked = enableTrafficStatsNotification,
                    onCheckedChange = onEnableTrafficStatsNotificationChange,
                    accent = IconAccent.MaskPink,
                )
                SwitchPreference(
                    title = stringResource(R.string.settings_vpn_append_http_proxy),
                    icon = Icons.Rounded.Http,
                    summary = stringResource(R.string.settings_vpn_append_http_proxy_summary),
                    checked = enableVpnAppendHttpProxy,
                    onCheckedChange = onEnableVpnAppendHttpProxyChange,
                    accent = IconAccent.MaskOrange,
                )
                SwitchPreference(
                    title = stringResource(R.string.settings_vpn_hev_tun),
                    icon = Icons.Rounded.Memory,
                    summary = stringResource(R.string.settings_vpn_hev_tun_summary),
                    checked = enableVpnHevTun,
                    onCheckedChange = onEnableVpnHevTunChange,
                    accent = IconAccent.MaskYellow,
                )
                ArrowPreference(
                    title = stringResource(R.string.settings_tun),
                    icon = Icons.Rounded.SettingsInputComponent,
                    summary = tunSettingsSummary,
                    onClick = onOpenTunSettings,
                    accent = IconAccent.MaskBlueVariant,
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
                        accent = IconAccent.MaskPink,
                    )
                }
                ArrowPreference(
                    title = stringResource(R.string.settings_service_control),
                    icon = Icons.Rounded.PowerSettingsNew,
                    summary = stringResource(R.string.settings_service_control_summary),
                    onClick = onOpenServiceControl,
                    accent = IconAccent.MaskPink,
                )
                AnimatedVisibility(
                    visible = runMode != RunModeBpf2Socks && runMode != RunModeEbpf && runMode != RunModeTun,
            enter = AsteriskMotion.contentEnter(),
            exit = AsteriskMotion.contentExit(),
                ) {
                    SwitchPreference(
                        title = stringResource(R.string.settings_root_ebpf_matcher),
                        icon = Icons.Rounded.Security,
                        summary = stringResource(R.string.settings_root_ebpf_matcher_summary),
                        checked = enableRootEbpfRules,
                        onCheckedChange = onEnableRootEbpfRulesChange,
                        accent = IconAccent.MaskPurple,
                    )
                }
                AnimatedVisibility(
                    visible = enableRootEbpfRules ||
                        runMode == RunModeBpf2Socks ||
                        (runMode == RunModeEbpf || runMode == RunModeTun),
            enter = AsteriskMotion.contentEnter(),
            exit = AsteriskMotion.contentExit(),
                ) {
                    AnimatedContent(
                        targetState = (runMode == RunModeEbpf || runMode == RunModeTun),
                        modifier = Modifier.fillMaxWidth(),
                        transitionSpec = AsteriskMotion.fadeThrough(
                            effectsSpec = bypassControlEffectsMotion,
                            sizeSpec = bypassControlSizeMotion,
                        ),
                        label = "settings-tun-bypass-control",
                    ) { useRuleSetSelector ->
                        if (useRuleSetSelector) {
                            ArrowPreference(
                                title = stringResource(
                                    R.string.settings_root_ebpf_bypass_direct_cidrs,
                                ),
                                icon = Icons.Rounded.Route,
                                summary = tunBypassRuleSetsSummary,
                                onClick = onOpenTunBypassRuleSets,
                                accent = IconAccent.MaskGreen,
                            )
                        } else {
                            SwitchPreference(
                                title = stringResource(
                                    R.string.settings_root_ebpf_bypass_direct_cidrs,
                                ),
                                icon = Icons.Rounded.Route,
                                summary = stringResource(
                                    R.string.settings_root_ebpf_bypass_direct_cidrs_summary,
                                ),
                                checked = enableRootEbpfDirectCidrBypass,
                                onCheckedChange = onEnableRootEbpfDirectCidrBypassChange,
                                accent = IconAccent.MaskGreen,
                            )
                        }
                    }
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
                        accent = IconAccent.MaskBlue,
                    )
                }
                AnimatedVisibility(
                    visible = runMode == RunModeEbpf,
                    enter = AsteriskMotion.contentEnter(),
                    exit = AsteriskMotion.contentExit(),
                ) {
                    Column {
                        OverlayDropdownPreference(
                            title = stringResource(R.string.settings_ebpf_data_plane),
                            icon = Icons.Rounded.AccountTree,
                            summary = stringResource(R.string.settings_ebpf_local_data_plane_summary),
                            items = EbpfLocalDataPlanes,
                            selectedIndex = EbpfLocalDataPlanes.indexOf(ebpfLocalDataPlane).coerceAtLeast(0),
                            onSelectedIndexChange = { index ->
                                EbpfLocalDataPlanes.getOrNull(index)?.let(onEbpfLocalDataPlaneChange)
                            },
                            accent = IconAccent.MaskPurple,
                        )
                        OverlayDropdownPreference(
                            title = stringResource(R.string.settings_ebpf_dns_mode),
                            icon = Icons.Rounded.Dns,
                            summary = stringResource(
                                if (enableLocalDns) R.string.settings_ebpf_local_dns_mode_summary
                                else R.string.settings_ebpf_dns_disabled,
                            ),
                            items = EbpfDnsModes,
                            selectedIndex = EbpfDnsModes.indexOf(ebpfLocalDnsMode).coerceAtLeast(0),
                            onSelectedIndexChange = { index ->
                                EbpfDnsModes.getOrNull(index)?.let(onEbpfLocalDnsModeChange)
                            },
                            accent = IconAccent.MaskBlueVariant,
                        )
                    }
                }
                SwitchPreference(
                    title = stringResource(R.string.settings_traffic_stats_notification),
                    icon = Icons.Rounded.Notifications,
                    summary = stringResource(R.string.settings_traffic_stats_notification_summary),
                    checked = enableTrafficStatsNotification,
                    onCheckedChange = onEnableTrafficStatsNotificationChange,
                    accent = IconAccent.MaskPink,
                )
                ArrowPreference(
                    title = stringResource(R.string.settings_local_proxy),
                    icon = Icons.Rounded.Router,
                    summary = localProxySettingsSummary,
                    onClick = onOpenLocalProxySettings,
                    accent = IconAccent.MaskBlue,
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
                        accent = IconAccent.MaskBlueVariant,
                    )
                }
                AnimatedVisibility(
                    visible = (runMode == RunModeEbpf || runMode == RunModeTun),
                    enter = AsteriskMotion.contentEnter(),
                    exit = AsteriskMotion.contentExit(),
                ) {
                    ArrowPreference(
                        title = stringResource(R.string.settings_tun_shared_network),
                        icon = Icons.Rounded.Cable,
                        summary = externalInterfacesSummary,
                        onClick = onOpenExternalInterfaces,
                        accent = IconAccent.MaskGrey,
                    )
                }
                AnimatedVisibility(
                    visible = runMode != RunModeEbpf && runMode != RunModeTun,
                    enter = AsteriskMotion.contentEnter(),
                    exit = AsteriskMotion.contentExit(),
                ) {
                    ArrowPreference(
                        title = stringResource(R.string.settings_external_interfaces),
                        icon = Icons.Rounded.Cable,
                        summary = externalInterfacesSummary,
                        onClick = onOpenExternalInterfaces,
                        accent = IconAccent.MaskGrey,
                    )
                }
                AnimatedVisibility(
                    visible = runMode != RunModeEbpf && runMode != RunModeTun,
                    enter = AsteriskMotion.contentEnter(),
                    exit = AsteriskMotion.contentExit(),
                ) {
                    ArrowPreference(
                        title = stringResource(R.string.settings_ignored_interfaces),
                        icon = Icons.Rounded.Block,
                        summary = ignoredInterfacesSummary,
                        onClick = onOpenIgnoredInterfaces,
                        accent = IconAccent.MaskRed,
                    )
                }
                AnimatedVisibility(
                    visible = runMode != RunModeEbpf && runMode != RunModeTun,
                    enter = AsteriskMotion.contentEnter(),
                    exit = AsteriskMotion.contentExit(),
                ) {
                    ArrowPreference(
                        title = stringResource(R.string.settings_private_addresses),
                        icon = Icons.Rounded.HomeWork,
                        summary = privateAddressCidrsSummary,
                        onClick = onOpenPrivateAddresses,
                        accent = IconAccent.MaskOrange,
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
            accent = IconAccent.MaskGreen,
        )
        ArrowPreference(
            title = stringResource(R.string.settings_logcat),
            icon = Icons.Rounded.Terminal,
            onClick = onOpenLogcatLogs,
            accent = IconAccent.MaskPurple,
        )
    }
}

@Composable
internal fun SettingsBackupRestoreSection(
    progressText: String?,
    onBackupUserData: () -> Unit,
    onRestoreUserData: () -> Unit,
) {
    val busy = progressText != null
    SmallTitle(text = stringResource(R.string.settings_backup_restore))
    SettingsSectionCard {
        ArrowPreference(
            title = stringResource(R.string.settings_backup_user_data),
            summary = stringResource(R.string.settings_backup_user_data_summary),
            icon = Icons.Rounded.FileUpload,
            enabled = !busy,
            onClick = onBackupUserData,
            accent = IconAccent.MaskYellow,
        )
        ArrowPreference(
            title = stringResource(R.string.settings_restore_user_data),
            summary = stringResource(R.string.settings_restore_user_data_summary),
            icon = Icons.Rounded.FileDownload,
            enabled = !busy,
            onClick = onRestoreUserData,
            accent = IconAccent.MaskYellow,
        )
        AnimatedVisibility(
            visible = progressText != null,
            enter = AsteriskMotion.contentEnter(),
            exit = AsteriskMotion.contentExit(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(
                    text = progressText.orEmpty(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
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
            accent = IconAccent.MaskBlue,
        )
        ArrowPreference(
            title = stringResource(R.string.settings_open_source_licenses),
            icon = Icons.Rounded.Policy,
            onClick = onOpenLicenses,
            accent = IconAccent.MaskGrey,
        )
    }
}
