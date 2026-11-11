// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package app

import features.resources.runtime.ResourceAutoUpdateScheduler
import features.resources.resourceAutoUpdateIntervalMillis
import features.resources.ResourceFileUseCase
import features.resources.ResourceFileUpdateCoordinator
import features.resources.ResourceFileUpdateRequest
import features.resources.runtime.AndroidResourceFileDownloadCancellation
import android.app.Application
import features.home.HomeServiceOperationState
import app.effects.AppActivityForegroundTracker
import app.effects.SingBoxRuntimeLifecycleCoordinator
import app.modes.RunModeVpnService
import features.boot.BootAutoConnect
import features.quicksettings.quickSettingsTileState
import features.quicksettings.refreshQuickSettingsTileUnlessShowing
import data.AndroidAppStateStore
import data.AppSettingsPreferences
import engine.singbox.config.validateSingBoxRuntimeConfiguration
import features.outbound.parseOutboundImportContent
import features.outbound.singBoxOutboundConfigFormatter
import features.outbound.AndroidOutboundPinger
import features.outbound.OutboundListProjectionCache
import features.outbound.OutboundCommandResult
import features.outbound.OutboundPingRuntimeRepository
import features.outbound.OutboundRepository
import features.outbound.OutboundStateGateway
import features.logs.AndroidAppLogger
import features.logs.AndroidCoreLogRepository
import features.logs.AndroidAsteriskdLogRepository
import features.logs.AndroidLogcatRepository
import features.subscription.runtime.AndroidSubscriptionPreparer
import features.subscription.runtime.AndroidSubscriptionScheduleGateway
import features.subscription.runtime.OutboundSubscriptionScheduler
import features.subscription.usecase.toSubscriptionFetchOptions
import features.subscription.usecase.OutboundSubscriptionUpdater
import features.subscription.usecase.SubscriptionStateGateway
import features.subscription.usecase.prepareSubscription
import features.subscription.usecase.SubscriptionInstallConfig
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import system.AndroidAppIconFetcher
import engine.singbox.runtime.SingBoxRuntimeRepository
import engine.proxy.AndroidProxyEngine
import system.AndroidRootShellGateway
import engine.vpn.AndroidLibboxRuntime
import engine.proxy.LocalProxyRuntime
import engine.vpn.AsteriskVpnService
import engine.vpn.VpnSession
import app.effects.synchronizeProxyStatus
import app.effects.RunningConfigurationApplier
import androidx.compose.runtime.snapshotFlow
import engine.proxy.ProxyServiceResult
import engine.proxy.ProxyServiceUseCase
import features.home.HomeServiceOperation
import features.resources.ResourceFileUpdateResult
import features.resources.isBusy
import kotlinx.coroutines.flow.first
import ui.feedback.AndroidToastTipNotifier

class AsteriskApplication : Application(), SingletonImageLoader.Factory {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    internal val homeServiceOperationState = HomeServiceOperationState()
    internal val singBoxRuntime: SingBoxRuntimeRepository by lazy { SingBoxRuntimeRepository(appScope, this) }
    internal val singBoxRuntimeLifecycle: SingBoxRuntimeLifecycleCoordinator by lazy {
        SingBoxRuntimeLifecycleCoordinator(appScope, singBoxRuntime)
    }
    private lateinit var foregroundTracker: AppActivityForegroundTracker
    internal val outboundPingRuntime: OutboundPingRuntimeRepository by lazy {
        OutboundPingRuntimeRepository(
            scope = appScope,
            pinger = AndroidOutboundPinger(),
        )
    }
    internal val outboundListProjectionCache: OutboundListProjectionCache by lazy {
        OutboundListProjectionCache()
    }
    internal val stateStore: AndroidAppStateStore by lazy {
        AndroidAppStateStore.get(applicationContext)
    }

    /** Shared by every entry point so proxy and traffic stats operations are serialized. */
    internal val proxyEngine: AndroidProxyEngine by lazy {
        AndroidProxyEngine(
            context = applicationContext,
            rootAccess = AndroidRootShellGateway(),
        )
    }
    internal val subscriptionInstallConfig by lazy {
        SubscriptionInstallConfig(
            currentState = { stateStore.state.value },
            repository = outboundRepository,
            updater = outboundSubscriptionUpdater,
        )
    }
    internal val outboundRepository: OutboundRepository by lazy {
        OutboundRepository(
            gateway = object : OutboundStateGateway {
                override fun snapshot(): AppState = stateStore.state.value

                override suspend fun commitPreparedAndAwaitPersistence(
                    expected: AppState,
                    updated: AppState,
                ): Result<Boolean> = stateStore.commitPreparedAndAwaitPersistence(expected, updated)
            },
            validate = { state ->
                withContext(Dispatchers.IO) {
                    validateSingBoxRuntimeConfiguration(applicationContext, state)
                }
            },
            onOutboundChanged = { id, json -> outboundPingRuntime.invalidate(id, json) },
            onOutboundRemoved = outboundPingRuntime::remove,
            reportRuntimeCallbackFailure = { operation, error ->
                AndroidAppLogger.error(
                    tag = "OutboundRepository",
                    message = "Runtime callback failed ($operation)",
                    error = error,
                )
            },
        )
    }
    internal val subscriptionPreparer: AndroidSubscriptionPreparer by lazy {
        AndroidSubscriptionPreparer(
            installationHwid = AppSettingsPreferences(applicationContext)
                .getOrCreateSubscriptionHwid(),
        )
    }
    private val outboundSubscriptionScheduler: OutboundSubscriptionScheduler by lazy {
        OutboundSubscriptionScheduler(
            AndroidSubscriptionScheduleGateway(applicationContext),
        )
    }
    internal val outboundSubscriptionUpdater: OutboundSubscriptionUpdater by lazy {
        OutboundSubscriptionUpdater(
            stateGateway = object : SubscriptionStateGateway {
                override fun snapshot(): AppState = stateStore.state.value

                override suspend fun compareAndSet(
                    expected: AppState,
                    updated: AppState,
                ): Result<Boolean> = when (
                    val result = outboundRepository.persistImport(expected, updated)
                ) {
                    OutboundCommandResult.ImportPersisted -> {
                        // Failure bookkeeping commits through here too; only new outbounds apply.
                        if (updated.outboundConfigChangedFrom(expected)) runningConfigurationApplier.request()
                        Result.success(true)
                    }
                    OutboundCommandResult.Conflict -> Result.success(false)
                    is OutboundCommandResult.Invalid -> Result.failure(result.error)
                    is OutboundCommandResult.PersistenceFailed -> Result.failure(result.error)
                    else -> Result.failure(
                        IllegalStateException("Unexpected subscription persistence result: $result"),
                    )
                }
            },
            prepare = { group, state ->
                prepareSubscription(
                    sourceUrl = group.url,
                    userAgent = group.userAgent,
                    ageSecretKey = group.ageSecretKey,
                    localContent = null,
                    subscriptionPreparer = subscriptionPreparer,
                    fetchOptions = state.toSubscriptionFetchOptions(group),
                    etag = group.subscriptionEtag,
                    lastModified = group.subscriptionLastModified,
                    verifyConfiguration = false,
                )
            },
            parse = { content, group, state ->
                parseOutboundImportContent(
                    content,
                    jsonFormatter = applicationContext.singBoxOutboundConfigFormatter(state.runMode),
                    providerFetcher = { request ->
                        subscriptionPreparer.fetchProvider(request, group.userAgent, state.toSubscriptionFetchOptions(group))
                    },
                    sourceUrl = group.url,
                )
            },
            validate = { state ->
                validateSingBoxRuntimeConfiguration(applicationContext, state)
            },
            formatDuplicateName = { name, ordinal ->
                applicationContext.getString(R.string.outbound_group_name_duplicate, name, ordinal)
            },
        )
    }

    private val resourceFileUseCase by lazy {
        ResourceFileUseCase(
            context = this,
            resourceFilePicker = { null },
            currentAppState = { stateStore.state.value },
        )
    }
    internal val resourceFileUpdateCoordinator by lazy {
        ResourceFileUpdateCoordinator(
            scope = appScope,
            execute = { request ->
                when (request) {
                    is ResourceFileUpdateRequest.BuiltIn -> resourceFileUseCase.update(
                        kind = request.kind,
                        source = request.source,
                        options = request.options,
                        customResourceFiles = request.customResourceFiles,
                    )
                    is ResourceFileUpdateRequest.Custom -> resourceFileUseCase.updateCustom(
                        customFile = request.file,
                        options = request.options,
                        customResourceFiles = request.customResourceFiles,
                    )
                    is ResourceFileUpdateRequest.CustomBatch -> resourceFileUseCase.updateCustomBatch(
                        customFiles = request.files,
                        options = request.options,
                        allCustomResourceFiles = request.customResourceFiles,
                    )
                    is ResourceFileUpdateRequest.All -> resourceFileUseCase.update(
                        source = request.source,
                        options = request.options,
                        customResourceFiles = request.customResourceFiles,
                    )
                }
            },
            cancelRunning = AndroidResourceFileDownloadCancellation::cancel,
        )
    }

    /** Applies successful resource and subscription updates to a running core. */
    private val runningConfigurationApplier by lazy {
        RunningConfigurationApplier(
            scope = appScope,
            updatesBusy = resourceFileUpdateCoordinator.state.map { state -> state.isBusy },
            apply = ::applyConfigurationToRunningCore,
            reportFailure = { error ->
                AndroidAppLogger.error(RunningConfigurationLogTag, "Automatic configuration apply failed", error)
            },
        )
    }

    /**
     * Same apply as the outbound page's banner: an in-place reload in VPN mode, a supervised
     * restart in ROOT modes. It claims the home screen's service operation state, so the power
     * button, the banner and this apply exclude each other, and a stopped core is left alone.
     */
    private suspend fun applyConfigurationToRunningCore() {
        while (true) {
            snapshotFlow { homeServiceOperationState.isIdle() }.first { idle -> idle }
            val claimed = withContext(Dispatchers.Main.immediate) {
                homeServiceOperationState.isIdle().also { idle ->
                    if (idle) homeServiceOperationState.operation = HomeServiceOperation.Applying
                }
            }
            if (claimed) break
        }
        try {
            val useCase = ProxyServiceUseCase(proxyEngine)
            val current = stateStore.state.value
            if (useCase.isRunning(current) != true) return
            // `proxyRunning` is only kept in sync by the UI in ROOT modes; the probe above is live.
            val snapshot = current.copy(proxyRunning = true)
            when (val result = useCase.applyConfiguration(snapshot, singBoxRuntime::reloadVpnConfiguration)) {
                is ProxyServiceResult.Success -> {
                    AndroidAppLogger.info(RunningConfigurationLogTag, "Applied updated configuration")
                    stateStore.update { state ->
                        state.copy(
                            proxyRunning = result.proxyRunning,
                            localProxyPort = result.appState?.localProxyPort ?: state.localProxyPort,
                            singBoxControlPort = result.appState?.singBoxControlPort ?: state.singBoxControlPort,
                            // Outbound edits committed after the snapshot still wait for the banner.
                            pendingConfigRestart = state.pendingConfigRestart &&
                                state.outboundConfigChangedFrom(snapshot),
                        )
                    }
                }
                is ProxyServiceResult.Failed -> {
                    // A failed restart may already have stopped the core, so read back what runs.
                    useCase.isRunning(snapshot)?.let { running ->
                        stateStore.update { state -> state.copy(proxyRunning = running) }
                    }
                    AndroidToastTipNotifier(applicationContext).showError(
                        result.error,
                        getString(R.string.proxy_service_auto_apply_failed),
                    )
                }
            }
        } finally {
            withContext(Dispatchers.Main.immediate) {
                homeServiceOperationState.operation = HomeServiceOperation.Idle
            }
        }
    }

    /**
     * Brings the app state in line when a tunnel ends in the service process, whether it was
     * stopped, revoked or its process died. The session watch reports the end, so this costs
     * nothing while the tunnel runs; a tunnel that is up when this process starts is watched
     * from here as well.
     */
    private fun synchronizeOnVpnSessionEnd() {
        appScope.launch {
            VpnSession.ended.collect {
                // Only the VPN mode is resynchronized: other modes are probed through ROOT.
                if (stateStore.state.value.runMode != RunModeVpnService) return@collect
                synchronizeProxyStatus(
                    currentState = { stateStore.state.value },
                    readStatus = { snapshot ->
                        runCatching {
                            proxyEngine.status(snapshot.runMode, snapshot)
                        }.getOrNull()
                    },
                    updateAppState = stateStore::update,
                )
            }
        }
        appScope.launch(Dispatchers.IO) { VpnSession.watch(applicationContext) }
    }

    /**
     * Asks SystemUI to rebind the quick settings tile whenever a state source in this process
     * changes it; the first collection is the refresh at process start. The language is included
     * because opening the panel no longer refreshes an active tile, and so is the active node the
     * tile names. A `proxyRunning` the tile writes back itself is skipped, since the tile shows it
     * without reading the status again.
     */
    private fun refreshQuickSettingsTileOnProxyStateChange() {
        appScope.launch {
            // The runtime ticks every second for traffic; only a new proxy snapshot can move the node.
            val runtimeNodes = singBoxRuntime.state.distinctUntilChanged { old, new ->
                old.running == new.running && old.proxies === new.proxies
            }
            combine(stateStore.state, runtimeNodes) { state, runtime -> state.quickSettingsTileState(runtime) }
                .distinctUntilChanged()
                .collect { tileState -> refreshQuickSettingsTileUnlessShowing(applicationContext, tileState) }
        }
    }

    override fun onCreate() {
        super.onCreate()
        // The `:service` process only executes: the VPN Service with its core and the traffic
        // statistics service. The state store, the schedulers, WorkManager and the core log
        // repositories belong to this app process; `:service` only appends its own app log.
        if (AsteriskVpnService.isServiceProcess(this)) {
            // libbox is set up on demand there: a ROOT traffic statistics service never needs it.
            AndroidLogcatRepository.initializeServiceProcess(this)
            return
        }
        // HTTP fetches here go through the tunnel's local proxy, which the service publishes.
        LocalProxyRuntime.attachExternalSource { VpnSession.runningLocalProxyOptions(applicationContext) }
        synchronizeOnVpnSessionEnd()
        appScope.launch {
            resourceFileUpdateCoordinator.results.collect { result ->
                if (result is ResourceFileUpdateResult.Success) runningConfigurationApplier.request()
            }
        }
        refreshQuickSettingsTileOnProxyStateChange()
        appScope.launch {
            val scheduler = ResourceAutoUpdateScheduler(applicationContext)
            stateStore.state
                .map { state -> resourceAutoUpdateIntervalMillis(state.enableResourceAutoUpdate, state.resourceAutoUpdateInterval) }
                .distinctUntilChanged()
                .collect(scheduler::reconcile)
        }
        AndroidLibboxRuntime.setup(this)
        AndroidLogcatRepository.initialize(applicationContext)
        AndroidCoreLogRepository.initialize(applicationContext)
        AndroidAsteriskdLogRepository.initialize(applicationContext)
        foregroundTracker = AppActivityForegroundTracker(singBoxRuntimeLifecycle)
        registerActivityLifecycleCallbacks(foregroundTracker)
        appScope.launch {
            stateStore.state
                .map { state ->
                    state.enableAutoConnect && state.runMode == RunModeVpnService
                }
                .distinctUntilChanged()
                .collect { enabled ->
                    BootAutoConnect.setComponentEnabled(applicationContext, enabled)
                }
        }
        appScope.launch {
            stateStore.state
                .map { state ->
                    state.outboundGroups.map { group ->
                        SubscriptionScheduleKey(
                            id = group.id,
                            url = group.url,
                            interval = group.updateInterval,
                            enabled = group.enabled,
                        )
                    }
                }
                .distinctUntilChanged()
                .collect {
                    outboundSubscriptionScheduler.reconcile(
                        stateStore.state.value.outboundGroups,
                    )
                }
        }
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader {
        return ImageLoader.Builder(context)
            .components {
                add(AndroidAppIconFetcher.Factory(this@AsteriskApplication))
                add(AndroidAppIconFetcher.CacheKeyer())
            }
            .build()
    }

    private data class SubscriptionScheduleKey(
        val id: Int,
        val url: String,
        val interval: String,
        val enabled: Boolean,
    )
}

private fun HomeServiceOperationState.isIdle(): Boolean =
    operation == HomeServiceOperation.Idle && !modeOperationInProgress

private const val RunningConfigurationLogTag = "RunningConfiguration"
