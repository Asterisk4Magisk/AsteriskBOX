// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package data

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import app.AppState
import app.ServiceControlSchedule
import app.ServiceControlSettings
import app.ServiceControlWifi
import app.ServiceControlWifiRule
import features.settings.servicecontrol.normalizeServiceControlSettings
import java.util.UUID

internal class AppSettingsPreferences(
    private val preferences: SharedPreferences,
) {
    constructor(context: Context) : this(
        context.getSharedPreferences(PreferencesName, Context.MODE_PRIVATE),
    )

    @SuppressLint("UseKtx")
    fun getOrCreateSubscriptionHwid(): String {
        synchronized(SubscriptionHwidLock) {
            preferences.getString(KeySubscriptionHwid, null)
                ?.trim()
                ?.takeIf(String::isNotEmpty)
                ?.let { return it }

            val generated = UUID.randomUUID().toString()
            check(preferences.edit().putString(KeySubscriptionHwid, generated).commit()) {
                "Failed to persist subscription HWID"
            }
            return generated
        }
    }

    fun load(): AppState {
        val defaults = AppState(singBoxControlSecret = UUID.randomUUID().toString())
        val singBoxControlSecret = preferences
            .getString(KeySingBoxControlSecret, null)
            ?.takeIf(String::isNotBlank)
            ?: defaults.singBoxControlSecret.also { secret ->
                preferences.edit { putString(KeySingBoxControlSecret, secret) }
            }
        return defaults.copy(
            colorMode = preferences.getInt(KeyColorMode, defaults.colorMode),
            languageMode = preferences.getInt(KeyLanguageMode, defaults.languageMode),
            seedIndex = preferences.getInt(KeySeedIndex, defaults.seedIndex),
            outboundListLayout = preferences.getInt(
                KeyOutboundListLayout,
                defaults.outboundListLayout,
            ),
            outboundListSort = preferences.getInt(KeyOutboundListSort, defaults.outboundListSort),
            selectorSelections = preferences.getStringMap(
                KeySelectorSelections,
                defaults.selectorSelections,
            ),
            routeAutoDetectInterface = preferences.getBoolean(
                KeyRouteAutoDetectInterface,
                defaults.routeAutoDetectInterface,
            ),
            routeOverrideAndroidVpn = preferences.getBoolean(
                KeyRouteOverrideAndroidVpn,
                defaults.routeOverrideAndroidVpn,
            ),
            routeDefaultNetworkStrategy = preferences.getString(
                KeyRouteDefaultNetworkStrategy,
                defaults.routeDefaultNetworkStrategy,
            ) ?: defaults.routeDefaultNetworkStrategy,
            routeDefaultNetworkTypes = preferences.getStringList(
                KeyRouteDefaultNetworkTypes,
                defaults.routeDefaultNetworkTypes,
            ),
            routeDefaultFallbackNetworkTypes = preferences.getStringList(
                KeyRouteDefaultFallbackNetworkTypes,
                defaults.routeDefaultFallbackNetworkTypes,
            ),
            routeDefaultFallbackDelay = preferences.getString(
                KeyRouteDefaultFallbackDelay,
                defaults.routeDefaultFallbackDelay,
            ) ?: defaults.routeDefaultFallbackDelay,
            routeFindProcess = preferences.getBoolean(
                KeyRouteFindProcess,
                defaults.routeFindProcess,
            ),
            routeFinal = preferences.getString(KeyRouteFinal, defaults.routeFinal)
                ?: defaults.routeFinal,
            runMode = preferences.getInt(KeyRunMode, defaults.runMode),
            singBoxMode = preferences.getInt(KeySingBoxMode, defaults.singBoxMode),
            singBoxProxyLayout = preferences.getInt(
                KeySingBoxProxyLayout,
                defaults.singBoxProxyLayout,
            ),
            singBoxProxySort = preferences.getInt(
                KeySingBoxProxySort,
                defaults.singBoxProxySort,
            ),
            singBoxTunStack = preferences.getInt(KeySingBoxTunStack, defaults.singBoxTunStack),
            singBoxControlPort = preferences.getString(
                KeySingBoxControlPort,
                defaults.singBoxControlPort,
            ) ?: defaults.singBoxControlPort,
            singBoxControlSecret = singBoxControlSecret,
            enableLocalDns = preferences.getBoolean(KeyEnableLocalDns, defaults.enableLocalDns),
            localProxyPort = preferences.getString(KeyLocalProxyPort, defaults.localProxyPort)
                ?: defaults.localProxyPort,
            enableDynamicLocalProxyPort = preferences.getBoolean(
                KeyEnableDynamicLocalProxyPort,
                defaults.enableDynamicLocalProxyPort,
            ),
            localProxyListenAllInterfaces = preferences.getBoolean(
                KeyLocalProxyListenAllInterfaces,
                defaults.localProxyListenAllInterfaces,
            ),
            localProxyUsername = preferences.getString(
                KeyLocalProxyUsername,
                defaults.localProxyUsername,
            ) ?: defaults.localProxyUsername,
            localProxyPassword = preferences.getString(
                KeyLocalProxyPassword,
                defaults.localProxyPassword,
            ) ?: defaults.localProxyPassword,
            enableVpnAppendHttpProxy = preferences.getBoolean(
                KeyEnableVpnAppendHttpProxy,
                defaults.enableVpnAppendHttpProxy,
            ),
            enableVpnHevTun = preferences.getBoolean(
                KeyEnableVpnHevTun,
                defaults.enableVpnHevTun,
            ),
            tunMtu = preferences.getString(KeyTunMtu, defaults.tunMtu) ?: defaults.tunMtu,
            tunVpnDns = preferences.getString(KeyTunVpnDns, defaults.tunVpnDns)
                ?: defaults.tunVpnDns,
            tunIpv4Cidr = preferences.getString(KeyTunIpv4Cidr, defaults.tunIpv4Cidr)
                ?: defaults.tunIpv4Cidr,
            tunIpv6Cidr = preferences.getString(KeyTunIpv6Cidr, defaults.tunIpv6Cidr)
                ?: defaults.tunIpv6Cidr,
            coreLogLevel = preferences.getString(KeyCoreLogLevel, defaults.coreLogLevel)
                ?: defaults.coreLogLevel,
            enableTrafficStatsNotification = preferences.getBoolean(
                KeyEnableTrafficStatsNotification,
                defaults.enableTrafficStatsNotification,
            ),
            enableBroadcastControl = preferences.getBoolean(
                KeyEnableBroadcastControl,
                defaults.enableBroadcastControl,
            ),
            resourceFileSource = preferences.getInt(
                KeyResourceFileSource,
                defaults.resourceFileSource,
            ),
            customResourceFileGeositeCategoryAdsAllUrl = preferences.getString(
                KeyCustomResourceFileGeositeCategoryAdsAllUrl,
                defaults.customResourceFileGeositeCategoryAdsAllUrl,
            ) ?: defaults.customResourceFileGeositeCategoryAdsAllUrl,
            customResourceFileGeositeGoogleUrl = preferences.getString(
                KeyCustomResourceFileGeositeGoogleUrl,
                defaults.customResourceFileGeositeGoogleUrl,
            ) ?: defaults.customResourceFileGeositeGoogleUrl,
            customResourceFileGeositeCnUrl = preferences.getString(
                KeyCustomResourceFileGeositeCnUrl,
                defaults.customResourceFileGeositeCnUrl,
            ) ?: defaults.customResourceFileGeositeCnUrl,
            customResourceFileGeoipCnUrl = preferences.getString(
                KeyCustomResourceFileGeoipCnUrl,
                defaults.customResourceFileGeoipCnUrl,
            ) ?: defaults.customResourceFileGeoipCnUrl,
            customResourceFileDirectCidrIpv4Url = preferences.getString(
                KeyCustomResourceFileDirectCidrIpv4Url,
                defaults.customResourceFileDirectCidrIpv4Url,
            ) ?: defaults.customResourceFileDirectCidrIpv4Url,
            customResourceFileDirectCidrIpv6Url = preferences.getString(
                KeyCustomResourceFileDirectCidrIpv6Url,
                defaults.customResourceFileDirectCidrIpv6Url,
            ) ?: defaults.customResourceFileDirectCidrIpv6Url,
            enableSniffer = preferences.getBoolean(KeyEnableSniffer, defaults.enableSniffer),
            snifferProtocols = preferences.getStringList(
                KeySnifferProtocols,
                defaults.snifferProtocols,
            ),
            snifferTimeout = preferences.getString(KeySnifferTimeout, defaults.snifferTimeout)
                ?: defaults.snifferTimeout,
            enableIpv6 = preferences.getBoolean(KeyEnableIpv6, defaults.enableIpv6),
            enableIpv6Prefer = preferences.getBoolean(
                KeyEnableIpv6Prefer,
                defaults.enableIpv6Prefer,
            ),
            dnsFinal = preferences.getString(KeyDnsFinal, defaults.dnsFinal) ?: defaults.dnsFinal,
            routeDefaultDomainResolver = preferences.getString(
                KeyRouteDefaultDomainResolver,
                defaults.routeDefaultDomainResolver,
            ) ?: defaults.routeDefaultDomainResolver,
            dnsCacheCapacity = preferences.getString(
                KeyDnsCacheCapacity,
                defaults.dnsCacheCapacity,
            ) ?: defaults.dnsCacheCapacity,
            dnsOptimisticCache = preferences.getBoolean(
                KeyDnsOptimisticCache,
                defaults.dnsOptimisticCache,
            ),
            dnsDisableCache = preferences.getBoolean(
                KeyDnsDisableCache,
                defaults.dnsDisableCache,
            ),
            dnsDisableExpire = preferences.getBoolean(
                KeyDnsDisableExpire,
                defaults.dnsDisableExpire,
            ),
            dnsTimeout = preferences.getString(KeyDnsTimeout, defaults.dnsTimeout)
                ?: defaults.dnsTimeout,
            storeFakeIp = preferences.getBoolean(KeyStoreFakeIp, defaults.storeFakeIp),
            storeDns = preferences.getBoolean(KeyStoreDns, defaults.storeDns),
            transparentProxyPort = preferences.getString(
                KeyTransparentProxyPort,
                defaults.transparentProxyPort,
            ) ?: defaults.transparentProxyPort,
            enableRootBootScript = preferences.getBoolean(
                KeyEnableRootBootScript,
                defaults.enableRootBootScript,
            ),
            enableRootEbpfRules = preferences.getBoolean(
                KeyEnableRootEbpfRules,
                defaults.enableRootEbpfRules,
            ),
            enableRootEbpfDirectCidrBypass = preferences.getBoolean(
                KeyEnableRootEbpfDirectCidrBypass,
                defaults.enableRootEbpfDirectCidrBypass,
            ),
            tunBypassRuleSetTags = preferences.getStringList(
                if (preferences.contains(KeyTunBypassRuleSetTags)) {
                    KeyTunBypassRuleSetTags
                } else {
                    LegacyKeyEbpfBypassRuleSetTags
                },
                defaults.tunBypassRuleSetTags,
            ),
            enableRootIpv6Disabler = preferences.getBoolean(
                KeyEnableRootIpv6Disabler,
                defaults.enableRootIpv6Disabler,
            ),
            socks5ProxyPort = preferences.getString(KeySocks5ProxyPort, defaults.socks5ProxyPort)
                ?: defaults.socks5ProxyPort,
            bpf2SocksBridgePort = preferences.getString(
                KeyBpf2SocksBridgePort,
                defaults.bpf2SocksBridgePort,
            ) ?: defaults.bpf2SocksBridgePort,
            serviceControl = preferences.getServiceControl(defaults.serviceControl),
            externalInterfaces = preferences.getStringList(
                KeyExternalInterfaces,
                defaults.externalInterfaces,
            ),
            tunSharedNetworkInterfaces = preferences.getStringList(
                if (preferences.contains(KeyTunSharedNetworkInterfaces)) {
                    KeyTunSharedNetworkInterfaces
                } else {
                    LegacyKeyEbpfSharedNetworkInterfaces
                },
                defaults.tunSharedNetworkInterfaces,
            ),
            ignoredInterfaces = preferences.getStringList(
                KeyIgnoredInterfaces,
                defaults.ignoredInterfaces,
            ),
            privateAddressCidrs = preferences.getStringList(
                KeyPrivateAddressCidrs,
                defaults.privateAddressCidrs,
            ),
            proxyAppListMode = preferences.getInt(
                KeyProxyAppListMode,
                defaults.proxyAppListMode,
            ),
        )
    }

    fun save(state: AppState) {
        preferences.edit {
            remove(ObsoleteSettingsPayloadKey)
            state.preferenceValues().forEach { (key, value) -> putPreferenceValue(key, value) }
        }
    }

    fun saveChanged(previous: AppState, next: AppState) {
        val changed = changedPreferenceValues(previous, next)
        if (changed.isEmpty()) return
        preferences.edit {
            changed.forEach { (key, value) -> putPreferenceValue(key, value) }
        }
    }

    private fun SharedPreferences.Editor.putPreferenceValue(
        key: String,
        value: AppPreferenceValue,
    ) {
        when (value) {
            is AppPreferenceValue.BooleanValue -> putBoolean(key, value.value)
            is AppPreferenceValue.IntValue -> putInt(key, value.value)
            is AppPreferenceValue.StringValue -> putString(key, value.value)
        }
    }

    private fun SharedPreferences.getServiceControl(
        defaults: ServiceControlSettings,
    ): ServiceControlSettings = normalizeServiceControlSettings(
        ServiceControlSettings(
            enabled = getBoolean(KeyServiceControlEnabled, defaults.enabled),
            schedule = ServiceControlSchedule(
                enabled = getBoolean(KeyServiceControlScheduleEnabled, defaults.schedule.enabled),
                startCron = getString(KeyServiceControlScheduleStartCron, defaults.schedule.startCron)
                    ?: defaults.schedule.startCron,
                stopCron = getString(KeyServiceControlScheduleStopCron, defaults.schedule.stopCron)
                    ?: defaults.schedule.stopCron,
            ),
            wifi = ServiceControlWifi(
                enabled = getBoolean(KeyServiceControlWifiEnabled, defaults.wifi.enabled),
                connectStart = getServiceControlWifiRule(
                    defaults.wifi.connectStart,
                    KeyServiceControlWifiConnectStartEnabled,
                    KeyServiceControlWifiConnectStartSsids,
                    KeyServiceControlWifiConnectStartBssids,
                ),
                connectStop = getServiceControlWifiRule(
                    defaults.wifi.connectStop,
                    KeyServiceControlWifiConnectStopEnabled,
                    KeyServiceControlWifiConnectStopSsids,
                    KeyServiceControlWifiConnectStopBssids,
                ),
                disconnectStart = getServiceControlWifiRule(
                    defaults.wifi.disconnectStart,
                    KeyServiceControlWifiDisconnectStartEnabled,
                    KeyServiceControlWifiDisconnectStartSsids,
                    KeyServiceControlWifiDisconnectStartBssids,
                ),
                disconnectStop = getServiceControlWifiRule(
                    defaults.wifi.disconnectStop,
                    KeyServiceControlWifiDisconnectStopEnabled,
                    KeyServiceControlWifiDisconnectStopSsids,
                    KeyServiceControlWifiDisconnectStopBssids,
                ),
            ),
        ),
    )

    private fun SharedPreferences.getServiceControlWifiRule(
        defaults: ServiceControlWifiRule,
        enabledKey: String,
        ssidsKey: String,
        bssidsKey: String,
    ): ServiceControlWifiRule = ServiceControlWifiRule(
        enabled = getBoolean(enabledKey, defaults.enabled),
        ssids = getStringList(ssidsKey, defaults.ssids),
        bssids = getStringList(bssidsKey, defaults.bssids),
    )

    private fun SharedPreferences.getStringList(
        key: String,
        defaultValue: List<String>,
    ): List<String> {
        return getString(key, null)?.let(StringListJson::decode) ?: defaultValue
    }

    private fun SharedPreferences.getStringMap(
        key: String,
        defaultValue: Map<String, String>,
    ): Map<String, String> {
        return getString(key, null)?.let(StringMapJson::decode) ?: defaultValue
    }

}

private const val PreferencesName = "asteriskbox_settings"
private const val ObsoleteSettingsPayloadKey = "settings"
internal const val KeyColorMode = "color_mode"
internal const val KeyLanguageMode = "language_mode"
internal const val KeySeedIndex = "seed_index"
private const val KeySubscriptionHwid = "subscription_hwid"
internal const val KeyOutboundListLayout = "outbound_list_layout"
internal const val KeyOutboundListSort = "outbound_list_sort"
internal const val KeySelectorSelections = "selector_selections"
internal const val KeyRouteAutoDetectInterface = "route_auto_detect_interface"
internal const val KeyRouteOverrideAndroidVpn = "route_override_android_vpn"
internal const val KeyRouteDefaultNetworkStrategy = "route_default_network_strategy"
internal const val KeyRouteDefaultNetworkTypes = "route_default_network_types"
internal const val KeyRouteDefaultFallbackNetworkTypes = "route_default_fallback_network_types"
internal const val KeyRouteDefaultFallbackDelay = "route_default_fallback_delay"
internal const val KeyRouteFindProcess = "route_find_process"
internal const val KeyRouteFinal = "route_final"
internal const val KeyRunMode = "run_mode"
internal const val KeySingBoxMode = "sing_box_mode"
internal const val KeySingBoxProxyLayout = "sing_box_proxy_layout"
internal const val KeySingBoxProxySort = "sing_box_proxy_sort"
internal const val KeySingBoxTunStack = "sing_box_tun_stack"
internal const val KeySingBoxControlPort = "sing_box_control_port"
internal const val KeySingBoxControlSecret = "sing_box_control_secret"
internal const val KeyEnableLocalDns = "enable_local_dns"
internal const val KeyLocalProxyPort = "local_proxy_port"
internal const val KeyEnableDynamicLocalProxyPort = "enable_dynamic_local_proxy_port"
internal const val KeyLocalProxyListenAllInterfaces = "local_proxy_listen_all_interfaces"
internal const val KeyLocalProxyUsername = "local_proxy_username"
internal const val KeyLocalProxyPassword = "local_proxy_password"
internal const val KeyEnableVpnAppendHttpProxy = "enable_vpn_append_http_proxy"
internal const val KeyEnableVpnHevTun = "enable_vpn_hev_tun"
internal const val KeyTunMtu = "tun_mtu"
internal const val KeyTunVpnDns = "tun_vpn_dns"
internal const val KeyTunIpv4Cidr = "tun_ipv4_cidr"
internal const val KeyTunIpv6Cidr = "tun_ipv6_cidr"
internal const val KeyCoreLogLevel = "core_log_level"
internal const val KeyEnableTrafficStatsNotification = "enable_traffic_stats_notification"
internal const val KeyEnableBroadcastControl = "enable_broadcast_control"
internal const val KeyResourceFileSource = "resource_file_source"
internal const val KeyCustomResourceFileGeositeCategoryAdsAllUrl =
    "custom_resource_file_geosite_category_ads_all_url"
internal const val KeyCustomResourceFileGeositeGoogleUrl =
    "custom_resource_file_geosite_google_url"
internal const val KeyCustomResourceFileGeositeCnUrl = "custom_resource_file_geosite_cn_url"
internal const val KeyCustomResourceFileGeoipCnUrl = "custom_resource_file_geoip_cn_url"
internal const val KeyCustomResourceFileDirectCidrIpv4Url =
    "custom_resource_file_direct_cidr_ipv4_url"
internal const val KeyCustomResourceFileDirectCidrIpv6Url =
    "custom_resource_file_direct_cidr_ipv6_url"
internal const val KeyEnableSniffer = "enable_sniffer"
internal const val KeySnifferProtocols = "sniffer_protocols"
internal const val KeySnifferTimeout = "sniffer_timeout"
internal const val KeyEnableIpv6 = "enable_ipv6"
internal const val KeyEnableIpv6Prefer = "enable_ipv6_prefer"
internal const val KeyDnsFinal = "dns_final"
internal const val KeyRouteDefaultDomainResolver = "route_default_domain_resolver"
internal const val KeyDnsCacheCapacity = "dns_cache_capacity"
internal const val KeyDnsOptimisticCache = "dns_optimistic_cache"
internal const val KeyDnsDisableCache = "dns_disable_cache"
internal const val KeyDnsDisableExpire = "dns_disable_expire"
internal const val KeyDnsTimeout = "dns_timeout"
internal const val KeyStoreFakeIp = "store_fake_ip"
internal const val KeyStoreDns = "store_dns"
internal const val KeyTransparentProxyPort = "transparent_proxy_port"
internal const val KeyEnableRootBootScript = "enable_root_boot_script"
internal const val KeyEnableRootEbpfRules = "enable_root_ebpf_rules"
internal const val KeyEnableRootEbpfDirectCidrBypass = "enable_root_ebpf_direct_cidr_bypass"
internal const val KeyTunBypassRuleSetTags = "tun_bypass_rule_set_tags"
private const val LegacyKeyEbpfBypassRuleSetTags = "ebpf_bypass_rule_set_tags"
internal const val KeyEnableRootIpv6Disabler = "enable_root_ipv6_disabler"
internal const val KeySocks5ProxyPort = "socks5_proxy_port"
internal const val KeyBpf2SocksBridgePort = "bpf2socks_bridge_port"
internal const val KeyServiceControlEnabled = "service_control_enabled"
internal const val KeyServiceControlScheduleEnabled = "service_control_schedule_enabled"
internal const val KeyServiceControlScheduleStartCron = "service_control_schedule_start_cron"
internal const val KeyServiceControlScheduleStopCron = "service_control_schedule_stop_cron"
internal const val KeyServiceControlWifiEnabled = "service_control_wifi_enabled"
internal const val KeyServiceControlWifiConnectStartEnabled = "service_control_wifi_connect_start_enabled"
internal const val KeyServiceControlWifiConnectStartSsids = "service_control_wifi_connect_start_ssids"
internal const val KeyServiceControlWifiConnectStartBssids = "service_control_wifi_connect_start_bssids"
internal const val KeyServiceControlWifiConnectStopEnabled = "service_control_wifi_connect_stop_enabled"
internal const val KeyServiceControlWifiConnectStopSsids = "service_control_wifi_connect_stop_ssids"
internal const val KeyServiceControlWifiConnectStopBssids = "service_control_wifi_connect_stop_bssids"
internal const val KeyServiceControlWifiDisconnectStartEnabled = "service_control_wifi_disconnect_start_enabled"
internal const val KeyServiceControlWifiDisconnectStartSsids = "service_control_wifi_disconnect_start_ssids"
internal const val KeyServiceControlWifiDisconnectStartBssids = "service_control_wifi_disconnect_start_bssids"
internal const val KeyServiceControlWifiDisconnectStopEnabled = "service_control_wifi_disconnect_stop_enabled"
internal const val KeyServiceControlWifiDisconnectStopSsids = "service_control_wifi_disconnect_stop_ssids"
internal const val KeyServiceControlWifiDisconnectStopBssids = "service_control_wifi_disconnect_stop_bssids"
internal const val KeyExternalInterfaces = "external_interfaces"
internal const val KeyTunSharedNetworkInterfaces = "tun_shared_network_interfaces"
private const val LegacyKeyEbpfSharedNetworkInterfaces = "ebpf_shared_network_interfaces"
internal const val KeyIgnoredInterfaces = "ignored_interfaces"
internal const val KeyPrivateAddressCidrs = "private_address_cidrs"
internal const val KeyProxyAppListMode = "proxy_app_list_mode"

private val SubscriptionHwidLock = Any()
