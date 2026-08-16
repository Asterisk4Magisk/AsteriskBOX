// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package app

import app.modes.ColorModeSystem
import app.modes.LanguageModeSystem
import app.modes.OutboundListLayoutAuto
import app.modes.OutboundListSortDefault
import app.modes.ProxyAppListModeGlobal
import app.modes.RunModeVpnService
import app.modes.SingBoxModeRule
import app.modes.SingBoxProxyLayoutAuto
import app.modes.SingBoxProxySortDefault
import app.modes.SingBoxTunStackGvisor
import engine.singbox.DefaultSingBoxControlPort
import engine.singbox.DefaultSingBoxDnsCacheCapacity
import engine.singbox.DefaultSingBoxDnsFinal
import engine.singbox.DefaultSingBoxDnsRules
import engine.singbox.DefaultSingBoxDnsServers
import engine.singbox.DefaultSingBoxDnsTimeout
import engine.singbox.DefaultSingBoxLogLevel
import engine.singbox.DefaultSingBoxRouteDefaultDomainResolver
import engine.singbox.DefaultSingBoxRouteRules
import engine.singbox.DefaultSingBoxSnifferProtocols
import engine.singbox.DefaultSingBoxSnifferTimeout
import engine.root.RootModeEngine
import engine.vpn.VpnDefaults
import features.resources.ResourceFileSourceDefault

data class AppState(
    val colorMode: Int = ColorModeSystem,
    val languageMode: Int = LanguageModeSystem,
    val seedIndex: Int = 0,

    val outboundGroups: List<OutboundGroupState> = emptyList(),
    val nextOutboundGroupId: Int = 1,
    val outbounds: List<OutboundState> = emptyList(),
    val nextOutboundId: Int = 1,
    val outboundListLayout: Int = OutboundListLayoutAuto,
    val outboundListSort: Int = OutboundListSortDefault,
    val endpoints: List<SingBoxEndpointState> = emptyList(),
    val nextEndpointId: Int = 1,
    val selectors: List<SingBoxSelectorState> = emptyList(),
    val nextSelectorId: Int = 1,
    val selectorSelections: Map<String, String> = emptyMap(),
    val routeAutoDetectInterface: Boolean = false,
    val routeOverrideAndroidVpn: Boolean = false,
    val routeDefaultNetworkStrategy: String = "",
    val routeDefaultNetworkTypes: List<String> = emptyList(),
    val routeDefaultFallbackNetworkTypes: List<String> = emptyList(),
    val routeDefaultFallbackDelay: String = "",
    val routeFindProcess: Boolean = false,
    val routeFinal: String = "",
    val routeRules: List<SingBoxRouteRuleState> = DefaultSingBoxRouteRules,
    val nextRouteRuleId: Int =
        (DefaultSingBoxRouteRules.maxOfOrNull(SingBoxRouteRuleState::id) ?: 0) + 1,
    val runMode: Int = RunModeVpnService,
    val singBoxMode: Int = SingBoxModeRule,
    val singBoxProxyLayout: Int = SingBoxProxyLayoutAuto,
    val singBoxProxySort: Int = SingBoxProxySortDefault,
    val singBoxTunStack: Int = SingBoxTunStackGvisor,
    val singBoxControlPort: String = DefaultSingBoxControlPort.toString(),
    val singBoxControlSecret: String = "",
    val enableLocalDns: Boolean = true,

    val localProxyPort: String = VpnDefaults.LOCAL_PROXY_PORT.toString(),
    val enableDynamicLocalProxyPort: Boolean = false,
    val localProxyListenAllInterfaces: Boolean = false,
    val localProxyUsername: String = "",
    val localProxyPassword: String = "",
    val enableVpnAppendHttpProxy: Boolean = false,
    val enableVpnHevTun: Boolean = false,
    val tunMtu: String = VpnDefaults.MTU.toString(),
    val tunVpnDns: String = VpnDefaults.IPV4_DNS,
    val tunIpv4Cidr: String = VpnDefaults.IPV4_CIDR,
    val tunIpv6Cidr: String = VpnDefaults.IPV6_CIDR,

    val proxyRunning: Boolean = false,

    val coreLogLevel: String = DefaultSingBoxLogLevel,
    val enableTrafficStatsNotification: Boolean = false,
    val enableBroadcastControl: Boolean = false,
    val resourceFileSource: Int = ResourceFileSourceDefault,
    val customResourceFileGeositeCategoryAdsAllUrl: String = "",
    val customResourceFileGeositeGoogleUrl: String = "",
    val customResourceFileGeositeCnUrl: String = "",
    val customResourceFileGeoipCnUrl: String = "",
    val customResourceFileDirectCidrIpv4Url: String = "",
    val customResourceFileDirectCidrIpv6Url: String = "",
    val customResourceFiles: List<CustomResourceFileState> = emptyList(),
    val nextCustomResourceFileId: Int = 1,
    val enableSniffer: Boolean = true,
    val snifferProtocols: List<String> = DefaultSingBoxSnifferProtocols,
    val snifferTimeout: String = DefaultSingBoxSnifferTimeout,

    val enableIpv6: Boolean = false,
    val enableIpv6Prefer: Boolean = false,

    val dnsFinal: String = DefaultSingBoxDnsFinal,
    val routeDefaultDomainResolver: String = DefaultSingBoxRouteDefaultDomainResolver,
    val dnsCacheCapacity: String = DefaultSingBoxDnsCacheCapacity,
    val dnsOptimisticCache: Boolean = false,
    val dnsDisableCache: Boolean = false,
    val dnsDisableExpire: Boolean = false,
    val dnsTimeout: String = DefaultSingBoxDnsTimeout,
    val storeFakeIp: Boolean = false,
    val storeDns: Boolean = false,
    val dnsServers: List<SingBoxDnsServerState> = DefaultSingBoxDnsServers,
    val nextDnsServerId: Int =
        (DefaultSingBoxDnsServers.maxOfOrNull(SingBoxDnsServerState::id) ?: 0) + 1,
    val dnsRules: List<SingBoxDnsRuleState> = DefaultSingBoxDnsRules,
    val nextDnsRuleId: Int =
        (DefaultSingBoxDnsRules.maxOfOrNull(SingBoxDnsRuleState::id) ?: 0) + 1,

    val transparentProxyPort: String = RootModeEngine.DefaultTproxyPort.toString(),
    val enableRootBootScript: Boolean = false,
    val enableRootEbpfRules: Boolean = false,
    val enableRootEbpfDirectCidrBypass: Boolean = false,
    val ebpfBypassRuleSetTags: List<String> = emptyList(),
    val enableRootIpv6Disabler: Boolean = false,
    val socks5ProxyPort: String = RootModeEngine.DefaultTun2SocksProxyPort.toString(),
    val bpf2SocksBridgePort: String = RootModeEngine.DefaultBpf2SocksBridgePort.toString(),

    val serviceControl: ServiceControlSettings = ServiceControlSettings(),

    val externalInterfaces: List<String> = emptyList(),
    val ebpfSharedNetworkInterfaces: List<String> = emptyList(),
    val ignoredInterfaces: List<String> = emptyList(),
    val privateAddressCidrs: List<String> = emptyList(),

    val proxyAppListMode: Int = ProxyAppListModeGlobal,
    val proxyAppListSelectedApps: List<String> = emptyList(),
)

val AppState.effectiveLocalDnsEnabled: Boolean
    get() = enableLocalDns

val AppState.effectiveFakeIpEnabled: Boolean
    get() = effectiveLocalDnsEnabled && dnsServers.any { server -> server.type == "fakeip" }
