// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package app.effects

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import data.AndroidAppStateStore
import engine.singbox.runtime.SingBoxRuntimeRepository

@Composable
internal fun SingBoxRuntimeSynchronizer(
    stateStore: AndroidAppStateStore,
    singBoxRuntime: SingBoxRuntimeRepository,
) {
    LaunchedEffect(stateStore, singBoxRuntime) {
        stateStore.state
            .collect { appState ->
                singBoxRuntime.start(appState)
            }
    }
}
