// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package app

import android.app.Application
import data.AndroidAppStateStore
import data.AppSettingsPreferences
import engine.singbox.config.validateSingBoxRuntimeConfiguration
import features.outbound.parseOutboundImportContent
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
import features.subscription.runtime.toSubscriptionFetchOptions
import features.subscription.usecase.OutboundSubscriptionUpdater
import features.subscription.usecase.SubscriptionStateGateway
import features.subscription.usecase.prepareSubscription
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import system.AndroidAppIconFetcher
import engine.singbox.runtime.SingBoxRuntimeRepository
import engine.vpn.AndroidLibboxRuntime

class AsteriskApplication : Application(), SingletonImageLoader.Factory {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    internal val singBoxRuntime: SingBoxRuntimeRepository by lazy { SingBoxRuntimeRepository(appScope, this) }
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
                    OutboundCommandResult.ImportPersisted -> Result.success(true)
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
                    fetchOptions = toSubscriptionFetchOptions(
                        useRunningProxy = group.updateViaProxy && state.proxyRunning,
                        hwid = group.hwid,
                    ),
                    etag = group.subscriptionEtag,
                    lastModified = group.subscriptionLastModified,
                    verifyConfiguration = false,
                )
            },
            parse = ::parseOutboundImportContent,
            validate = { state ->
                validateSingBoxRuntimeConfiguration(applicationContext, state)
            },
        )
    }

    override fun onCreate() {
        super.onCreate()
        AndroidLibboxRuntime.setup(this)
        AndroidLogcatRepository.initialize(applicationContext)
        AndroidCoreLogRepository.initialize(applicationContext)
        AndroidAsteriskdLogRepository.initialize(applicationContext)
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
