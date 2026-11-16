// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package app.effects

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import app.modes.SingBoxModeDirect
import data.AndroidAppStateStore
import engine.singbox.config.APP_DIRECT_OUTBOUND
import engine.singbox.config.APP_GLOBAL_SELECTOR
import engine.singbox.runtime.SingBoxRuntimeRepository
import engine.singbox.runtime.SingBoxRuntimeState
import features.singbox.activeSingBoxNodeTag
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first

/**
 * Probes the first testable active node on home entry and resume. Home visibility only gates
 * new probes; an already started probe finishes across navigation and selector changes.
 * Group batches and foreground lifecycle still may cancel it.
 */
@Composable
internal fun NodeDelayAutoProbe(
    stateStore: AndroidAppStateStore,
    singBoxRuntime: SingBoxRuntimeRepository,
    homeVisible: Boolean,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val latestHomeVisible = rememberUpdatedState(homeVisible)
    LaunchedEffect(lifecycleOwner, stateStore, singBoxRuntime) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            snapshotFlow { latestHomeVisible.value }.collect { visible ->
                if (!visible) return@collect
                val revision = singBoxRuntime.state.value.proxySelectionRevision
                val tag = singBoxRuntime.state.awaitResumeProbeNode(revision) {
                    stateStore.state.value.singBoxMode
                } ?: return@collect
                if (!latestHomeVisible.value) return@collect
                singBoxRuntime.probeProxyDelay(stateStore.state.value, tag, SystemClock.elapsedRealtime())
            }
        }
    }
}

internal suspend fun StateFlow<SingBoxRuntimeState>.awaitResumeProbeNode(
    selectionRevision: Long,
    fallbackMode: () -> Int,
): String? {
    val runtime = first {
        it.proxySelectionRevision != selectionRevision || it.activeProbeNode(fallbackMode()) != null
    }
    return if (runtime.proxySelectionRevision != selectionRevision) null else runtime.activeProbeNode(fallbackMode())
}

internal fun SingBoxRuntimeState.activeProbeNode(fallbackMode: Int): String? {
    if (!running || fallbackMode == SingBoxModeDirect) return null
    return activeSingBoxNodeTag(proxies)?.takeUnless { it == APP_DIRECT_OUTBOUND || it == APP_GLOBAL_SELECTOR }
}
