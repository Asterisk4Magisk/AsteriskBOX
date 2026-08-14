// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package app

import android.app.Application
import data.AndroidAppStateStore
import data.AppSettingsPreferences
import engine.singbox.config.validateSingBoxRuntimeConfiguration
import features.outbound.parseOutboundImportContent
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
import system.AndroidAppIconFetcher
import engine.singbox.runtime.SingBoxRuntimeRepository
import engine.vpn.AndroidLibboxRuntime

class AsteriskApplication : Application(), SingletonImageLoader.Factory {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    internal val singBoxRuntime: SingBoxRuntimeRepository by lazy { SingBoxRuntimeRepository(appScope, this) }
    internal val stateStore: AndroidAppStateStore by lazy {
        AndroidAppStateStore.get(applicationContext)
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

                override fun compareAndSet(expected: AppState, updated: AppState): Boolean =
                    stateStore.compareAndSet(expected, updated)
            },
            prepare = { group, state ->
                prepareSubscription(
                    sourceUrl = group.url,
                    userAgent = group.userAgent,
                    ageSecretKey = group.ageSecretKey,
                    localContent = null,
                    subscriptionPreparer = subscriptionPreparer,
                    fetchOptions = state.toSubscriptionFetchOptions(
                        useRunningProxy = group.updateViaProxy,
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
