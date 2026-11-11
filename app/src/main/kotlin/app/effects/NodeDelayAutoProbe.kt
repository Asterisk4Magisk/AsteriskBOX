// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package app.effects

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import app.modes.SingBoxModeDirect
import data.AndroidAppStateStore
import engine.singbox.config.APP_DIRECT_OUTBOUND
import engine.singbox.config.APP_GLOBAL_SELECTOR
import engine.singbox.runtime.SingBoxRuntimeRepository
import features.singbox.activeSingBoxNodeTag
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Keeps the active node's latency fresh while the app is in the foreground: it is probed when the
 * service comes up, when the node changes and on every resume. The runtime throttles repeats of
 * the same node and lets any user-started test preempt a probe, so this effect only decides when.
 */
@Composable
internal fun NodeDelayAutoProbe(
    stateStore: AndroidAppStateStore,
    singBoxRuntime: SingBoxRuntimeRepository,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner, stateStore, singBoxRuntime) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            singBoxRuntime.state
                // The state ticks every second for traffic; the proxy snapshot keeps its identity
                // in between, so the node is resolved only when the snapshot really changes.
                .map { runtime -> runtime.running to runtime.proxies }
                .distinctUntilChanged()
                .map { (running, proxies) -> if (running) activeSingBoxNodeTag(proxies) else null }
                .distinctUntilChanged()
                // A node change cancels the probe of the previous one.
                .collectLatest { tag ->
                    // Direct and the selector itself are not nodes the core can test.
                    if (tag == null || tag == APP_DIRECT_OUTBOUND || tag == APP_GLOBAL_SELECTOR) return@collectLatest
                    val appState = stateStore.state.value
                    if (appState.singBoxMode == SingBoxModeDirect) return@collectLatest
                    singBoxRuntime.probeProxyDelay(appState, tag, SystemClock.elapsedRealtime())
                }
        }
    }
}
