// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package app.effects

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import app.AppState
import app.modes.isRootRunMode
import data.AndroidAppStateStore
import engine.proxy.withResolvedDynamicLocalProxyPort
import features.logs.AndroidAppLogger
import features.settings.usecase.RootBootScriptResult
import features.settings.usecase.RootBootScriptUseCase
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

@Composable
internal fun RootBootScriptSynchronizer(
    stateStore: AndroidAppStateStore,
    rootBootScriptUseCase: RootBootScriptUseCase,
) {
    LaunchedEffect(stateStore, rootBootScriptUseCase) {
        stateStore.state
            .map { state -> state.toRootBootScriptRefresh() }
            .distinctUntilChanged { previous, next -> previous.signature == next.signature }
            .conflate()
            .collect { refresh ->
                val state = refresh.appState.withResolvedDynamicLocalProxyPort()
                if (state != refresh.appState) {
                    stateStore.update { currentState ->
                        if (currentState == refresh.appState) state else currentState
                    }
                    return@collect
                }
                if (!state.enableRootBootScript || !state.runMode.isRootRunMode()) {
                    return@collect
                }
                when (
                    val result = rootBootScriptUseCase.refresh(state) {
                        val currentState = stateStore.state.value
                        currentState.enableRootBootScript &&
                            currentState.runMode.isRootRunMode() &&
                            currentState.toRootBootScriptRefresh().signature == refresh.signature
                    }
                ) {
                    RootBootScriptResult.Success -> Unit
                    RootBootScriptResult.RootUnavailable -> AndroidAppLogger.warn(
                        LogTag,
                        "Skipped ROOT boot script refresh because root access is unavailable",
                    )
                    is RootBootScriptResult.Failed -> AndroidAppLogger.warn(
                        LogTag,
                        "Failed to refresh ROOT boot script",
                        result.error,
                    )
                }
            }
    }
}

private data class RootBootScriptRefresh(
    val appState: AppState,
    val signature: AppState,
)

private fun AppState.toRootBootScriptRefresh(): RootBootScriptRefresh {
    return RootBootScriptRefresh(
        appState = this,
        signature = copy(
            colorMode = 0,
            languageMode = 0,
            seedIndex = 0,
            singBoxProxyLayout = 0,
            singBoxProxySort = 0,
            proxyRunning = false,
            enableTrafficStatsNotification = false,
            nextCustomResourceFileId = 0,
        ),
    )
}

private const val LogTag = "RootBootScript"
