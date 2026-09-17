// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package engine.singbox

import app.AppState
import engine.singbox.runtime.SingBoxRuntimeRepository
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.NetworkQualityProgress as LibboxNetworkQualityProgress
import io.nekohasekai.libbox.NetworkQualityResult as LibboxNetworkQualityResult
import io.nekohasekai.libbox.NetworkQualityTest
import io.nekohasekai.libbox.NetworkQualityTestHandler
import io.nekohasekai.libbox.NetworkQualityTestSession
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.Dispatchers

/**
 * Unified surface for running an Apple `networkQuality` test, with two
 * concrete paths:
 *
 *  - [ServiceNetworkQualityExecutor] — runs while the proxy service is up and
 *    routes through the existing command client. Forwards to
 *    `daemon.StartedService/StartNetworkQualityTest` on the gRPC control
 *    channel used by every other runtime command. Honours the `outboundTag`
 *    parameter and never touches ROOT shell, iptables, or BPF state.
 *  - [StandaloneNetworkQualityExecutor] — runs without the service. Uses the
 *    process-internal `Libbox.newNetworkQualityTest` entry point, which makes
 *    raw HTTPS requests directly; no ROOT, no gRPC control plane, no
 *    persistent state.
 *
 * Both paths terminate by either an `onResult` (phase = [NetworkQualityPhase.Done])
 * or `onError` callback; the resulting terminal [NetworkQualityProgress]
 * always has `finished = true`.
 */
internal interface NetworkQualityExecutor {
    fun run(
        configUrl: String,
        outboundTag: String,
        serial: Boolean,
        maxRuntimeSeconds: Int,
        http3: Boolean,
    ): Flow<NetworkQualityProgress>

    fun cancel()
}

internal class ServiceNetworkQualityExecutor(
    private val repository: SingBoxRuntimeRepository,
    private val appState: AppState,
) : NetworkQualityExecutor {
    private val sessionRef = AtomicReference<NetworkQualityTestSession?>()

    override fun run(
        configUrl: String,
        outboundTag: String,
        serial: Boolean,
        maxRuntimeSeconds: Int,
        http3: Boolean,
    ): Flow<NetworkQualityProgress> = callbackFlow {
        val client = repository.activeCommandClient(appState)
        val latestProgress = AtomicReference(LibboxNetworkQualityProgress())
        val handler = object : NetworkQualityTestHandler {
            override fun onProgress(progress: LibboxNetworkQualityProgress) {
                latestProgress.set(progress)
                trySend(progress.toModel())
            }

            override fun onError(message: String) {
                trySend(
                    NetworkQualityProgress(
                        elapsedMs = latestProgress.get().elapsedMs,
                        error = message,
                        finished = true,
                    ),
                )
                close()
            }

            override fun onResult(result: LibboxNetworkQualityResult) {
                trySend(
                    NetworkQualityProgress(
                        phase = NetworkQualityPhase.Done,
                        downloadCapacityBitsPerSecond = result.downloadCapacity,
                        uploadCapacityBitsPerSecond = result.uploadCapacity,
                        downloadRpm = result.downloadRPM,
                        uploadRpm = result.uploadRPM,
                        idleLatencyMs = result.idleLatencyMs,
                        elapsedMs = latestProgress.get().elapsedMs,
                        downloadCapacityAccuracy = result.downloadCapacityAccuracy,
                        uploadCapacityAccuracy = result.uploadCapacityAccuracy,
                        downloadRpmAccuracy = result.downloadRPMAccuracy,
                        uploadRpmAccuracy = result.uploadRPMAccuracy,
                        finished = true,
                    ),
                )
                close()
            }
        }
        val session = client.startNetworkQualityTest(
            configURL = configUrl,
            outboundTag = outboundTag,
            serial = serial,
            maxRuntimeSeconds = maxRuntimeSeconds,
            http3 = http3,
            handler = handler,
        )
        sessionRef.set(session)
        awaitClose {
            sessionRef.set(null)
            runCatching { session.close() }
        }
    }.flowOn(Dispatchers.IO)

    override fun cancel() {
        sessionRef.getAndSet(null)?.let { runCatching { it.close() } }
    }
}

internal class StandaloneNetworkQualityExecutor : NetworkQualityExecutor {
    private val testRef = AtomicReference<NetworkQualityTest?>()

    override fun run(
        configUrl: String,
        outboundTag: String,
        serial: Boolean,
        maxRuntimeSeconds: Int,
        http3: Boolean,
    ): Flow<NetworkQualityProgress> = callbackFlow {
        // The standalone entry point intentionally ignores `outboundTag`; it
        // does not route through the proxy and has no proxy context to honour.
        @Suppress("UNUSED_PARAMETER")
        val ignored = outboundTag
        val test = Libbox.newNetworkQualityTest()
        val latestProgress = AtomicReference(LibboxNetworkQualityProgress())
        val handler = object : NetworkQualityTestHandler {
            override fun onProgress(progress: LibboxNetworkQualityProgress) {
                latestProgress.set(progress)
                trySend(progress.toModel())
            }

            override fun onError(message: String) {
                trySend(
                    NetworkQualityProgress(
                        elapsedMs = latestProgress.get().elapsedMs,
                        error = message,
                        finished = true,
                    ),
                )
                close()
            }

            override fun onResult(result: LibboxNetworkQualityResult) {
                trySend(
                    NetworkQualityProgress(
                        phase = NetworkQualityPhase.Done,
                        downloadCapacityBitsPerSecond = result.downloadCapacity,
                        uploadCapacityBitsPerSecond = result.uploadCapacity,
                        downloadRpm = result.downloadRPM,
                        uploadRpm = result.uploadRPM,
                        idleLatencyMs = result.idleLatencyMs,
                        elapsedMs = latestProgress.get().elapsedMs,
                        downloadCapacityAccuracy = result.downloadCapacityAccuracy,
                        uploadCapacityAccuracy = result.uploadCapacityAccuracy,
                        downloadRpmAccuracy = result.downloadRPMAccuracy,
                        uploadRpmAccuracy = result.uploadRPMAccuracy,
                        finished = true,
                    ),
                )
                close()
            }
        }
        testRef.set(test)
        test.start(configUrl, serial, maxRuntimeSeconds, http3, handler)
        awaitClose {
            testRef.set(null)
            runCatching { test.cancel() }
        }
    }.flowOn(Dispatchers.IO)

    override fun cancel() {
        testRef.getAndSet(null)?.let { runCatching { it.cancel() } }
    }
}

private fun LibboxNetworkQualityProgress.toModel(): NetworkQualityProgress = NetworkQualityProgress(
    phase = NetworkQualityPhase.ofWire(phase),
    downloadCapacityBitsPerSecond = downloadCapacity,
    uploadCapacityBitsPerSecond = uploadCapacity,
    downloadRpm = downloadRPM,
    uploadRpm = uploadRPM,
    idleLatencyMs = idleLatencyMs,
    elapsedMs = elapsedMs,
    downloadCapacityAccuracy = downloadCapacityAccuracy,
    uploadCapacityAccuracy = uploadCapacityAccuracy,
    downloadRpmAccuracy = downloadRPMAccuracy,
    uploadRpmAccuracy = uploadRPMAccuracy,
)
