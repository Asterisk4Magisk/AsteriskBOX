// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package app.effects

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import app.modes.SingBoxModeDirect
import engine.singbox.config.APP_DIRECT_OUTBOUND
import engine.singbox.config.APP_GLOBAL_SELECTOR
import data.AndroidAppStateStore
import engine.singbox.runtime.SingBoxRuntimeRepository
import features.singbox.activeSingBoxNodeTag
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * The hero card and the proxy list both read the delay the core reports, so the value is only
 * useful while it is fresh. Probe the active node as soon as the service comes up and every time
 * the app returns to the foreground; the throttle keeps a start and a resume in the same second
 * from firing two tests.
 */
@Composable
internal fun NodeDelayAutoProbe(
    stateStore: AndroidAppStateStore,
    singBoxRuntime: SingBoxRuntimeRepository,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    // No foreground counter in the keys: `repeatOnLifecycle` below already restarts the collector on
    // every resume, and keeping the throttle outside that restart is the whole point.
    val throttle = remember(singBoxRuntime) { DelayProbeThrottle() }

    LaunchedEffect(lifecycleOwner, stateStore, singBoxRuntime) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            singBoxRuntime.state
                .map { runtime -> ActiveNodeSnapshot(runtime.running, activeSingBoxNodeTag(runtime.proxies)) }
                .distinctUntilChanged()
                .collect { snapshot ->
                    val tag = snapshot.tag
                    if (!snapshot.running || tag == null) return@collect
                    // Direct and the selector itself are not nodes the core can test.
                    if (tag == APP_DIRECT_OUTBOUND || tag == APP_GLOBAL_SELECTOR) return@collect
                    val appState = stateStore.state.value
                    if (appState.singBoxMode == SingBoxModeDirect) return@collect
                    // Nothing runs before the service is up, but the collector also restarts on
                    // every resume, so gate the duplicates by time instead of by event.
                    if (!throttle.take(SystemClock.elapsedRealtime())) return@collect
                    // testProxyDelay already reports failures as a Result; a CancellationException
                    // must stay uncaught so the collector can be cancelled.
                    singBoxRuntime.testProxyDelay(appState, tag)
                }
        }
    }
}

private data class ActiveNodeSnapshot(
    val running: Boolean,
    val tag: String?,
)

/** Short enough to feel automatic, long enough that a start plus a resume test only once. */
private const val NodeDelayProbeThrottleMillis = 6_000L

private class DelayProbeThrottle {
    private var lastAtMillis = 0L

    fun take(nowMillis: Long): Boolean {
        if (nowMillis - lastAtMillis < NodeDelayProbeThrottleMillis) return false
        lastAtMillis = nowMillis
        return true
    }
}
