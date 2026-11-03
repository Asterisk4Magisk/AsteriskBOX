// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.home

import engine.singbox.runtime.SingBoxTrafficSample
import kotlin.math.sqrt

/**
 * Normalized amplitudes for the home network activity waveform.
 *
 * `download[index]` and `upload[index]` describe the same `SingBoxTrafficSample`, so both series
 * share one time axis. Every value is in `0f..1f` and is produced by [networkActivityAmplitude],
 * which is also what drives the per-dot color ramp: a dot's height and tone always come from the
 * same normalized speed.
 */
internal class NetworkActivityWaveform(
    val download: FloatArray,
    val upload: FloatArray,
    /** Fastest download and upload sample currently on screen, in bytes per second. */
    val downloadPeakBytes: Long = 0L,
    val uploadPeakBytes: Long = 0L,
) {
    val sampleCount: Int = download.size
}

internal fun buildNetworkActivityWaveform(samples: List<SingBoxTrafficSample>): NetworkActivityWaveform {
    if (samples.isEmpty()) return NetworkActivityWaveform(FloatArray(0), FloatArray(0))
    val downloadBytes = LongArray(samples.size) { index -> samples[index].down.coerceAtLeast(0L) }
    val uploadBytes = LongArray(samples.size) { index -> samples[index].up.coerceAtLeast(0L) }
    // Each direction owns its own reference: a slow upload must not shrink because downloads are fast.
    val downloadReference = networkActivityReferenceBytes(downloadBytes)
    val uploadReference = networkActivityReferenceBytes(uploadBytes)
    return NetworkActivityWaveform(
        download = FloatArray(samples.size) { index ->
            networkActivityAmplitude(downloadBytes[index], downloadReference)
        },
        upload = FloatArray(samples.size) { index ->
            networkActivityAmplitude(uploadBytes[index], uploadReference)
        },
        downloadPeakBytes = downloadBytes.maxOrNull() ?: 0L,
        uploadPeakBytes = uploadBytes.maxOrNull() ?: 0L,
    )
}

/**
 * Two line-chart series sharing one scale: each edge is its own speed divided by the larger of the
 * two window peaks, so the download line and the upload line are directly comparable on the same
 * axis instead of one being stacked on top of the other.
 */
internal class NetworkActivityLineBands(
    val downloadEdge: FloatArray,
    val uploadEdge: FloatArray,
    val downloadPeakBytes: Long = 0L,
    val uploadPeakBytes: Long = 0L,
) {
    val sampleCount: Int = downloadEdge.size
}

internal fun buildNetworkActivityLineBands(
    samples: List<SingBoxTrafficSample>,
): NetworkActivityLineBands {
    if (samples.isEmpty()) {
        return NetworkActivityLineBands(FloatArray(0), FloatArray(0))
    }
    var downloadPeak = 0L
    var uploadPeak = 0L
    samples.forEach { sample ->
        downloadPeak = maxOf(downloadPeak, sample.down.coerceAtLeast(0L))
        uploadPeak = maxOf(uploadPeak, sample.up.coerceAtLeast(0L))
    }
    // Both curves share the larger peak (plus headroom), so their heights can be compared directly.
    val reference = maxOf(downloadPeak, uploadPeak, 1L) * NetworkActivityStackHeadroom
    return NetworkActivityLineBands(
        downloadEdge = FloatArray(samples.size) { index ->
            (samples[index].down.coerceAtLeast(0L) / reference).toFloat()
        },
        uploadEdge = FloatArray(samples.size) { index ->
            (samples[index].up.coerceAtLeast(0L) / reference).toFloat()
        },
        downloadPeakBytes = downloadPeak,
        uploadPeakBytes = uploadPeak,
    )
}

/**
 * Right-aligns the edges inside a fixed [slotCount] axis: leading slots become NaN, the painters'
 * "no sample" sentinel, so the line style shares the dotted style's time axis and never stretches a
 * short buffer across the whole card.
 */
internal fun NetworkActivityLineBands.alignedToSlotCount(slotCount: Int): NetworkActivityLineBands {
    if (slotCount <= sampleCount) return this
    val nanPadding = FloatArray(slotCount - sampleCount) { Float.NaN }
    return NetworkActivityLineBands(
        downloadEdge = nanPadding + downloadEdge,
        uploadEdge = nanPadding + uploadEdge,
        downloadPeakBytes = downloadPeakBytes,
        uploadPeakBytes = uploadPeakBytes,
    )
}

/**
 * Speed that maps to the far edge of a half.
 *
 * The reference follows a high percentile of the window with headroom instead of the window peak,
 * so an isolated spike saturates at the edge rather than flattening every other sample. Idle
 * seconds are excluded from the percentile: a mostly idle window would otherwise pull it down to
 * zero, and every active sample would then saturate at full height. For short windows the index
 * still lands on the largest samples, so startup traffic is scaled to what is actually present.
 * The floor keeps sub-kilobyte speeds measurable.
 */
internal fun networkActivityReferenceBytes(values: LongArray): Float {
    val sorted = values.sortedArray()
    // Sorted ascending, so the idle seconds form a prefix that must not count towards the rank.
    val activeStart = sorted.indexOfFirst { value -> value > 0L }
    if (activeStart < 0) return NetworkActivityReferenceFloorBytes
    val activeCount = sorted.size - activeStart
    val percentileIndex = activeStart + ((activeCount - 1) * NetworkActivityReferencePercentile) / 100
    return maxOf(
        sorted[percentileIndex] * NetworkActivityReferenceHeadroom,
        NetworkActivityReferenceFloorBytes,
    )
}

/**
 * Square-root compression keeps low speeds separated from the axis while still ranking peaks above
 * ordinary traffic, which a linear mapping against a per-window maximum cannot do.
 */
internal fun networkActivityAmplitude(bytes: Long, referenceBytes: Float): Float {
    if (bytes <= 0L || referenceBytes <= 0f) return 0f
    return sqrt((bytes.toFloat() / referenceBytes).coerceIn(0f, 1f))
}

private const val NetworkActivityReferencePercentile = 90
private const val NetworkActivityStackHeadroom = 1.1f
private const val NetworkActivityReferenceHeadroom = 1.35f
private const val NetworkActivityReferenceFloorBytes = 4f * 1024f
