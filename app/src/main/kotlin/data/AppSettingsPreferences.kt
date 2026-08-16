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
            ebpfBypassRuleSetTags = preferences.getStringList(
                KeyEbpfBypassRuleSetTags,
                defaults.ebpfBypassRuleSetTags,
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
            ebpfSharedNetworkInterfaces = preferences.getStringList(
                KeyEbpfSharedNetworkInterfaces,
                defaults.ebpfSharedNetworkInterfaces,
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
            putInt(KeyColorMode, state.colorMode)
            putInt(KeyLanguageMode, state.languageMode)
            putInt(KeySeedIndex, state.seedIndex)
            putInt(KeyOutboundListLayout, state.outboundListLayout)
            putInt(KeyOutboundListSort, state.outboundListSort)
            putStringMap(KeySelectorSelections, state.selectorSelections)
            putBoolean(KeyRouteAutoDetectInterface, state.routeAutoDetectInterface)
            putBoolean(KeyRouteOverrideAndroidVpn, state.routeOverrideAndroidVpn)
            putString(KeyRouteDefaultNetworkStrategy, state.routeDefaultNetworkStrategy)
            putStringList(KeyRouteDefaultNetworkTypes, state.routeDefaultNetworkTypes)
            putStringList(
                KeyRouteDefaultFallbackNetworkTypes,
                state.routeDefaultFallbackNetworkTypes,
            )
            putString(KeyRouteDefaultFallbackDelay, state.routeDefaultFallbackDelay)
            putBoolean(KeyRouteFindProcess, state.routeFindProcess)
            putString(KeyRouteFinal, state.routeFinal)
            putInt(KeyRunMode, state.runMode)
            putInt(KeySingBoxMode, state.singBoxMode)
            putInt(KeySingBoxProxyLayout, state.singBoxProxyLayout)
            putInt(KeySingBoxProxySort, state.singBoxProxySort)
            putInt(KeySingBoxTunStack, state.singBoxTunStack)
            putString(KeySingBoxControlPort, state.singBoxControlPort)
            putString(KeySingBoxControlSecret, state.singBoxControlSecret)
            putBoolean(KeyEnableLocalDns, state.enableLocalDns)
            putString(KeyLocalProxyPort, state.localProxyPort)
            putBoolean(KeyEnableDynamicLocalProxyPort, state.enableDynamicLocalProxyPort)
            putBoolean(KeyLocalProxyListenAllInterfaces, state.localProxyListenAllInterfaces)
            putString(KeyLocalProxyUsername, state.localProxyUsername)
            putString(KeyLocalProxyPassword, state.localProxyPassword)
            putBoolean(KeyEnableVpnAppendHttpProxy, state.enableVpnAppendHttpProxy)
            putBoolean(KeyEnableVpnHevTun, state.enableVpnHevTun)
            putString(KeyTunMtu, state.tunMtu)
            putString(KeyTunVpnDns, state.tunVpnDns)
            putString(KeyTunIpv4Cidr, state.tunIpv4Cidr)
            putString(KeyTunIpv6Cidr, state.tunIpv6Cidr)
            putString(KeyCoreLogLevel, state.coreLogLevel)
            putBoolean(KeyEnableTrafficStatsNotification, state.enableTrafficStatsNotification)
            putBoolean(KeyEnableBroadcastControl, state.enableBroadcastControl)
            putInt(KeyResourceFileSource, state.resourceFileSource)
            putString(
                KeyCustomResourceFileGeositeCategoryAdsAllUrl,
                state.customResourceFileGeositeCategoryAdsAllUrl,
            )
            putString(
                KeyCustomResourceFileGeositeGoogleUrl,
                state.customResourceFileGeositeGoogleUrl,
            )
            putString(KeyCustomResourceFileGeositeCnUrl, state.customResourceFileGeositeCnUrl)
            putString(KeyCustomResourceFileGeoipCnUrl, state.customResourceFileGeoipCnUrl)
            putString(
                KeyCustomResourceFileDirectCidrIpv4Url,
                state.customResourceFileDirectCidrIpv4Url,
            )
            putString(
                KeyCustomResourceFileDirectCidrIpv6Url,
                state.customResourceFileDirectCidrIpv6Url,
            )
            putBoolean(KeyEnableSniffer, state.enableSniffer)
            putStringList(KeySnifferProtocols, state.snifferProtocols)
            putString(KeySnifferTimeout, state.snifferTimeout)
            putBoolean(KeyEnableIpv6, state.enableIpv6)
            putBoolean(KeyEnableIpv6Prefer, state.enableIpv6Prefer)
            putString(KeyDnsFinal, state.dnsFinal)
            putString(KeyRouteDefaultDomainResolver, state.routeDefaultDomainResolver)
            putString(KeyDnsCacheCapacity, state.dnsCacheCapacity)
            putBoolean(KeyDnsOptimisticCache, state.dnsOptimisticCache)
            putBoolean(KeyDnsDisableCache, state.dnsDisableCache)
            putBoolean(KeyDnsDisableExpire, state.dnsDisableExpire)
            putString(KeyDnsTimeout, state.dnsTimeout)
            putBoolean(KeyStoreFakeIp, state.storeFakeIp)
            putBoolean(KeyStoreDns, state.storeDns)
            putString(KeyTransparentProxyPort, state.transparentProxyPort)
            putBoolean(KeyEnableRootBootScript, state.enableRootBootScript)
            putBoolean(KeyEnableRootEbpfRules, state.enableRootEbpfRules)
            putBoolean(
                KeyEnableRootEbpfDirectCidrBypass,
                state.enableRootEbpfDirectCidrBypass,
            )
            putStringList(KeyEbpfBypassRuleSetTags, state.ebpfBypassRuleSetTags)
            putBoolean(KeyEnableRootIpv6Disabler, state.enableRootIpv6Disabler)
            putString(KeySocks5ProxyPort, state.socks5ProxyPort)
            putString(KeyBpf2SocksBridgePort, state.bpf2SocksBridgePort)
            putServiceControl(state.serviceControl)
            putStringList(KeyExternalInterfaces, state.externalInterfaces)
            putStringList(KeyEbpfSharedNetworkInterfaces, state.ebpfSharedNetworkInterfaces)
            putStringList(KeyIgnoredInterfaces, state.ignoredInterfaces)
            putStringList(KeyPrivateAddressCidrs, state.privateAddressCidrs)
            putInt(KeyProxyAppListMode, state.proxyAppListMode)
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

    private fun SharedPreferences.Editor.putServiceControl(
        value: ServiceControlSettings,
    ): SharedPreferences.Editor =
        putBoolean(KeyServiceControlEnabled, value.enabled)
            .putBoolean(KeyServiceControlScheduleEnabled, value.schedule.enabled)
            .putString(KeyServiceControlScheduleStartCron, value.schedule.startCron)
            .putString(KeyServiceControlScheduleStopCron, value.schedule.stopCron)
            .putBoolean(KeyServiceControlWifiEnabled, value.wifi.enabled)
            .putServiceControlWifiRule(
                value.wifi.connectStart,
                KeyServiceControlWifiConnectStartEnabled,
                KeyServiceControlWifiConnectStartSsids,
                KeyServiceControlWifiConnectStartBssids,
            )
            .putServiceControlWifiRule(
                value.wifi.connectStop,
                KeyServiceControlWifiConnectStopEnabled,
                KeyServiceControlWifiConnectStopSsids,
                KeyServiceControlWifiConnectStopBssids,
            )
            .putServiceControlWifiRule(
                value.wifi.disconnectStart,
                KeyServiceControlWifiDisconnectStartEnabled,
                KeyServiceControlWifiDisconnectStartSsids,
                KeyServiceControlWifiDisconnectStartBssids,
            )
            .putServiceControlWifiRule(
                value.wifi.disconnectStop,
                KeyServiceControlWifiDisconnectStopEnabled,
                KeyServiceControlWifiDisconnectStopSsids,
                KeyServiceControlWifiDisconnectStopBssids,
            )

    private fun SharedPreferences.Editor.putServiceControlWifiRule(
        value: ServiceControlWifiRule,
        enabledKey: String,
        ssidsKey: String,
        bssidsKey: String,
    ): SharedPreferences.Editor =
        putBoolean(enabledKey, value.enabled)
            .putStringList(ssidsKey, value.ssids)
            .putStringList(bssidsKey, value.bssids)

    private fun SharedPreferences.getStringList(
        key: String,
        defaultValue: List<String>,
    ): List<String> {
        return getString(key, null)?.let(StringListJson::decode) ?: defaultValue
    }

    private fun SharedPreferences.Editor.putStringList(
        key: String,
        values: List<String>,
    ): SharedPreferences.Editor {
        return putString(key, StringListJson.encode(values))
    }

    private fun SharedPreferences.getStringMap(
        key: String,
        defaultValue: Map<String, String>,
    ): Map<String, String> {
        return getString(key, null)?.let(StringMapJson::decode) ?: defaultValue
    }

    private fun SharedPreferences.Editor.putStringMap(
        key: String,
        values: Map<String, String>,
    ): SharedPreferences.Editor {
        return putString(key, StringMapJson.encode(values))
    }
}

private const val PreferencesName = "asteriskbox_settings"
private const val ObsoleteSettingsPayloadKey = "settings"
private const val KeyColorMode = "color_mode"
private const val KeyLanguageMode = "language_mode"
private const val KeySeedIndex = "seed_index"
private const val KeySubscriptionHwid = "subscription_hwid"
private const val KeyOutboundListLayout = "outbound_list_layout"
private const val KeyOutboundListSort = "outbound_list_sort"
private const val KeySelectorSelections = "selector_selections"
private const val KeyRouteAutoDetectInterface = "route_auto_detect_interface"
private const val KeyRouteOverrideAndroidVpn = "route_override_android_vpn"
private const val KeyRouteDefaultNetworkStrategy = "route_default_network_strategy"
private const val KeyRouteDefaultNetworkTypes = "route_default_network_types"
private const val KeyRouteDefaultFallbackNetworkTypes = "route_default_fallback_network_types"
private const val KeyRouteDefaultFallbackDelay = "route_default_fallback_delay"
private const val KeyRouteFindProcess = "route_find_process"
private const val KeyRouteFinal = "route_final"
private const val KeyRunMode = "run_mode"
private const val KeySingBoxMode = "sing_box_mode"
private const val KeySingBoxProxyLayout = "sing_box_proxy_layout"
private const val KeySingBoxProxySort = "sing_box_proxy_sort"
private const val KeySingBoxTunStack = "sing_box_tun_stack"
private const val KeySingBoxControlPort = "sing_box_control_port"
private const val KeySingBoxControlSecret = "sing_box_control_secret"
private const val KeyEnableLocalDns = "enable_local_dns"
private const val KeyLocalProxyPort = "local_proxy_port"
private const val KeyEnableDynamicLocalProxyPort = "enable_dynamic_local_proxy_port"
private const val KeyLocalProxyListenAllInterfaces = "local_proxy_listen_all_interfaces"
private const val KeyLocalProxyUsername = "local_proxy_username"
private const val KeyLocalProxyPassword = "local_proxy_password"
private const val KeyEnableVpnAppendHttpProxy = "enable_vpn_append_http_proxy"
private const val KeyEnableVpnHevTun = "enable_vpn_hev_tun"
private const val KeyTunMtu = "tun_mtu"
private const val KeyTunVpnDns = "tun_vpn_dns"
private const val KeyTunIpv4Cidr = "tun_ipv4_cidr"
private const val KeyTunIpv6Cidr = "tun_ipv6_cidr"
private const val KeyCoreLogLevel = "core_log_level"
private const val KeyEnableTrafficStatsNotification = "enable_traffic_stats_notification"
private const val KeyEnableBroadcastControl = "enable_broadcast_control"
private const val KeyResourceFileSource = "resource_file_source"
private const val KeyCustomResourceFileGeositeCategoryAdsAllUrl =
    "custom_resource_file_geosite_category_ads_all_url"
private const val KeyCustomResourceFileGeositeGoogleUrl =
    "custom_resource_file_geosite_google_url"
private const val KeyCustomResourceFileGeositeCnUrl = "custom_resource_file_geosite_cn_url"
private const val KeyCustomResourceFileGeoipCnUrl = "custom_resource_file_geoip_cn_url"
private const val KeyCustomResourceFileDirectCidrIpv4Url =
    "custom_resource_file_direct_cidr_ipv4_url"
private const val KeyCustomResourceFileDirectCidrIpv6Url =
    "custom_resource_file_direct_cidr_ipv6_url"
private const val KeyEnableSniffer = "enable_sniffer"
private const val KeySnifferProtocols = "sniffer_protocols"
private const val KeySnifferTimeout = "sniffer_timeout"
private const val KeyEnableIpv6 = "enable_ipv6"
private const val KeyEnableIpv6Prefer = "enable_ipv6_prefer"
private const val KeyDnsFinal = "dns_final"
private const val KeyRouteDefaultDomainResolver = "route_default_domain_resolver"
private const val KeyDnsCacheCapacity = "dns_cache_capacity"
private const val KeyDnsOptimisticCache = "dns_optimistic_cache"
private const val KeyDnsDisableCache = "dns_disable_cache"
private const val KeyDnsDisableExpire = "dns_disable_expire"
private const val KeyDnsTimeout = "dns_timeout"
private const val KeyStoreFakeIp = "store_fake_ip"
private const val KeyStoreDns = "store_dns"
private const val KeyTransparentProxyPort = "transparent_proxy_port"
private const val KeyEnableRootBootScript = "enable_root_boot_script"
private const val KeyEnableRootEbpfRules = "enable_root_ebpf_rules"
private const val KeyEnableRootEbpfDirectCidrBypass = "enable_root_ebpf_direct_cidr_bypass"
private const val KeyEbpfBypassRuleSetTags = "ebpf_bypass_rule_set_tags"
private const val KeyEnableRootIpv6Disabler = "enable_root_ipv6_disabler"
private const val KeySocks5ProxyPort = "socks5_proxy_port"
private const val KeyBpf2SocksBridgePort = "bpf2socks_bridge_port"
private const val KeyServiceControlEnabled = "service_control_enabled"
private const val KeyServiceControlScheduleEnabled = "service_control_schedule_enabled"
private const val KeyServiceControlScheduleStartCron = "service_control_schedule_start_cron"
private const val KeyServiceControlScheduleStopCron = "service_control_schedule_stop_cron"
private const val KeyServiceControlWifiEnabled = "service_control_wifi_enabled"
private const val KeyServiceControlWifiConnectStartEnabled = "service_control_wifi_connect_start_enabled"
private const val KeyServiceControlWifiConnectStartSsids = "service_control_wifi_connect_start_ssids"
private const val KeyServiceControlWifiConnectStartBssids = "service_control_wifi_connect_start_bssids"
private const val KeyServiceControlWifiConnectStopEnabled = "service_control_wifi_connect_stop_enabled"
private const val KeyServiceControlWifiConnectStopSsids = "service_control_wifi_connect_stop_ssids"
private const val KeyServiceControlWifiConnectStopBssids = "service_control_wifi_connect_stop_bssids"
private const val KeyServiceControlWifiDisconnectStartEnabled = "service_control_wifi_disconnect_start_enabled"
private const val KeyServiceControlWifiDisconnectStartSsids = "service_control_wifi_disconnect_start_ssids"
private const val KeyServiceControlWifiDisconnectStartBssids = "service_control_wifi_disconnect_start_bssids"
private const val KeyServiceControlWifiDisconnectStopEnabled = "service_control_wifi_disconnect_stop_enabled"
private const val KeyServiceControlWifiDisconnectStopSsids = "service_control_wifi_disconnect_stop_ssids"
private const val KeyServiceControlWifiDisconnectStopBssids = "service_control_wifi_disconnect_stop_bssids"
private const val KeyExternalInterfaces = "external_interfaces"
private const val KeyEbpfSharedNetworkInterfaces = "ebpf_shared_network_interfaces"
private const val KeyIgnoredInterfaces = "ignored_interfaces"
private const val KeyPrivateAddressCidrs = "private_address_cidrs"
private const val KeyProxyAppListMode = "proxy_app_list_mode"

private val SubscriptionHwidLock = Any()
