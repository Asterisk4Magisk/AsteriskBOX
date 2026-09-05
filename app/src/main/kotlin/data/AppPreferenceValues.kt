// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package data

import app.AppState

internal sealed interface AppPreferenceValue {
    data class IntValue(val value: Int) : AppPreferenceValue
    data class BooleanValue(val value: Boolean) : AppPreferenceValue
    data class StringValue(val value: String) : AppPreferenceValue
}

internal fun changedPreferenceValues(
    previous: AppState,
    next: AppState,
): Map<String, AppPreferenceValue> {
    val previousValues = previous.preferenceValues()
    return next.preferenceValues().filter { (key, value) -> previousValues[key] != value }
}

internal fun AppState.preferenceValues(): Map<String, AppPreferenceValue> = buildMap {
    fun int(key: String, value: Int) = put(key, AppPreferenceValue.IntValue(value))
    fun boolean(key: String, value: Boolean) = put(key, AppPreferenceValue.BooleanValue(value))
    fun string(key: String, value: String) = put(key, AppPreferenceValue.StringValue(value))
    fun stringList(key: String, value: List<String>) = string(key, StringListJson.encode(value))
    fun serviceControlWifiRule(
        rule: app.ServiceControlWifiRule,
        enabledKey: String,
        ssidsKey: String,
        bssidsKey: String,
    ) {
        boolean(enabledKey, rule.enabled)
        stringList(ssidsKey, rule.ssids)
        stringList(bssidsKey, rule.bssids)
    }

    int(KeyColorMode, colorMode)
    int(KeyLanguageMode, languageMode)
    int(KeySeedIndex, seedIndex)
    int(KeyOutboundListLayout, outboundListLayout)
    int(KeyOutboundListSort, outboundListSort)
    string(KeySelectorSelections, StringMapJson.encode(selectorSelections))
    boolean(KeyRouteAutoDetectInterface, routeAutoDetectInterface)
    boolean(KeyRouteOverrideAndroidVpn, routeOverrideAndroidVpn)
    string(KeyRouteDefaultNetworkStrategy, routeDefaultNetworkStrategy)
    stringList(KeyRouteDefaultNetworkTypes, routeDefaultNetworkTypes)
    stringList(KeyRouteDefaultFallbackNetworkTypes, routeDefaultFallbackNetworkTypes)
    string(KeyRouteDefaultFallbackDelay, routeDefaultFallbackDelay)
    boolean(KeyRouteFindProcess, routeFindProcess)
    string(KeyRouteFinal, routeFinal)
    int(KeyRunMode, runMode)
    int(KeySingBoxMode, singBoxMode)
    int(KeySingBoxProxyLayout, singBoxProxyLayout)
    int(KeySingBoxProxySort, singBoxProxySort)
    int(KeySingBoxTunStack, singBoxTunStack)
    string(KeySingBoxControlPort, singBoxControlPort)
    string(KeySingBoxControlSecret, singBoxControlSecret)
    boolean(KeyEnableLocalDns, enableLocalDns)
    string(KeyLocalProxyPort, localProxyPort)
    boolean(KeyEnableDynamicLocalProxyPort, enableDynamicLocalProxyPort)
    boolean(KeyLocalProxyListenAllInterfaces, localProxyListenAllInterfaces)
    string(KeyLocalProxyUsername, localProxyUsername)
    string(KeyLocalProxyPassword, localProxyPassword)
    boolean(KeyEnableVpnAppendHttpProxy, enableVpnAppendHttpProxy)
    boolean(KeyEnableVpnHevTun, enableVpnHevTun)
    string(KeyTunMtu, tunMtu)
    string(KeyTunVpnDns, tunVpnDns)
    string(KeyTunIpv4Cidr, tunIpv4Cidr)
    string(KeyTunIpv6Cidr, tunIpv6Cidr)
    string(KeyCoreLogLevel, coreLogLevel)
    boolean(KeyEnableTrafficStatsNotification, enableTrafficStatsNotification)
    boolean(KeyEnableBroadcastControl, enableBroadcastControl)
    int(KeyResourceFileSource, resourceFileSource)
    string(KeyCustomResourceFileGeositeCategoryAdsAllUrl, customResourceFileGeositeCategoryAdsAllUrl)
    string(KeyCustomResourceFileGeositeGoogleUrl, customResourceFileGeositeGoogleUrl)
    string(KeyCustomResourceFileGeositeCnUrl, customResourceFileGeositeCnUrl)
    string(KeyCustomResourceFileGeoipCnUrl, customResourceFileGeoipCnUrl)
    string(KeyCustomResourceFileDirectCidrIpv4Url, customResourceFileDirectCidrIpv4Url)
    string(KeyCustomResourceFileDirectCidrIpv6Url, customResourceFileDirectCidrIpv6Url)
    boolean(KeyEnableSniffer, enableSniffer)
    stringList(KeySnifferProtocols, snifferProtocols)
    string(KeySnifferTimeout, snifferTimeout)
    boolean(KeyEnableIpv6, enableIpv6)
    boolean(KeyEnableIpv6Prefer, enableIpv6Prefer)
    string(KeyDnsFinal, dnsFinal)
    string(KeyRouteDefaultDomainResolver, routeDefaultDomainResolver)
    string(KeyDnsCacheCapacity, dnsCacheCapacity)
    boolean(KeyDnsOptimisticCache, dnsOptimisticCache)
    boolean(KeyDnsDisableCache, dnsDisableCache)
    boolean(KeyDnsDisableExpire, dnsDisableExpire)
    string(KeyDnsTimeout, dnsTimeout)
    boolean(KeyStoreFakeIp, storeFakeIp)
    boolean(KeyStoreDns, storeDns)
    string(KeyTransparentProxyPort, transparentProxyPort)
    boolean(KeyEnableRootBootScript, enableRootBootScript)
    boolean(KeyEnableRootEbpfRules, enableRootEbpfRules)
    boolean(KeyEnableRootEbpfDirectCidrBypass, enableRootEbpfDirectCidrBypass)
    stringList(KeyTunBypassRuleSetTags, tunBypassRuleSetTags)
    boolean(KeyEnableRootIpv6Disabler, enableRootIpv6Disabler)
    string(KeySocks5ProxyPort, socks5ProxyPort)
    string(KeyBpf2SocksBridgePort, bpf2SocksBridgePort)
    boolean(KeyServiceControlEnabled, serviceControl.enabled)
    boolean(KeyServiceControlScheduleEnabled, serviceControl.schedule.enabled)
    string(KeyServiceControlScheduleStartCron, serviceControl.schedule.startCron)
    string(KeyServiceControlScheduleStopCron, serviceControl.schedule.stopCron)
    boolean(KeyServiceControlWifiEnabled, serviceControl.wifi.enabled)
    serviceControlWifiRule(
        serviceControl.wifi.connectStart,
        KeyServiceControlWifiConnectStartEnabled,
        KeyServiceControlWifiConnectStartSsids,
        KeyServiceControlWifiConnectStartBssids,
    )
    serviceControlWifiRule(
        serviceControl.wifi.connectStop,
        KeyServiceControlWifiConnectStopEnabled,
        KeyServiceControlWifiConnectStopSsids,
        KeyServiceControlWifiConnectStopBssids,
    )
    serviceControlWifiRule(
        serviceControl.wifi.disconnectStart,
        KeyServiceControlWifiDisconnectStartEnabled,
        KeyServiceControlWifiDisconnectStartSsids,
        KeyServiceControlWifiDisconnectStartBssids,
    )
    serviceControlWifiRule(
        serviceControl.wifi.disconnectStop,
        KeyServiceControlWifiDisconnectStopEnabled,
        KeyServiceControlWifiDisconnectStopSsids,
        KeyServiceControlWifiDisconnectStopBssids,
    )
    stringList(KeyExternalInterfaces, externalInterfaces)
    stringList(KeyTunSharedNetworkInterfaces, tunSharedNetworkInterfaces)
    stringList(KeyIgnoredInterfaces, ignoredInterfaces)
    stringList(KeyPrivateAddressCidrs, privateAddressCidrs)
    int(KeyProxyAppListMode, proxyAppListMode)
}
