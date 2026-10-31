// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package app.effects

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import data.AndroidAppStateStore

@Composable
internal fun SingBoxRuntimeSynchronizer(
    stateStore: AndroidAppStateStore,
    singBoxRuntimeLifecycle: SingBoxRuntimeLifecycleCoordinator,
) {
    LaunchedEffect(stateStore, singBoxRuntimeLifecycle) {
        stateStore.state
            .collect { appState ->
                singBoxRuntimeLifecycle.updateAppState(appState)
            }
    }
}
