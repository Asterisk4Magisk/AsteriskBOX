// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package app.effects

import app.AppState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

// Unlike Mihomo, sing-box keeps no standby runtime in the app process, so pausing only
// disconnects the command client.
internal interface SingBoxRuntimeLifecycleTarget {
    fun resumeObservation(appState: AppState, observation: RuntimeObservation)
    fun pauseForBackground(appState: AppState)
}

internal class SingBoxRuntimeLifecycleCoordinator(
    private val appScope: CoroutineScope,
    private val target: SingBoxRuntimeLifecycleTarget,
    private val backgroundGraceMillis: Long = DefaultBackgroundGraceMillis,
) {
    private val lock = Any()
    private var observation: RuntimeObservation? = null
    private var latestAppState: AppState? = null
    private var backgroundJob: Job? = null

    fun updateAppState(appState: AppState) {
        synchronized(lock) {
            latestAppState = appState
            observation?.let { demand -> target.resumeObservation(appState, demand) }
        }
    }

    fun updateObservation(demand: RuntimeObservation?) {
        synchronized(lock) {
            if (observation == demand) return
            observation = demand
            backgroundJob?.cancel()
            backgroundJob = null
            if (demand != null) {
                latestAppState?.let { target.resumeObservation(it, demand) }
                return
            }
            backgroundJob = appScope.launch {
                delay(backgroundGraceMillis.milliseconds)
                synchronized(lock) {
                    if (observation != null) return@launch
                    backgroundJob = null
                    latestAppState?.let { appState ->
                        target.pauseForBackground(appState)
                    }
                }
            }
        }
    }

    fun close() {
        synchronized(lock) {
            backgroundJob?.cancel()
            backgroundJob = null
            observation = null
        }
    }
}

internal const val DefaultBackgroundGraceMillis = 5_000L
