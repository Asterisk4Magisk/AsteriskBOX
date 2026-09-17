// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package engine.singbox

/**
 * Mirrors the `libbox.NetworkQualityPhase*` int constants exposed by
 * `io.nekohasekai.libbox.Libbox`:
 *   Idle = 0, Download = 1, Upload = 2, Done = 3
 *
 * Values map to the wire constants used by both `NetworkQualityProgress.phase`
 * and the aar `NetworkQualityPhase*` ints, so callers can convert in both
 * directions without hard-coding magic numbers.
 */
internal enum class NetworkQualityPhase(val wire: Int) {
    Idle(0),
    Download(1),
    Upload(2),
    Done(3),
    ;

    companion object {
        fun ofWire(wireValue: Int): NetworkQualityPhase =
            entries.firstOrNull { it.wire == wireValue } ?: Idle
    }
}

/**
 * One snapshot delivered to the UI from either a service-mode or standalone
 * run. Mirrors [io.nekohasekai.libbox.NetworkQualityProgress] but flattened
 * for Compose consumption. `finished` is set on the terminal snapshot (phase
 * == [NetworkQualityPhase.Done] for success, or any phase paired with an
 * [error]).
 */
internal data class NetworkQualityProgress(
    val phase: NetworkQualityPhase = NetworkQualityPhase.Idle,
    val downloadCapacityBitsPerSecond: Long = 0L,
    val uploadCapacityBitsPerSecond: Long = 0L,
    val downloadRpm: Int = 0,
    val uploadRpm: Int = 0,
    val idleLatencyMs: Int = 0,
    val elapsedMs: Long = 0L,
    val downloadCapacityAccuracy: Int = 0,
    val uploadCapacityAccuracy: Int = 0,
    val downloadRpmAccuracy: Int = 0,
    val uploadRpmAccuracy: Int = 0,
    val error: String? = null,
    val finished: Boolean = false,
)
