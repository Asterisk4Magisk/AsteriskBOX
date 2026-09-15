// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package engine.root.runtime

import engine.root.publication.RootRuntimeLayout
import features.logs.AndroidAppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import system.RootShellGateway
import system.ShellExecOptions
import utils.shellQuote
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

    fun ensureStarted(shell: RootShellGateway, layout: RootRuntimeLayout) {
        if (!started.compareAndSet(false, true)) return
        scope.launch { watch(shell, layout) }
    }

    private suspend fun watch(shell: RootShellGateway, layout: RootRuntimeLayout) {
        val statePath = layout.asteriskdStatePath
        val stateFile = File(statePath)
        val errorLogPath = layout.logDirectoryPath + "/error.log"

        var baselineMtime = NOT_CAPTURED
        var lastSeenMtime = NOT_CAPTURED
        var publishedMtime = NOT_CAPTURED

        while (true) {
            val mtime = runCatching { stateFile.lastModified() }.getOrDefault(0L)
            if (baselineMtime == NOT_CAPTURED) baselineMtime = mtime

            if (mtime > 0L && mtime != lastSeenMtime) {
                lastSeenMtime = mtime
                val state = readState(shell, statePath)
                val errorCode = state?.errorCode
                if (state != null && errorCode != null &&
                    mtime != baselineMtime && mtime != publishedMtime
                ) {
                    publishedMtime = mtime
                    val explanation = RootEbpfFailureAnalyzer.analyze(
                        errorCode = errorCode,
                        exitCode = state.exitCode,
                        message = state.errorMessage,
                        mode = state.mode,
                        extraContext = readLastFatalLine(shell, errorLogPath),
                        occurredAtEpochMillis = System.currentTimeMillis(),
                    )
                    runCatching {
                        AndroidAppLogger.warn(
                            LogTag,
                            "proxy_failure mode=${explanation.mode} diagnostics=${explanation.diagnostics.size}",
                        )
                    }
                    ProxyErrorBus.publish(explanation)
                }
            }
            delay(PollIntervalMillis)
        }
    }

    private data class SupervisorState(
        val mode: String,
        val errorCode: String,
        val exitCode: Int?,
        val errorMessage: String?,
    )

    private suspend fun readState(shell: RootShellGateway, path: String): SupervisorState? {
        val text = readFileText(shell, path) ?: return null
        return runCatching {
            val failure = JSONObject(text).optJSONObject("failure")
            val code = failure?.optString("code")?.takeIf { it.isNotEmpty() } ?: return null
            SupervisorState(
                mode = JSONObject(text).optString("mode"),
                errorCode = code,
                exitCode = failure.optInt("exitCode", -1).takeIf { it >= 0 },
                errorMessage = failure.optString("message").takeIf { it.isNotEmpty() },
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
        val text = readFileText(shell, path) ?: return null
        return text.lineSequence()
            .map(String::trim)
            .filter { it.startsWith(FatalPrefix) }
            .lastOrNull()
    }

    /**
     * The supervisor writes its files as root, so they are not directly readable by the app
     * uid; read them through the ROOT shell gateway. A direct read is attempted as a fallback
     * for setups where the file is app-readable.
     */
    private suspend fun readFileText(shell: RootShellGateway, path: String): String? {
        val viaShell = runCatching {
            val result = shell.exec("cat ${path.shellQuote()}", ShellExecOptions(logFailure = false))
            if (result.errno == 0 && result.stdout.isNotBlank()) result.stdout else null
        }.getOrNull()
        if (viaShell != null) return viaShell
        return runCatching {
            File(path).takeIf { it.isFile && it.canRead() }?.readText()
        }.getOrNull()
    }

    private const val NOT_CAPTURED = Long.MIN_VALUE
    private const val PollIntervalMillis = 500L
    private const val FatalPrefix = "FATAL["
    private const val LogTag = "RootFailureWatcher"
}
