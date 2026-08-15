// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package data.backup

import app.AppState
import app.DefaultSingBoxUrlTestIdleTimeout
import app.DefaultSingBoxUrlTestInterval
import app.DefaultSingBoxUrlTestTolerance
import app.DefaultSingBoxUrlTestUrl
import app.OutboundGroupUpdateStatus
import app.SingBoxDnsRuleState
import app.SingBoxDnsServerState
import app.SingBoxRouteRuleState
import app.SingBoxSelectorTypeSelector
import kotlinx.serialization.Serializable

internal const val AppBackupFormat = "asteriskbox-backup"
internal const val CurrentAppBackupVersion = 2

private val BackupDefaults = AppState()

@Serializable
internal data class AppBackupFile(
    val format: String = "",
    val version: Int = 0,
    val createdAtMillis: Long = 0L,
    val appVersionName: String = "",
    val appVersionCode: Int = 0,
    val data: AppBackupData = AppBackupData(),
)

@Serializable
internal data class AppBackupData(
    val settings: AppBackupSettings = AppBackupSettings(),
    val outboundGroups: List<AppBackupOutboundGroup> = emptyList(),
    val outbounds: List<AppBackupOutbound> = emptyList(),
    val endpoints: List<AppBackupEndpoint> = emptyList(),
    val selectors: List<AppBackupSelector> = emptyList(),
    val routeRules: List<SingBoxRouteRuleState> = emptyList(),
    val dnsServers: List<SingBoxDnsServerState> = emptyList(),
    val dnsRules: List<SingBoxDnsRuleState> = emptyList(),
    val customResourceFiles: List<AppBackupCustomResourceFile> = emptyList(),
    val proxyAppListSelectedApps: List<String> = emptyList(),
)

@Serializable
internal data class AppBackupSettings(
    val colorMode: Int = BackupDefaults.colorMode,
    val languageMode: Int = BackupDefaults.languageMode,
    val seedIndex: Int = BackupDefaults.seedIndex,
    val outboundListLayout: Int = BackupDefaults.outboundListLayout,
    val outboundListSort: Int = BackupDefaults.outboundListSort,
    val selectorSelections: Map<String, String> = BackupDefaults.selectorSelections,
    val routeAutoDetectInterface: Boolean = BackupDefaults.routeAutoDetectInterface,
    val routeOverrideAndroidVpn: Boolean = BackupDefaults.routeOverrideAndroidVpn,
    val routeDefaultNetworkStrategy: String = BackupDefaults.routeDefaultNetworkStrategy,
    val routeDefaultNetworkTypes: List<String> = BackupDefaults.routeDefaultNetworkTypes,
    val routeDefaultFallbackNetworkTypes: List<String> = BackupDefaults.routeDefaultFallbackNetworkTypes,
    val routeDefaultFallbackDelay: String = BackupDefaults.routeDefaultFallbackDelay,
    val routeFindProcess: Boolean = BackupDefaults.routeFindProcess,
    val routeFinal: String = BackupDefaults.routeFinal,
    val singBoxMode: Int = BackupDefaults.singBoxMode,
    val singBoxProxyLayout: Int = BackupDefaults.singBoxProxyLayout,
    val singBoxProxySort: Int = BackupDefaults.singBoxProxySort,
    val singBoxTunStack: Int = BackupDefaults.singBoxTunStack,
    val singBoxControlPort: String = BackupDefaults.singBoxControlPort,
    val singBoxControlSecret: String = BackupDefaults.singBoxControlSecret,
    val enableLocalDns: Boolean = BackupDefaults.enableLocalDns,
    val localProxyPort: String = BackupDefaults.localProxyPort,
    val enableDynamicLocalProxyPort: Boolean = BackupDefaults.enableDynamicLocalProxyPort,
    val localProxyListenAllInterfaces: Boolean = BackupDefaults.localProxyListenAllInterfaces,
    val localProxyUsername: String = BackupDefaults.localProxyUsername,
    val localProxyPassword: String = BackupDefaults.localProxyPassword,
    val enableVpnAppendHttpProxy: Boolean = BackupDefaults.enableVpnAppendHttpProxy,
    val enableVpnHevTun: Boolean = BackupDefaults.enableVpnHevTun,
    val tunMtu: String = BackupDefaults.tunMtu,
    val tunVpnDns: String = BackupDefaults.tunVpnDns,
    val tunIpv4Cidr: String = BackupDefaults.tunIpv4Cidr,
    val tunIpv6Cidr: String = BackupDefaults.tunIpv6Cidr,
    val coreLogLevel: String = BackupDefaults.coreLogLevel,
    val enableTrafficStatsNotification: Boolean = BackupDefaults.enableTrafficStatsNotification,
    val enableBroadcastControl: Boolean = BackupDefaults.enableBroadcastControl,
    val resourceFileSource: Int = BackupDefaults.resourceFileSource,
    val customResourceFileGeositeCategoryAdsAllUrl: String =
        BackupDefaults.customResourceFileGeositeCategoryAdsAllUrl,
    val customResourceFileGeositeGoogleUrl: String = BackupDefaults.customResourceFileGeositeGoogleUrl,
    val customResourceFileGeositeCnUrl: String = BackupDefaults.customResourceFileGeositeCnUrl,
    val customResourceFileGeoipCnUrl: String = BackupDefaults.customResourceFileGeoipCnUrl,
    val customResourceFileDirectCidrIpv4Url: String = BackupDefaults.customResourceFileDirectCidrIpv4Url,
    val customResourceFileDirectCidrIpv6Url: String = BackupDefaults.customResourceFileDirectCidrIpv6Url,
    val enableSniffer: Boolean = BackupDefaults.enableSniffer,
    val snifferProtocols: List<String> = BackupDefaults.snifferProtocols,
    val snifferTimeout: String = BackupDefaults.snifferTimeout,
    val enableIpv6: Boolean = BackupDefaults.enableIpv6,
    val enableIpv6Prefer: Boolean = BackupDefaults.enableIpv6Prefer,
    val dnsFinal: String = BackupDefaults.dnsFinal,
    val routeDefaultDomainResolver: String = BackupDefaults.routeDefaultDomainResolver,
    val dnsCacheCapacity: String = BackupDefaults.dnsCacheCapacity,
    val dnsOptimisticCache: Boolean = BackupDefaults.dnsOptimisticCache,
    val dnsDisableCache: Boolean = BackupDefaults.dnsDisableCache,
    val dnsDisableExpire: Boolean = BackupDefaults.dnsDisableExpire,
    val dnsTimeout: String = BackupDefaults.dnsTimeout,
    val transparentProxyPort: String = BackupDefaults.transparentProxyPort,
    val enableRootEbpfDirectCidrBypass: Boolean = BackupDefaults.enableRootEbpfDirectCidrBypass,
    val ebpfBypassRuleSetTags: List<String> = BackupDefaults.ebpfBypassRuleSetTags,
    val enableRootIpv6Disabler: Boolean = BackupDefaults.enableRootIpv6Disabler,
    val socks5ProxyPort: String = BackupDefaults.socks5ProxyPort,
    val bpf2SocksBridgePort: String = BackupDefaults.bpf2SocksBridgePort,
    val externalInterfaces: List<String> = BackupDefaults.externalInterfaces,
    val ebpfSharedNetworkInterfaces: List<String> = BackupDefaults.ebpfSharedNetworkInterfaces,
    val ignoredInterfaces: List<String> = BackupDefaults.ignoredInterfaces,
    val privateAddressCidrs: List<String> = BackupDefaults.privateAddressCidrs,
    val proxyAppListMode: Int = BackupDefaults.proxyAppListMode,
)

@Serializable
internal data class AppBackupOutboundGroup(
    val id: Int = 0,
    val name: String = "",
    val url: String = "",
    val userAgent: String = "",
    val updateInterval: String = "",
    val hwid: String = "",
    val updateViaProxy: Boolean = false,
    val ageSecretKey: String = "",
    val enabled: Boolean = true,
    val strictImport: Boolean = false,
    val lastUpdateAttemptAtMillis: Long = 0L,
    val lastUpdatedAtMillis: Long = 0L,
    val lastUpdateStatus: String = OutboundGroupUpdateStatus.NEVER.name,
    val lastUpdateImportedCount: Int = 0,
    val lastUpdateSkippedCount: Int = 0,
    val lastUpdateDuplicateCount: Int = 0,
    val consecutiveUpdateFailures: Int = 0,
    val lastUpdateErrorSummary: String = "",
    val subscriptionEtag: String = "",
    val subscriptionLastModified: String = "",
)

@Serializable
internal data class AppBackupOutbound(
    val id: Int = 0,
    val groupId: Int = 0,
    val remarks: String = "",
    val type: String = "",
    val json: String = "",
)

@Serializable
internal data class AppBackupEndpoint(
    val id: Int = 0,
    val remarks: String = "",
    val type: String = "",
    val json: String = "",
)

@Serializable
internal data class AppBackupSelector(
    val id: Int = 0,
    val remarks: String = "",
    val outbounds: List<String> = emptyList(),
    val default: String = "",
    val type: String = SingBoxSelectorTypeSelector,
    val url: String = DefaultSingBoxUrlTestUrl,
    val interval: String = DefaultSingBoxUrlTestInterval,
    val tolerance: Int = DefaultSingBoxUrlTestTolerance,
    val idleTimeout: String = DefaultSingBoxUrlTestIdleTimeout,
    val interruptExistConnections: Boolean = true,
)

@Serializable
internal data class AppBackupCustomResourceFile(
    val id: Int = 0,
    val name: String = "",
    val url: String = "",
)

internal data class AppBackupRestorePreview(
    val backup: AppBackupFile,
    val restoredState: AppState,
    val warnings: List<AppBackupWarning>,
) {
    val outboundGroupCount: Int
        get() = restoredState.outboundGroups.size

    val outboundCount: Int
        get() = restoredState.outbounds.size

    val endpointCount: Int
        get() = restoredState.endpoints.size

    val selectorCount: Int
        get() = restoredState.selectors.size

    val routeRuleCount: Int
        get() = restoredState.routeRules.size

    val dnsServerCount: Int
        get() = restoredState.dnsServers.size

    val dnsRuleCount: Int
        get() = restoredState.dnsRules.size
}

internal sealed interface AppBackupWarning {
    data class MissingOutboundReferences(
        val count: Int,
    ) : AppBackupWarning

    data class MissingDnsServerReferences(
        val count: Int,
    ) : AppBackupWarning

    data class MissingEndpointReferences(
        val count: Int,
    ) : AppBackupWarning
}
