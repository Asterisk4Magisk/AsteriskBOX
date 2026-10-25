// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package app

import app.modes.ColorModeSystem
import app.modes.LanguageModeSystem
import app.modes.OutboundListLayoutAuto
import app.modes.OutboundListSortDefault
import app.modes.ProxyAppListModeGlobal
import app.modes.isRootRunMode
import app.modes.supportsAppListEnhancement
import app.modes.RunModeVpnService
import app.modes.SingBoxModeRule
import app.modes.SingBoxProxyLayoutAuto
import app.modes.SingBoxProxySortDefault
import engine.singbox.DefaultSingBoxControlPort
import engine.singbox.DefaultEbpfLocalDataPlane
import engine.singbox.DefaultEbpfSharedDataPlane
import engine.singbox.DefaultEbpfDnsMode
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
    val hideRecentTasks: Boolean = false,
    val allowScreenshots: Boolean = false,
    val hideLauncherIcon: Boolean = false,
    val autoHideNavigationBar: Boolean = false,

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

    /**
     * Starts the VPN Service by itself after boot or an app update. VPN Service mode only:
     * ROOT modes bring their own boot entry ([enableRootBootScript]).
     */
    val enableAutoConnect: Boolean = false,
    val tunMtu: String = VpnDefaults.MTU.toString(),
    val tunVpnDns: String = VpnDefaults.IPV4_DNS,
    val tunIpv4Cidr: String = VpnDefaults.IPV4_CIDR,
    val tunIpv6Cidr: String = VpnDefaults.IPV6_CIDR,

    val proxyRunning: Boolean = false,

    /**
     * The running core was started from a configuration that no longer matches the
     * persisted outbound data, so the service must be restarted to apply it.
     *
     * This models the same gap META closes with its pending-restart profile: group,
     * outbound and subscription edits are committed to the store immediately, but a
     * live sing-box instance keeps the configuration it was launched with. It is
     * intentionally runtime-only (like [proxyRunning]) because a freshly launched core
     * always compiles the current state, which settles the marker on its own.
     */
    val pendingConfigRestart: Boolean = false,

    val enableConfigOverrideScript: Boolean = false,
    val configOverrideScript: String = "const main = (config) => {\n  return config\n}",
    val coreLogLevel: String = DefaultSingBoxLogLevel,
    val enableTrafficStatsNotification: Boolean = false,
    val enableBroadcastControl: Boolean = false,
    val enableResourceAutoUpdate: Boolean = false,
    val resourceAutoUpdateInterval: String = "24",
    val resourceFileSource: Int = ResourceFileSourceDefault,
    val customResourceFileGeositeCategoryAdsAllUrl: String = "",
    val customResourceFileGeositeGoogleUrl: String = "",
    val customResourceFileGeositeCnUrl: String = "",
    val customResourceFileGeoipCnUrl: String = "",
    val customResourceFileDirectCidrIpv4Url: String = "",
    val customResourceFileDirectCidrIpv6Url: String = "",
    val customResourceFiles: List<CustomResourceFileState> = emptyList(),
    val bundledRuleSetsInitialized: Boolean = false,
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
    val enableFakeIp: Boolean = false,
    val dnsFakeIpRange: String = engine.network.DefaultFakeIpIpv4Pool,
    val storeFakeIp: Boolean = true,
    val storeDns: Boolean = false,
    val dnsServers: List<SingBoxDnsServerState> = DefaultSingBoxDnsServers,
    val nextDnsServerId: Int =
        (DefaultSingBoxDnsServers.maxOfOrNull(SingBoxDnsServerState::id) ?: 0) + 1,
    val dnsRules: List<SingBoxDnsRuleState> = DefaultSingBoxDnsRules,
    val nextDnsRuleId: Int =
        (DefaultSingBoxDnsRules.maxOfOrNull(SingBoxDnsRuleState::id) ?: 0) + 1,

    val transparentProxyPort: String = RootModeEngine.DefaultTproxyPort.toString(),
    val enableRootBootScript: Boolean = false,
    val enableAppListEnhancement: Boolean = false,
    val enableRootEbpfRules: Boolean = false,
    val enableRootEbpfDirectCidrBypass: Boolean = false,
    val enableRootEbpfPrivateAddressBypass: Boolean = false,
    val ebpfLocalDataPlane: String = DefaultEbpfLocalDataPlane,
    val ebpfSharedDataPlane: String = DefaultEbpfSharedDataPlane,
    val ebpfLocalDnsMode: String = DefaultEbpfDnsMode,
    val ebpfSharedDnsMode: String = DefaultEbpfDnsMode,
    val tunBypassRuleSetTags: List<String> = emptyList(),
    val enableRootIpv6Disabler: Boolean = false,
    val socks5ProxyPort: String = RootModeEngine.DefaultTun2SocksProxyPort.toString(),
    val bpf2SocksBridgePort: String = RootModeEngine.DefaultBpf2SocksBridgePort.toString(),

    val serviceControl: ServiceControlSettings = ServiceControlSettings(),

    val externalInterfaces: List<String> = emptyList(),
    val tunSharedNetworkInterfaces: List<String> = emptyList(),
    val ignoredInterfaces: List<String> = emptyList(),
    val privateAddressCidrs: List<String> = emptyList(),

    // Global connection-test settings used by the delay test.
    val connectionTestUrl: String = DefaultConnectionTestUrl,
    val connectionTestConcurrent: Int = DefaultConnectionTestConcurrent,
    val connectionTestTimeoutMillis: Int = DefaultConnectionTestTimeoutMillis,
    val connectionTestUnifiedDelay: Boolean = DefaultConnectionTestUnifiedDelay,
    val connectionTestIgnoreHandshakeTime: Boolean = DefaultConnectionTestIgnoreHandshakeTime,

    val proxyAppListMode: Int = ProxyAppListModeGlobal,
    val proxyAppListSelectedApps: List<String> = emptyList(),
)

val AppState.effectiveLocalDnsEnabled: Boolean
    get() = enableLocalDns

/**
 * Whether [previous] and this state disagree on any field that feeds the compiled
 * outbound section of the sing-box configuration: the outbound list itself, and each
 * group's identity, name, enablement and detour.
 *
 * Subscription bookkeeping (URL, HWID, user agent, update interval, last-update
 * counters) is deliberately excluded: editing it never changes the running core.
 */
internal fun AppState.outboundConfigChangedFrom(previous: AppState): Boolean {
    if (outbounds != previous.outbounds) return true
    if (outboundGroups.size != previous.outboundGroups.size) return true
    return outboundGroups.zip(previous.outboundGroups).any { (current, prior) ->
        current.id != prior.id ||
            current.name != prior.name ||
            current.enabled != prior.enabled ||
            current.detour != prior.detour
    }
}

/** Marks the running core as stale after an outbound-side edit. */
internal fun AppState.withConfigRestartPendingFrom(previous: AppState): AppState {
    if (pendingConfigRestart || !proxyRunning) return this
    if (!outboundConfigChangedFrom(previous)) return this
    return copy(pendingConfigRestart = true)
}

/**
 * Clears the marker once it cannot describe a live core any more: the service is
 * stopped, or it just (re)started — a launch always compiles the current state.
 *
 * Mirrors META, which drops its pending-restart id when the proxy is observed as not
 * running; the extra `proxyRunning` transition covers starts from the tile, the
 * broadcast receiver and the home screen, none of which go through our restart button.
 */
internal fun AppState.withSettledConfigRestart(previous: AppState): AppState {
    if (!pendingConfigRestart) return this
    if (!proxyRunning || proxyRunning != previous.proxyRunning) return copy(pendingConfigRestart = false)
    return this
}

val AppState.rootIpv6DataPathEnabled: Boolean
    get() = enableIpv6 || (effectiveLocalDnsEnabled && !enableRootIpv6Disabler)

val AppState.effectiveFakeIpEnabled: Boolean
    get() = effectiveLocalDnsEnabled && enableFakeIp

val AppState.requiresGlobalProxyAppMode: Boolean
    get() = runMode.isRootRunMode() && effectiveFakeIpEnabled &&
        !(enableAppListEnhancement && runMode.supportsAppListEnhancement())

internal fun AppState.withCompatibleProxyAppListMode(): AppState {
    val normalized = withIpv4FakeIpSettings()
    return if (normalized.requiresGlobalProxyAppMode && normalized.proxyAppListMode != ProxyAppListModeGlobal) {
        normalized.copy(proxyAppListMode = ProxyAppListModeGlobal)
    } else {
        normalized
    }
}
