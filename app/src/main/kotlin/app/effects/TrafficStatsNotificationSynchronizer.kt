// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package app.effects

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import data.AndroidAppStateStore
import engine.stats.SingBoxTrafficStatsNotificationService
import engine.stats.SingBoxTrafficStatsRuntime
import engine.stats.toSingBoxTrafficStatsRuntime

@Composable
internal fun TrafficStatsNotificationSynchronizer(
    stateStore: AndroidAppStateStore,
) {
    val appContext = LocalContext.current.applicationContext
    LaunchedEffect(appContext, stateStore) {
        var activeRuntime: SingBoxTrafficStatsRuntime? = null
        stateStore.state.collect { appState ->
            val runtime = if (appState.proxyRunning) {
                appState.toSingBoxTrafficStatsRuntime()
            } else {
                null
            }
            if (runtime == activeRuntime) {
                return@collect
            }
            activeRuntime = runtime
            SingBoxTrafficStatsNotificationService.reconcile(appContext, runtime)
        }
    }
}
