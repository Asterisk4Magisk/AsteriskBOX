// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.settings.sheets

/**
 * UI state for the Apple `networkQuality` test page. Mirrors the husi page
 * structure (config URL, serial, max runtime, http3, outbound tag, report)
 * but flattened to fit the BOX settings-sheet rhythm.
 */
internal data class NetworkQualityReport(
    val idleLatencyMs: Int = 0,
    val downloadCapacityBitsPerSecond: Long = 0L,
    val uploadCapacityBitsPerSecond: Long = 0L,
    val downloadRpm: Int = 0,
    val uploadRpm: Int = 0,
    val elapsedMs: Long = 0L,
    val downloadCapacityAccuracy: Int = -1,
    val uploadCapacityAccuracy: Int = -1,
    val downloadRpmAccuracy: Int = -1,
    val uploadRpmAccuracy: Int = -1,
)

internal const val NETWORK_QUALITY_DEFAULT_CONFIG_URL: String =
    "https://mensura.cdn-apple.com/api/v1/gm/config"

internal const val NETWORK_QUALITY_DEFAULT_MAX_RUNTIME_SECONDS: Int = 20
