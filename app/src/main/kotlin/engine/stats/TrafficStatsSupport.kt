// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package engine.stats

import app.AppState
import app.modes.RunModeVpnService
import engine.singbox.SingBoxControlConfig
import engine.singbox.singBoxControlConfig
import java.util.concurrent.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Owns one traffic sampling session. Lifecycle methods are called on the service
 * main thread; workers can publish notification changes only through their session.
 */
internal class TrafficStatsMonitor(
    private val scope: CoroutineScope,
    private val lifecycleDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
) {
    private var session: Session? = null
    private var job: Job? = null

    val isActive: Boolean
        get() = job?.isActive == true

    fun start(block: suspend Session.() -> Unit) {
        cancel()
        val next = Session()
        session = next
        job = scope.launch { next.block() }
    }

    fun cancel() {
        session = null
        job?.cancel()
        job = null
    }

    inner class Session internal constructor() {
        suspend fun publish(action: () -> Unit) {
            withContext(lifecycleDispatcher) {
                currentCoroutineContext().ensureActive()
                if (session === this@Session) action()
            }
        }
    }
}

internal fun tryStartTrafficStatsService(
    start: () -> Unit,
    onFailure: (Exception) -> Unit,
): Boolean {
    return try {
        start()
        true
    } catch (error: Exception) {
        if (error is CancellationException) throw error
        onFailure(error)
        false
    }
}

/**
 * Serializes start and stop requests for a traffic stats foreground service.
 *
 * After startForegroundService(), the service must call startForeground() before it is
 * stopped; a stopService() issued earlier makes the system crash the app with
 * ForegroundServiceDidNotStartInTimeException. While a start request is undelivered,
 * stop requests are therefore deferred to the service, which enters the foreground,
 * consumes the request and stops itself when no runtime is requested anymore.
 */
internal class TrafficStatsServiceRequests<T : Any> {
    private val lock = Any()
    private var pendingStarts = 0
    private var requestedRuntime: T? = null

    fun start(runtime: T, requestStart: () -> Boolean) {
        synchronized(lock) {
            requestedRuntime = runtime
            if (requestStart()) pendingStarts += 1
        }
    }

    fun stop(stopService: () -> Unit) {
        synchronized(lock) {
            requestedRuntime = null
            if (pendingStarts == 0) stopService()
        }
    }

    /** Called by the service for each delivered start request, after startForeground(). */
    fun consumeStart(): T? {
        synchronized(lock) {
            if (pendingStarts > 0) pendingStarts -= 1
            return requestedRuntime
        }
    }
}

internal data class SingBoxTrafficStatsRuntime(
    val control: SingBoxControlConfig,
    val local: Boolean,
)

internal fun AppState.toSingBoxTrafficStatsRuntime(
    runMode: Int = this.runMode,
): SingBoxTrafficStatsRuntime? {
    if (!enableTrafficStatsNotification) return null
    return SingBoxTrafficStatsRuntime(
        control = singBoxControlConfig(),
        local = runMode == RunModeVpnService,
    )
}

internal data class TrafficStatsNotificationContent(
    val title: String,
    val summary: String,
    val expandedText: String,
)

internal fun trafficStatsNotificationContent(
    appName: String,
    speedLine: String,
    trafficLine: String,
): TrafficStatsNotificationContent {
    return TrafficStatsNotificationContent(
        title = appName,
        summary = speedLine,
        expandedText = "$speedLine\n$trafficLine",
    )
}
