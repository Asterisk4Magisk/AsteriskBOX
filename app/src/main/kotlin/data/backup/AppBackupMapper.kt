// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package data.backup

import app.AppState
import app.CustomResourceFileState
import app.ManagedSingBoxTagPrefix
import app.OutboundGroupState
import app.OutboundGroupUpdateStatus
import app.OutboundState
import app.SingBoxEndpointState
import app.SingBoxRouteRuleState
import app.SingBoxSelectorState
import app.selectableManagedOutbounds
import app.modes.RunModeVpnService

internal fun AppState.toAppBackupFile(
    createdAtMillis: Long,
    appVersionName: String,
    appVersionCode: Int,
): AppBackupFile =
    AppBackupFile(
        format = AppBackupFormat,
        version = CurrentAppBackupVersion,
        createdAtMillis = createdAtMillis,
        appVersionName = appVersionName,
        appVersionCode = appVersionCode,
        data =
            AppBackupData(
                settings = toBackupSettings(),
                outboundGroups = outboundGroups.map(OutboundGroupState::toBackup),
                outbounds = outbounds.map(OutboundState::toBackup),
                endpoints = endpoints.map(SingBoxEndpointState::toBackup),
                selectors = selectors.map(SingBoxSelectorState::toBackup),
                routeRules = routeRules,
                dnsServers = dnsServers,
                dnsRules = dnsRules,
                customResourceFiles = customResourceFiles.map(CustomResourceFileState::toBackup),
                proxyAppListSelectedApps = proxyAppListSelectedApps,
            ),
    )

internal fun AppBackupFile.toRestorePreview(): AppBackupRestorePreview {
    val migrated = migrateAppBackup()
    migrated.data.validateForRestore()
    val restoredState = migrated.data.toAppState()
    return AppBackupRestorePreview(
        backup = migrated,
        restoredState = restoredState,
        warnings = restoredState.restoreWarnings(),
    )
}

private fun AppState.toBackupSettings(): AppBackupSettings =
    AppBackupSettings(
        colorMode = colorMode,
        languageMode = languageMode,
        seedIndex = seedIndex,
        outboundListLayout = outboundListLayout,
        outboundListSort = outboundListSort,
        selectorSelections = selectorSelections,
        routeAutoDetectInterface = routeAutoDetectInterface,
        routeOverrideAndroidVpn = routeOverrideAndroidVpn,
        routeDefaultNetworkStrategy = routeDefaultNetworkStrategy,
        routeDefaultNetworkTypes = routeDefaultNetworkTypes,
        routeDefaultFallbackNetworkTypes = routeDefaultFallbackNetworkTypes,
        routeDefaultFallbackDelay = routeDefaultFallbackDelay,
        routeFindProcess = routeFindProcess,
        routeFinal = routeFinal,
        singBoxMode = singBoxMode,
        singBoxProxyLayout = singBoxProxyLayout,
        singBoxProxySort = singBoxProxySort,
        singBoxTunStack = singBoxTunStack,
        singBoxControlPort = singBoxControlPort,
        singBoxControlSecret = singBoxControlSecret,
        enableLocalDns = enableLocalDns,
        localProxyPort = localProxyPort,
        enableDynamicLocalProxyPort = enableDynamicLocalProxyPort,
        localProxyListenAllInterfaces = localProxyListenAllInterfaces,
        localProxyUsername = localProxyUsername,
        localProxyPassword = localProxyPassword,
        enableVpnAppendHttpProxy = enableVpnAppendHttpProxy,
        enableVpnHevTun = enableVpnHevTun,
        tunMtu = tunMtu,
        tunVpnDns = tunVpnDns,
        tunIpv4Cidr = tunIpv4Cidr,
        tunIpv6Cidr = tunIpv6Cidr,
        coreLogLevel = coreLogLevel,
        enableTrafficStatsNotification = enableTrafficStatsNotification,
        enableBroadcastControl = enableBroadcastControl,
        resourceFileSource = resourceFileSource,
        customResourceFileGeositeCategoryAdsAllUrl = customResourceFileGeositeCategoryAdsAllUrl,
        customResourceFileGeositeGoogleUrl = customResourceFileGeositeGoogleUrl,
        customResourceFileGeositeCnUrl = customResourceFileGeositeCnUrl,
        customResourceFileGeoipCnUrl = customResourceFileGeoipCnUrl,
        customResourceFileDirectCidrIpv4Url = customResourceFileDirectCidrIpv4Url,
        customResourceFileDirectCidrIpv6Url = customResourceFileDirectCidrIpv6Url,
        enableSniffer = enableSniffer,
        snifferProtocols = snifferProtocols,
        snifferTimeout = snifferTimeout,
        enableIpv6 = enableIpv6,
        enableIpv6Prefer = enableIpv6Prefer,
        dnsFinal = dnsFinal,
        routeDefaultDomainResolver = routeDefaultDomainResolver,
        dnsCacheCapacity = dnsCacheCapacity,
        dnsOptimisticCache = dnsOptimisticCache,
        dnsDisableCache = dnsDisableCache,
        dnsDisableExpire = dnsDisableExpire,
        dnsTimeout = dnsTimeout,
        transparentProxyPort = transparentProxyPort,
        enableRootEbpfDirectCidrBypass = enableRootEbpfDirectCidrBypass,
        enableRootIpv6Disabler = enableRootIpv6Disabler,
        socks5ProxyPort = socks5ProxyPort,
        bpf2SocksBridgePort = bpf2SocksBridgePort,
        externalInterfaces = externalInterfaces,
        ebpfSharedNetworkInterfaces = ebpfSharedNetworkInterfaces,
        ignoredInterfaces = ignoredInterfaces,
        privateAddressCidrs = privateAddressCidrs,
        proxyAppListMode = proxyAppListMode,
    )

private fun OutboundGroupState.toBackup(): AppBackupOutboundGroup =
    AppBackupOutboundGroup(
        id = id,
        name = name,
        url = url,
        userAgent = userAgent,
        updateInterval = updateInterval,
        hwid = hwid,
        updateViaProxy = updateViaProxy,
        ageSecretKey = ageSecretKey,
        enabled = enabled,
        strictImport = strictImport,
        lastUpdateAttemptAtMillis = lastUpdateAttemptAtMillis,
        lastUpdatedAtMillis = lastUpdatedAtMillis,
        lastUpdateStatus = lastUpdateStatus.name,
        lastUpdateImportedCount = lastUpdateImportedCount,
        lastUpdateSkippedCount = lastUpdateSkippedCount,
        lastUpdateDuplicateCount = lastUpdateDuplicateCount,
        consecutiveUpdateFailures = consecutiveUpdateFailures,
        lastUpdateErrorSummary = lastUpdateErrorSummary,
        subscriptionEtag = subscriptionEtag,
        subscriptionLastModified = subscriptionLastModified,
    )

private fun OutboundState.toBackup(): AppBackupOutbound =
    AppBackupOutbound(
        id = id,
        groupId = groupId,
        remarks = remarks,
        type = type,
        json = json,
    )

private fun SingBoxEndpointState.toBackup(): AppBackupEndpoint =
    AppBackupEndpoint(
        id = id,
        remarks = remarks,
        type = type,
        json = json,
    )

private fun SingBoxSelectorState.toBackup(): AppBackupSelector =
    AppBackupSelector(
        id = id,
        remarks = remarks,
        outbounds = outbounds,
        default = default,
        type = type,
        url = url,
        interval = interval,
        tolerance = tolerance,
        idleTimeout = idleTimeout,
        interruptExistConnections = interruptExistConnections,
    )

private fun CustomResourceFileState.toBackup(): AppBackupCustomResourceFile =
    AppBackupCustomResourceFile(
        id = id,
        name = name,
        url = url,
    )

private fun AppBackupData.toAppState(): AppState {
    val defaults = AppState()
    val restoredOutboundGroups = outboundGroups.map(AppBackupOutboundGroup::toState)
    val restoredOutbounds = outbounds.map(AppBackupOutbound::toState)
    val restoredEndpoints = endpoints.map(AppBackupEndpoint::toState)
    val restoredSelectors = selectors.map(AppBackupSelector::toState)
    val restoredCustomResourceFiles = customResourceFiles.map(AppBackupCustomResourceFile::toState)

    return defaults.copy(
        colorMode = settings.colorMode,
        languageMode = settings.languageMode,
        seedIndex = settings.seedIndex,
        outboundGroups = restoredOutboundGroups,
        nextOutboundGroupId = nextId(
            defaults.nextOutboundGroupId,
            restoredOutboundGroups.map(OutboundGroupState::id),
        ),
        outbounds = restoredOutbounds,
        nextOutboundId = nextId(defaults.nextOutboundId, restoredOutbounds.map(OutboundState::id)),
        outboundListLayout = settings.outboundListLayout,
        outboundListSort = settings.outboundListSort,
        endpoints = restoredEndpoints,
        nextEndpointId = nextId(defaults.nextEndpointId, restoredEndpoints.map(SingBoxEndpointState::id)),
        selectors = restoredSelectors,
        nextSelectorId = nextId(defaults.nextSelectorId, restoredSelectors.map(SingBoxSelectorState::id)),
        selectorSelections = settings.selectorSelections,
        routeAutoDetectInterface = settings.routeAutoDetectInterface,
        routeOverrideAndroidVpn = settings.routeOverrideAndroidVpn,
        routeDefaultNetworkStrategy = settings.routeDefaultNetworkStrategy,
        routeDefaultNetworkTypes = settings.routeDefaultNetworkTypes,
        routeDefaultFallbackNetworkTypes = settings.routeDefaultFallbackNetworkTypes,
        routeDefaultFallbackDelay = settings.routeDefaultFallbackDelay,
        routeFindProcess = settings.routeFindProcess,
        routeFinal = settings.routeFinal,
        routeRules = routeRules,
        nextRouteRuleId = nextId(defaults.nextRouteRuleId, routeRules.map(SingBoxRouteRuleState::id)),
        runMode = RunModeVpnService,
        singBoxMode = settings.singBoxMode,
        singBoxProxyLayout = settings.singBoxProxyLayout,
        singBoxProxySort = settings.singBoxProxySort,
        singBoxTunStack = settings.singBoxTunStack,
        singBoxControlPort = settings.singBoxControlPort,
        singBoxControlSecret = settings.singBoxControlSecret,
        enableLocalDns = settings.enableLocalDns,
        localProxyPort = settings.localProxyPort,
        enableDynamicLocalProxyPort = settings.enableDynamicLocalProxyPort,
        localProxyListenAllInterfaces = settings.localProxyListenAllInterfaces,
        localProxyUsername = settings.localProxyUsername,
        localProxyPassword = settings.localProxyPassword,
        enableVpnAppendHttpProxy = settings.enableVpnAppendHttpProxy,
        enableVpnHevTun = settings.enableVpnHevTun,
        tunMtu = settings.tunMtu,
        tunVpnDns = settings.tunVpnDns,
        tunIpv4Cidr = settings.tunIpv4Cidr,
        tunIpv6Cidr = settings.tunIpv6Cidr,
        proxyRunning = false,
        coreLogLevel = settings.coreLogLevel,
        enableTrafficStatsNotification = settings.enableTrafficStatsNotification,
        enableBroadcastControl = settings.enableBroadcastControl,
        resourceFileSource = settings.resourceFileSource,
        customResourceFileGeositeCategoryAdsAllUrl = settings.customResourceFileGeositeCategoryAdsAllUrl,
        customResourceFileGeositeGoogleUrl = settings.customResourceFileGeositeGoogleUrl,
        customResourceFileGeositeCnUrl = settings.customResourceFileGeositeCnUrl,
        customResourceFileGeoipCnUrl = settings.customResourceFileGeoipCnUrl,
        customResourceFileDirectCidrIpv4Url = settings.customResourceFileDirectCidrIpv4Url,
        customResourceFileDirectCidrIpv6Url = settings.customResourceFileDirectCidrIpv6Url,
        customResourceFiles = restoredCustomResourceFiles,
        nextCustomResourceFileId = nextId(
            defaults.nextCustomResourceFileId,
            restoredCustomResourceFiles.map(CustomResourceFileState::id),
        ),
        enableSniffer = settings.enableSniffer,
        snifferProtocols = settings.snifferProtocols,
        snifferTimeout = settings.snifferTimeout,
        enableIpv6 = settings.enableIpv6,
        enableIpv6Prefer = settings.enableIpv6Prefer,
        dnsFinal = settings.dnsFinal,
        routeDefaultDomainResolver = settings.routeDefaultDomainResolver,
        dnsCacheCapacity = settings.dnsCacheCapacity,
        dnsOptimisticCache = settings.dnsOptimisticCache,
        dnsDisableCache = settings.dnsDisableCache,
        dnsDisableExpire = settings.dnsDisableExpire,
        dnsTimeout = settings.dnsTimeout,
        dnsServers = dnsServers,
        nextDnsServerId = nextId(defaults.nextDnsServerId, dnsServers.map { server -> server.id }),
        dnsRules = dnsRules,
        nextDnsRuleId = nextId(defaults.nextDnsRuleId, dnsRules.map { rule -> rule.id }),
        transparentProxyPort = settings.transparentProxyPort,
        enableRootBootScript = false,
        enableRootEbpfRules = false,
        enableRootEbpfDirectCidrBypass = settings.enableRootEbpfDirectCidrBypass,
        enableRootIpv6Disabler = settings.enableRootIpv6Disabler,
        socks5ProxyPort = settings.socks5ProxyPort,
        bpf2SocksBridgePort = settings.bpf2SocksBridgePort,
        externalInterfaces = settings.externalInterfaces,
        ebpfSharedNetworkInterfaces = settings.ebpfSharedNetworkInterfaces,
        ignoredInterfaces = settings.ignoredInterfaces,
        privateAddressCidrs = settings.privateAddressCidrs,
        proxyAppListMode = settings.proxyAppListMode,
        proxyAppListSelectedApps = proxyAppListSelectedApps,
    )
}

private fun AppBackupOutboundGroup.toState(): OutboundGroupState =
    OutboundGroupState(
        id = id,
        name = name,
        url = url,
        userAgent = userAgent,
        updateInterval = updateInterval,
        hwid = hwid,
        updateViaProxy = updateViaProxy,
        ageSecretKey = ageSecretKey,
        enabled = enabled,
        strictImport = strictImport,
        lastUpdateAttemptAtMillis = lastUpdateAttemptAtMillis,
        lastUpdatedAtMillis = lastUpdatedAtMillis,
        lastUpdateStatus = OutboundGroupUpdateStatus.valueOf(lastUpdateStatus),
        lastUpdateImportedCount = lastUpdateImportedCount,
        lastUpdateSkippedCount = lastUpdateSkippedCount,
        lastUpdateDuplicateCount = lastUpdateDuplicateCount,
        consecutiveUpdateFailures = consecutiveUpdateFailures,
        lastUpdateErrorSummary = lastUpdateErrorSummary,
        subscriptionEtag = subscriptionEtag,
        subscriptionLastModified = subscriptionLastModified,
    )

private fun AppBackupOutbound.toState(): OutboundState =
    OutboundState(
        id = id,
        groupId = groupId,
        remarks = remarks,
        type = type,
        json = json,
        pingMillis = null,
    )

private fun AppBackupEndpoint.toState(): SingBoxEndpointState =
    SingBoxEndpointState(
        id = id,
        remarks = remarks,
        type = type,
        json = json,
    )

private fun AppBackupSelector.toState(): SingBoxSelectorState =
    SingBoxSelectorState(
        id = id,
        remarks = remarks,
        outbounds = outbounds,
        default = default,
        type = type,
        url = url,
        interval = interval,
        tolerance = tolerance,
        idleTimeout = idleTimeout,
        interruptExistConnections = interruptExistConnections,
    )

private fun AppBackupCustomResourceFile.toState(): CustomResourceFileState =
    CustomResourceFileState(
        id = id,
        name = name,
        url = url,
    )

private fun AppState.restoreWarnings(): List<AppBackupWarning> {
    val availableOutbounds = selectableManagedOutbounds(this).mapTo(mutableSetOf()) { choice -> choice.tag }
    val outboundReferences = buildList {
        selectors.forEach { selector ->
            addAll(selector.outbounds)
            add(selector.default)
        }
        selectorSelections.forEach { (selector, outbound) ->
            add(selector)
            add(outbound)
        }
        add(routeFinal)
        routeRules.forEach { rule -> addAll(rule.outboundReferences()) }
        dnsServers.forEach { server -> add(server.detour) }
    }
    val missingOutboundCount = outboundReferences.countMissingManagedReferences(availableOutbounds)

    val availableDnsServers = dnsServers.mapTo(mutableSetOf()) { server -> server.tag }
    val dnsReferences = buildList {
        add(dnsFinal)
        add(routeDefaultDomainResolver)
        dnsServers.forEach { server -> add(server.domainResolver) }
        dnsRules.forEach { rule -> add(rule.server) }
    }
    val missingDnsServerCount = dnsReferences.countMissingManagedReferences(availableDnsServers)

    val availableEndpoints = endpoints.mapTo(mutableSetOf()) { endpoint -> endpoint.tag }
    val missingEndpointCount = dnsServers
        .map { server -> server.endpoint }
        .countMissingManagedReferences(availableEndpoints)

    return buildList {
        if (missingOutboundCount > 0) {
            add(AppBackupWarning.MissingOutboundReferences(missingOutboundCount))
        }
        if (missingDnsServerCount > 0) {
            add(AppBackupWarning.MissingDnsServerReferences(missingDnsServerCount))
        }
        if (missingEndpointCount > 0) {
            add(AppBackupWarning.MissingEndpointReferences(missingEndpointCount))
        }
    }
}

private fun SingBoxRouteRuleState.outboundReferences(): List<String> =
    buildList {
        add(outbound)
        logicalRules.forEach { rule -> addAll(rule.outboundReferences()) }
    }

private fun Iterable<String>.countMissingManagedReferences(availableTags: Set<String>): Int =
    count { reference ->
        val normalized = reference.trim()
        normalized.startsWith(ManagedSingBoxTagPrefix) && normalized !in availableTags
    }

private fun nextId(defaultValue: Int, ids: List<Int>): Int =
    maxOf(defaultValue, (ids.maxOrNull() ?: 0) + 1)
