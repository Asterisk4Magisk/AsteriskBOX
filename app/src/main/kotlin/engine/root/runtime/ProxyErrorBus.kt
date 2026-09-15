// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package engine.root.runtime

import androidx.annotation.StringRes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A structured explanation of a failed proxy start.
 *
 * [rawMessage] is always the real error text read from the device (supervisor state plus the
 * core's own FATAL line, or the thrown exception). [diagnostics] holds string resource ids for
 * suggested actions, and is empty unless the error matched a rule confirmed on real hardware.
 *
 * Produced by [RootEbpfFailureAnalyzer]; UI hosts consume [ProxyErrorBus] and render the
 * explanation via [features.singbox.ProxyErrorDialog].
 */
internal data class ProxyErrorExplanation(
    val mode: String,
    val occurredAtEpochMillis: Long,
    val rawMessage: String,
    @StringRes val diagnostics: List<Int>,
) {
    val hasDiagnostics: Boolean get() = diagnostics.isNotEmpty()
}

/**
 * Module-scoped publisher for proxy start failures. One slot; UI hosts `collectAsState` it
 * and pop the dialog; the user dismisses by calling [acknowledge].
 *
 * Lifetime is tied to the application process; on cold start the slot is `null`.
 */
internal object ProxyErrorBus {
    private val state = MutableStateFlow<ProxyErrorExplanation?>(null)

    fun observe(): StateFlow<ProxyErrorExplanation?> = state.asStateFlow()

    fun publish(explanation: ProxyErrorExplanation) {
        state.value = explanation
    }

    fun acknowledge() {
        state.value = null
    }
}