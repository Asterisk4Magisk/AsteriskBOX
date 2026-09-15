// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package engine.root.runtime

import androidx.annotation.StringRes
import app.R
import app.modes.RunModeEbpf
import engine.root.mode.RootModeCatalog

/**
 * Pure mapping from a failed start to a structured [ProxyErrorExplanation].
 *
 * Two entry points exist:
 *  - [analyze] with a [Throwable], for synchronous start failures surfaced by
 *    `RootModeEngine.start`.
 *  - [analyze] with the persisted supervisor failure fields, for the asynchronous case where
 *    the core process dies after the start call already returned.
 *
 * The result always carries the real error text; the optional suggested-action list is only
 * attached when the error matches a rule that was confirmed on real hardware.
 *
 * Rule policy: a rule may only be added once the corresponding failure has actually been
 * observed and diagnosed on a device. Do NOT add speculative rules — an unmatched error must
 * degrade to "error text only" rather than receive a guessed remedy.
 */
internal object RootEbpfFailureAnalyzer {

    private data class Rule(
        /** RunMode constant from `app.modes`. Only [RunModeEbpf] is wired today. */
        val runMode: Int,
        val matcher: Regex,
        @StringRes val diagnostics: IntArray,
    )

    private val rules: List<Rule> = listOf(
        // Observed on alioth (kernel 4.19 + KernelSU Next): the eBPF `tc` data plane needs a
        // self-created veth pair plus a clsact qdisc. strace of the core showed the kernel
        // rejecting the veth link creation with EOPNOTSUPP, which sing-box reports against the
        // clsact step. The cgroup data plane starts normally on the same device.
        Rule(
            runMode = RunModeEbpf,
            matcher = Regex("""create TC eBPF delivery link:\s*operation not supported"""),
            diagnostics = intArrayOf(
                R.string.proxy_error_ebpf_tc_unsupported_explanation,
                R.string.proxy_error_ebpf_tc_unsupported_switch,
                R.string.proxy_error_ebpf_tc_unsupported_fallback,
                R.string.proxy_error_ebpf_tc_unsupported_report,
            ),
        ),
    )

    /** Synchronous start failure: the throwable carries the underlying asteriskd / core error. */
    fun analyze(runMode: Int, error: Throwable, occurredAtEpochMillis: Long): ProxyErrorExplanation {
        val rawMessage = extractRawMessage(error)
        return buildExplanation(runMode, rawMessage, occurredAtEpochMillis)
    }

    /**
     * Asynchronous failure: built from the persisted supervisor state after the daemon exited.
     *
     * [extraContext] carries the newest core FATAL line, which holds the actionable signature
     * that the supervisor-level message does not.
     */
    fun analyze(
        errorCode: String,
        exitCode: Int?,
        message: String?,
        mode: String,
        extraContext: String? = null,
        occurredAtEpochMillis: Long,
    ): ProxyErrorExplanation {
        val runMode = modeToRunMode(mode)
        val rawMessage = buildString {
            append('[')
            append(errorCode)
            append("] ")
            append(message ?: "")
            if (exitCode != null) append(" (exitCode=$exitCode)")
            if (!extraContext.isNullOrBlank()) {
                append('\n')
                append(extraContext)
            }
        }
        return buildExplanation(runMode, rawMessage, occurredAtEpochMillis)
    }

    private fun modeToRunMode(mode: String): Int =
        RootModeCatalog.definitions.firstOrNull { it.daemonMode.wireValue == mode }?.runMode
            ?: error("Unknown asteriskd mode: $mode")

    private fun buildExplanation(runMode: Int, rawMessage: String, occurredAtEpochMillis: Long): ProxyErrorExplanation {
        val diagnostics = rules
            .filter { it.runMode == runMode }
            .firstOrNull { it.matcher.containsMatchIn(rawMessage) }
            ?.diagnostics
            ?.toList()
            ?: emptyList()
        return ProxyErrorExplanation(
            mode = modeWireValue(runMode),
            occurredAtEpochMillis = occurredAtEpochMillis,
            rawMessage = rawMessage,
            diagnostics = diagnostics,
        )
    }

    /**
     * Walk the cause chain until we find the deepest non-blank `message`. `RootModeEngine.start`
     * wraps the original error in `IllegalStateException(getString(startFailedErrorResId, ...))`,
     * so the underlying sing-box / asteriskd error lives on `cause`.
     */
    private fun extractRawMessage(error: Throwable): String {
        var current: Throwable? = error
        var best: String = error.message.orEmpty()
        while (current != null) {
            current.message?.takeIf { it.isNotBlank() }?.let { best = it }
            current = current.cause
        }
        return best
    }

    /** Wire value of the daemon mode the failed start targeted, for UI labelling. */
    private fun modeWireValue(runMode: Int): String =
        RootModeCatalog.find(runMode)?.daemonMode?.wireValue ?: "unknown"
}
