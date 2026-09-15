// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package engine.root.runtime

import android.content.Context
import engine.root.publication.RootRuntimeLayout
import features.logs.AndroidAppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import system.RootShellGateway
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Process-wide watcher for ROOT supervisor failures.
 *
 * The supervisor runs out of process (asteriskd under KSU's root context) and reports failures
 * through `asteriskd.state`. When the core process dies after reaching `running` — the common
 * eBPF case, where sing-box accepts its config and then aborts — the start call has already
 * returned successfully, so nothing synchronous observes the failure. This watcher bridges
 * that gap: it tails the state file and publishes a [ProxyErrorExplanation] to [ProxyErrorBus]
 * so the UI can surface a dialog.
 *
 * Exactly one watcher runs per application process, regardless of how many mode controllers
 * exist. A second [ensureStarted] call is a no-op.
 *
 * Freshness rule: a failure is surfaced only when the state file's modification time differs
 * from both the value captured when the watcher started and the value already published. A
 * failure record left behind by a previous session therefore never raises a dialog on app
 * launch; only a failure written while this process is running does.
 */
internal object RootFailureWatcher {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val started = AtomicBoolean(false)
    private val attemptReset = AtomicBoolean(false)

    fun ensureStarted(context: Context, shell: RootShellGateway, layout: RootRuntimeLayout) {
        if (!started.compareAndSet(false, true)) return
        // Capture the baseline here, synchronously, before the watcher coroutine is scheduled.
        // `scope.launch` gives no ordering guarantee, and a supervisor failure landing in that
        // gap would otherwise be recorded as the baseline and then dismissed as a leftover
        // record — silently dropping the very failure this watcher exists to report.
        val baselineMtime = runCatching { File(layout.asteriskdStatePath).lastModified() }
            .getOrDefault(0L)
        scope.launch { watch(context.applicationContext, shell, layout, baselineMtime) }
    }

    /**
     * Signal that a new start attempt has begun, so a failure from it publishes again even if the
     * previous episode's failure is still the newest record in the state file.
     *
     * Without this the watcher would depend on observing an intermediate failure-free state write
     * to re-arm. That write is normally seen, but if it lands between two polls the retry's
     * failure would be swallowed with no dialog at all — a silent failure of the exact feature
     * this exists to provide. An explicit signal removes that dependency.
     */
    fun beginAttempt() {
        attemptReset.set(true)
    }

    private suspend fun watch(
        context: Context,
        shell: RootShellGateway,
        layout: RootRuntimeLayout,
        baselineMtime: Long,
    ) {
        val statePath = layout.asteriskdStatePath
        val stateFile = File(statePath)
        val errorLogPath = layout.logDirectoryPath + "/error.log"

        var lastSeenMtime = NOT_CAPTURED
        // The supervisor writes the state file several times for a single failure (failed, then
        // stopping, then stopped), each write bumping the mtime, and the rendered failure shifts
        // slightly between those writes. Keying publication on the failure code instead of on the
        // write means one dialog per failure episode, while a change of code within the episode
        // still gets through.
        var publishedErrorCode: String? = null

        while (true) {
            val mtime = runCatching { stateFile.lastModified() }.getOrDefault(0L)

            if (attemptReset.compareAndSet(true, false)) {
                publishedErrorCode = null
            }

            if (mtime > 0L && mtime != lastSeenMtime) {
                lastSeenMtime = mtime
                val state = readState(shell, statePath)
                val errorCode = state?.errorCode
                when {
                    state == null -> Unit
                    // A settled, failure-free state closes the episode: the next failure publishes.
                    errorCode == null -> publishedErrorCode = null
                    mtime == baselineMtime -> Unit
                    errorCode == publishedErrorCode -> Unit
                    else -> {
                        publishedErrorCode = errorCode
                        val occurredAt = System.currentTimeMillis()
                        val report = RootFailureReport.build(context, shell, layout, occurredAt)
                        val explanation = RootEbpfFailureAnalyzer.analyze(
                            errorCode = errorCode,
                            exitCode = state.exitCode,
                            message = state.errorMessage,
                            mode = state.mode,
                            extraContext = readLastFatalLine(shell, errorLogPath),
                            occurredAtEpochMillis = occurredAt,
                        ).copy(
                            deviceInfo = report.deviceInfo,
                            serviceLog = report.serviceLog,
                        )
                        runCatching {
                            AndroidAppLogger.warn(
                                LogTag,
                                "proxy_failure mode=${explanation.mode} code=$errorCode diagnostics=${explanation.diagnostics.size}",
                            )
                        }
                        ProxyErrorBus.publish(explanation)
                    }
                }
            }
            delay(PollIntervalMillis)
        }
    }

    private data class SupervisorState(
        val mode: String,
        val errorCode: String?,
        val exitCode: Int?,
        val errorMessage: String?,
    )

    /**
     * Parse the persisted supervisor state. The failure is optional: a healthy or in-progress
     * state parses too, and reporting it as [SupervisorState.errorCode] `null` is what lets the
     * watcher close a failure episode. Returning `null` for the whole state instead — as an
     * earlier version did when `failure.code` was absent — made that reset unreachable, so a
     * later attempt repeating the previous failure code produced no dialog at all.
     */
    private suspend fun readState(shell: RootShellGateway, path: String): SupervisorState? {
        val text = RootFailureReport.readText(shell, path) ?: return null
        return runCatching {
            val state = JSONObject(text)
            val failure = state.optJSONObject("failure")
            SupervisorState(
                mode = state.optString("mode"),
                errorCode = failure?.optString("code")?.takeIf { it.isNotEmpty() },
                exitCode = failure?.optInt("exitCode", -1)?.takeIf { it >= 0 },
                errorMessage = failure?.optString("message")?.takeIf { it.isNotEmpty() },
            )
        }.getOrNull()
    }

    /**
     * Read the newest sing-box FATAL line from the service error log. The state file only
     * carries the supervisor-level message ("required core exited"); the actionable signature
     * (for example "create TC eBPF delivery link: operation not supported") is written by the
     * core to its own error log.
     */
    private suspend fun readLastFatalLine(shell: RootShellGateway, path: String): String? {
        val text = RootFailureReport.readText(shell, path) ?: return null
        return text.lineSequence()
            .map(String::trim)
            .filter { it.startsWith(FatalPrefix) }
            .lastOrNull()
    }

    private const val NOT_CAPTURED = Long.MIN_VALUE
    private const val PollIntervalMillis = 500L
    private const val FatalPrefix = "FATAL["
    private const val LogTag = "RootFailureWatcher"
}
