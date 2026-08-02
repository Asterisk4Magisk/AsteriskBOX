// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import org.asterisk.zcc.abox.R
import ui.icons.AsteriskIcons as Icons

internal data class SettingsSearchEntry(
    val title: String,
    val parent: String,
    val icon: ImageVector,
    val onClick: () -> Unit,
)

internal fun filterSettingsSearchEntries(
    entries: List<SettingsSearchEntry>,
    query: String,
): List<SettingsSearchEntry> {
    val normalizedQuery = query.trim()
    if (normalizedQuery.isEmpty()) return entries
    return entries.filter { entry ->
        entry.title.contains(normalizedQuery, ignoreCase = true) ||
            entry.parent.contains(normalizedQuery, ignoreCase = true)
    }
}

@Composable
internal fun settingsTopLevelSearchItems(
    useEbpfSharedNetwork: Boolean,
    colorModeOptions: List<String>,
    colorMode: Int,
    keyColorOptions: List<String>,
    seedIndex: Int,
    languageOptions: List<String>,
    languageMode: Int,
    coreLogLevel: String,
    runModeOptions: List<String>,
    selectedRunModeIndex: Int,
    snifferSummary: String,
    localProxySummary: String,
    tunSummary: String,
    externalInterfacesSummary: String,
    ignoredInterfacesSummary: String,
    privateAddressesSummary: String,
): List<SettingsSearchItem> {
    fun optionValue(options: List<String>, index: Int): String =
        options.getOrNull(index).orEmpty()

    val coreLogLevelLabels = settingsCoreLogLevelLabels()
    return listOfNotNull(
        SettingsSearchItem(
            SettingsSectionId.Theme,
            stringResource(R.string.settings_color_mode),
            value = optionValue(colorModeOptions, colorMode),
            optionText = colorModeOptions,
        ),
        SettingsSearchItem(
            SettingsSectionId.Theme,
            stringResource(R.string.settings_theme_color),
            value = optionValue(keyColorOptions, seedIndex),
            optionText = keyColorOptions,
        ),
        SettingsSearchItem(
            SettingsSectionId.Theme,
            stringResource(R.string.settings_language),
            value = optionValue(languageOptions, languageMode),
            optionText = languageOptions,
        ),
        SettingsSearchItem(
            SettingsSectionId.General,
            stringResource(R.string.settings_group_management),
            stringResource(R.string.settings_group_management_summary),
        ),
        SettingsSearchItem(
            SettingsSectionId.General,
            stringResource(R.string.settings_resource_management),
            stringResource(R.string.settings_resource_management_summary),
        ),
        SettingsSearchItem(
            SettingsSectionId.Core,
            stringResource(R.string.settings_dns_management),
            stringResource(R.string.settings_dns_summary),
        ),
        SettingsSearchItem(SettingsSectionId.Core, stringResource(R.string.settings_sniffer), snifferSummary),
        SettingsSearchItem(
            SettingsSectionId.Core,
            stringResource(R.string.settings_outbound_management),
            stringResource(R.string.settings_outbound_management_summary),
        ),
        SettingsSearchItem(
            SettingsSectionId.Core,
            stringResource(R.string.settings_endpoint_management),
            stringResource(R.string.settings_endpoint_management_summary),
        ),
        SettingsSearchItem(
            SettingsSectionId.Core,
            stringResource(R.string.settings_selector_management),
            stringResource(R.string.settings_selector_management_summary),
        ),
        SettingsSearchItem(
            SettingsSectionId.Core,
            stringResource(R.string.settings_routing_management),
            stringResource(R.string.settings_routing_management_summary),
        ),
        SettingsSearchItem(
            SettingsSectionId.Core,
            stringResource(R.string.settings_log_level),
            value = coreLogLevel,
            optionText = coreLogLevelLabels,
        ),
        SettingsSearchItem(
            SettingsSectionId.Advanced,
            stringResource(R.string.settings_broadcast_control),
            stringResource(R.string.settings_broadcast_control_summary),
        ),
        SettingsSearchItem(
            SettingsSectionId.Advanced,
            stringResource(R.string.common_ipv6),
            stringResource(R.string.settings_ipv6_summary),
        ),
        SettingsSearchItem(
            SettingsSectionId.Advanced,
            stringResource(R.string.settings_ipv6_prefer),
            stringResource(R.string.settings_ipv6_prefer_summary),
        ),
        SettingsSearchItem(
            SettingsSectionId.Advanced,
            stringResource(R.string.settings_run_mode),
            value = optionValue(runModeOptions, selectedRunModeIndex),
            optionText = runModeOptions,
        ),
        SettingsSearchItem(SettingsSectionId.Vpn, stringResource(R.string.settings_local_proxy), localProxySummary),
        SettingsSearchItem(
            SettingsSectionId.Vpn,
            stringResource(R.string.settings_traffic_stats_notification),
            stringResource(R.string.settings_traffic_stats_notification_summary),
        ),
        SettingsSearchItem(
            SettingsSectionId.Vpn,
            stringResource(R.string.settings_vpn_append_http_proxy),
            stringResource(R.string.settings_vpn_append_http_proxy_summary),
        ),
        SettingsSearchItem(
            SettingsSectionId.Vpn,
            stringResource(R.string.settings_vpn_hev_tun),
            stringResource(R.string.settings_vpn_hev_tun_summary),
        ),
        SettingsSearchItem(SettingsSectionId.Vpn, stringResource(R.string.settings_tun), tunSummary),
        SettingsSearchItem(
            SettingsSectionId.Tproxy,
            stringResource(R.string.settings_root_boot_script),
            stringResource(R.string.settings_root_boot_script_summary),
        ),
        if (useEbpfSharedNetwork) {
            null
        } else {
            SettingsSearchItem(
                SettingsSectionId.Tproxy,
                stringResource(R.string.settings_root_ebpf_matcher),
                stringResource(R.string.settings_root_ebpf_matcher_summary),
            )
        },
        SettingsSearchItem(
            SettingsSectionId.Tproxy,
            stringResource(R.string.settings_root_ebpf_bypass_direct_cidrs),
            stringResource(
                if (useEbpfSharedNetwork) {
                    R.string.settings_ebpf_bypass_direct_rule_sets_summary
                } else {
                    R.string.settings_root_ebpf_bypass_direct_cidrs_summary
                },
            ),
        ),
        SettingsSearchItem(
            SettingsSectionId.Tproxy,
            stringResource(R.string.settings_root_ipv6_disabler),
            stringResource(R.string.settings_root_ipv6_disabler_summary),
        ),
        SettingsSearchItem(
            SettingsSectionId.Tproxy,
            stringResource(
                if (useEbpfSharedNetwork) {
                    R.string.settings_ebpf_shared_network
                } else {
                    R.string.settings_external_interfaces
                },
            ),
            externalInterfacesSummary,
        ),
        if (useEbpfSharedNetwork) {
            null
        } else {
            SettingsSearchItem(
                SettingsSectionId.Tproxy,
                stringResource(R.string.settings_ignored_interfaces),
                ignoredInterfacesSummary,
            )
        },
        if (useEbpfSharedNetwork) {
            null
        } else {
            SettingsSearchItem(
                SettingsSectionId.Tproxy,
                stringResource(R.string.settings_private_addresses),
                privateAddressesSummary,
            )
        },
        SettingsSearchItem(SettingsSectionId.Logs, stringResource(R.string.settings_core_logs)),
        SettingsSearchItem(SettingsSectionId.Logs, stringResource(R.string.settings_logcat)),
        SettingsSearchItem(
            SettingsSectionId.BackupRestore,
            stringResource(R.string.settings_backup_user_data),
            stringResource(R.string.settings_backup_user_data_summary),
        ),
        SettingsSearchItem(
            SettingsSectionId.BackupRestore,
            stringResource(R.string.settings_restore_user_data),
            stringResource(R.string.settings_restore_user_data_summary),
        ),
        SettingsSearchItem(SettingsSectionId.About, stringResource(R.string.settings_about_project)),
        SettingsSearchItem(SettingsSectionId.About, stringResource(R.string.settings_open_source_licenses)),
        SettingsSearchItem(
            SettingsSectionId.Advanced,
            title = stringResource(R.string.common_boolean),
            optionText = listOf("true", "false"),
        ),
    )
}

@Composable
internal fun SettingsNestedSearchResults(
    query: String,
    entries: List<SettingsSearchEntry>,
) {
    val results = filterSettingsSearchEntries(entries, query)
    if (results.isEmpty()) return
    SmallTitle(text = stringResource(R.string.settings_search_results))
    SettingsSectionCard {
        results.forEach { entry ->
            SettingsActionRow(
                title = entry.title,
                summary = entry.parent,
                icon = entry.icon,
                onClick = entry.onClick,
            )
        }
    }
}

@Composable
internal fun settingsNestedSearchEntries(
    useEbpfSharedNetwork: Boolean,
    onOpenDns: () -> Unit,
    onOpenSniffer: () -> Unit,
    onOpenLocalProxy: () -> Unit,
    onOpenTun: () -> Unit,
    onOpenExternalInterfaces: () -> Unit,
    onOpenIgnoredInterfaces: () -> Unit,
    onOpenPrivateAddresses: () -> Unit,
): List<SettingsSearchEntry> {
    val dns = stringResource(R.string.settings_dns)
    val sniffer = stringResource(R.string.settings_sniffer)
    val localProxy = stringResource(R.string.settings_local_proxy)
    val tun = stringResource(R.string.settings_tun)
    val externalInterfaces = stringResource(
        if (useEbpfSharedNetwork) {
            R.string.settings_ebpf_shared_network
        } else {
            R.string.settings_external_interfaces
        },
    )
    val ignoredInterfaces = stringResource(R.string.settings_ignored_interfaces)
    val privateAddresses = stringResource(R.string.settings_private_addresses)

    val dnsItems = listOf(
        stringResource(R.string.settings_vpn_local_dns),
        stringResource(R.string.settings_dns_final),
        stringResource(R.string.settings_dns_default_domain_resolver),
        stringResource(R.string.settings_dns_cache_capacity),
        stringResource(R.string.settings_dns_optimistic_cache),
        stringResource(R.string.settings_dns_disable_cache),
        stringResource(R.string.settings_dns_disable_expire),
        stringResource(R.string.settings_dns_timeout),
        stringResource(R.string.settings_dns_section_servers),
    )
    val snifferItems = listOf(
        stringResource(R.string.settings_sniffer_enable),
        stringResource(R.string.settings_sniffer_protocols),
        stringResource(R.string.settings_sniffer_timeout),
    )
    val localProxyItems = listOf(
        stringResource(R.string.settings_local_proxy_port),
        stringResource(R.string.settings_local_proxy_dynamic_port),
        stringResource(R.string.settings_local_proxy_listen_all_interfaces),
        stringResource(R.string.settings_local_proxy_username),
        stringResource(R.string.settings_local_proxy_password),
    )
    val tunItems = listOf(
        stringResource(R.string.settings_tun_stack),
        stringResource(R.string.settings_tun_mtu),
        stringResource(R.string.settings_tun_vpn_dns),
        stringResource(R.string.settings_tun_ipv4_cidr),
        stringResource(R.string.settings_tun_ipv6_cidr),
    )
    val externalItems = if (useEbpfSharedNetwork) {
        listOf(
            stringResource(R.string.settings_ebpf_shared_network_input),
            stringResource(R.string.settings_ebpf_shared_network_description),
        )
    } else {
        listOf(
            stringResource(R.string.settings_external_interfaces_wifi),
            stringResource(R.string.settings_external_interfaces_usb),
            stringResource(R.string.settings_external_interfaces_bluetooth),
            stringResource(R.string.settings_external_interfaces_ethernet),
        )
    }

    return buildList {
        dnsItems.forEach { add(SettingsSearchEntry(it, dns, Icons.Rounded.Dns, onOpenDns)) }
        snifferItems.forEach { add(SettingsSearchEntry(it, sniffer, Icons.Rounded.TravelExplore, onOpenSniffer)) }
        localProxyItems.forEach { add(SettingsSearchEntry(it, localProxy, Icons.Rounded.Router, onOpenLocalProxy)) }
        tunItems.forEach { add(SettingsSearchEntry(it, tun, Icons.Rounded.SettingsInputComponent, onOpenTun)) }
        externalItems.forEach { add(SettingsSearchEntry(it, externalInterfaces, Icons.Rounded.Cable, onOpenExternalInterfaces)) }
        if (!useEbpfSharedNetwork) {
            add(SettingsSearchEntry(ignoredInterfaces, ignoredInterfaces, Icons.Rounded.Block, onOpenIgnoredInterfaces))
            add(SettingsSearchEntry(privateAddresses, privateAddresses, Icons.Rounded.HomeWork, onOpenPrivateAddresses))
        }
    }
}
