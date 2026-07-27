// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package app.effects

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import app.AppState
import app.modes.isRootRunMode
import data.AndroidAppStateStore
import engine.proxy.AndroidProxyEngine
import engine.singbox.runtime.SingBoxRuntimeRepository

@Composable
internal fun SingBoxRuntimeSynchronizer(
    stateStore: AndroidAppStateStore,
    proxyEngine: AndroidProxyEngine,
    singBoxRuntime: SingBoxRuntimeRepository,
    updateAppState: ((AppState) -> AppState) -> Unit,
) {
    LaunchedEffect(stateStore, proxyEngine, singBoxRuntime) {
        syncStartupProxyStatus(
            stateStore = stateStore,
            proxyEngine = proxyEngine,
            updateAppState = updateAppState,
        )
        stateStore.state
            .collect { appState ->
                singBoxRuntime.start(appState)
            }
    }
}

private suspend fun syncStartupProxyStatus(
    stateStore: AndroidAppStateStore,
    proxyEngine: AndroidProxyEngine,
    updateAppState: ((AppState) -> AppState) -> Unit,
) {
    val currentState = stateStore.state.value
    if (!currentState.shouldCheckProxyStatusBeforeRuntimeSync()) {
        return
    }
    val status = runCatching { proxyEngine.status(currentState.runMode, currentState) }.getOrNull() ?: return
    updateAppState { state ->
        val runMode = status.runMode.takeIf { mode -> mode?.isRootRunMode() == true } ?: state.runMode
        state.copy(
            proxyRunning = status.running,
            runMode = runMode,
        )
    }
}

private fun AppState.shouldCheckProxyStatusBeforeRuntimeSync(): Boolean {
    return proxyRunning || runMode.isRootRunMode()
}
