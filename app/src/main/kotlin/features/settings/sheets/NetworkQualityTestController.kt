// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.settings.sheets

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import app.AppState
import app.LocalAppServices
import app.LocalAppStateStore
import engine.singbox.NetworkQualityExecutor
import engine.singbox.ServiceNetworkQualityExecutor
import engine.singbox.StandaloneNetworkQualityExecutor
import engine.singbox.runtime.SingBoxRuntimeRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import ui.feedback.AndroidToastTipNotifier

/**
 * In-memory controller for the network quality popup. Owns the live test
 * state (parameters, outbound selection, running job, progress report) and
 * exposes the two popups ([NetworkQualityTestSheet] and
 * [NetworkQualitySettingsSheet]) plus a `show` flag the host flips on and
 * off, mirroring the Sniffer sheet's `SettingsSheetState` pattern.
 *
 * State is in-memory only; nothing is persisted yet.
 */
internal class NetworkQualityTestController {

    // ----- Sheet visibility --------------------------------------------------
    var showSettings by mutableStateOf(false)

    // ----- Test parameters (in-memory) ---------------------------------------
    var configUrl by mutableStateOf(NETWORK_QUALITY_DEFAULT_CONFIG_URL)
    var maxRuntimeSeconds by mutableIntStateOf(NETWORK_QUALITY_DEFAULT_MAX_RUNTIME_SECONDS)
    var serial by mutableStateOf(false)
    var http3 by mutableStateOf(false)

    // ----- Outbound selection -------------------------------------------------
    var outboundTag by mutableStateOf("")

    // ----- Live state ---------------------------------------------------------
    var running by mutableStateOf(false)
    var errorDialog by mutableStateOf<String?>(null)
    val report = mutableStateOf(NetworkQualityReport())

    // ----- Coroutine plumbing (not observable by Compose) --------------------
    private var job: Job? = null
    private var executor: NetworkQualityExecutor? = null

    fun close() {
        showSettings = false
        cancel()
    }

    fun cancel() {
        job?.cancel()
        job = null
        executor?.cancel()
        executor = null
        running = false
    }

    fun start(
        proxyRunning: Boolean,
        appState: AppState,
        runtimeRepository: SingBoxRuntimeRepository,
        snackbarMessage: String,
        tipNotifier: AndroidToastTipNotifier,
        scope: CoroutineScope,
    ) {
        if (running) return
        val effectiveOutboundTag = if (proxyRunning) outboundTag else ""
        val selected: NetworkQualityExecutor = if (proxyRunning) {
            ServiceNetworkQualityExecutor(runtimeRepository, appState)
        } else {
            StandaloneNetworkQualityExecutor()
        }
        executor = selected
        running = true
        report.value = NetworkQualityReport()
        errorDialog = null
        job = scope.launch {
            selected.run(
                configUrl = configUrl,
                outboundTag = effectiveOutboundTag,
                serial = serial,
                maxRuntimeSeconds = maxRuntimeSeconds,
                http3 = http3,
            ).catch { error ->
                running = false
                errorDialog = error.message.orEmpty()
            }.collect { progress ->
                if (progress.error != null) {
                    running = false
                    errorDialog = progress.error
                    return@collect
                }
                report.value = NetworkQualityReport(
                    idleLatencyMs = progress.idleLatencyMs,
                    downloadCapacityBitsPerSecond = progress.downloadCapacityBitsPerSecond,
                    uploadCapacityBitsPerSecond = progress.uploadCapacityBitsPerSecond,
                    downloadRpm = progress.downloadRpm,
                    uploadRpm = progress.uploadRpm,
                    elapsedMs = progress.elapsedMs,
                    downloadCapacityAccuracy = progress.downloadCapacityAccuracy,
                    uploadCapacityAccuracy = progress.uploadCapacityAccuracy,
                    downloadRpmAccuracy = progress.downloadRpmAccuracy,
                    uploadRpmAccuracy = progress.uploadRpmAccuracy,
                )
                if (progress.finished) {
                    running = false
                    tipNotifier.show(snackbarMessage)
                }
            }
        }
    }

    fun reconcileOutboundTag(knownTags: Set<String>) {
        if (outboundTag.isNotEmpty() && outboundTag !in knownTags) {
            outboundTag = ""
        }
    }
}

@Composable
internal fun rememberNetworkQualityTestController(): NetworkQualityTestController {
    val services = LocalAppServices.current
    val stateStore = LocalAppStateStore.current
    val appState by stateStore.state.collectAsState()
    val runtimeState by services.singBoxRuntime.state.collectAsState()

    val controller = remember { NetworkQualityTestController() }
    LaunchedEffect(runtimeState.proxies.nodeByName.keys, appState.proxyRunning) {
        controller.reconcileOutboundTag(runtimeState.proxies.nodeByName.keys)
    }
    DisposableEffect(controller) {
        onDispose { controller.cancel() }
    }
    return controller
}
