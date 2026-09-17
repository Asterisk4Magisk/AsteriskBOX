// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.settings.sheets

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import ui.isInDarkTheme

/**
 * Per-metric rating buckets the popup attaches to each card. Good / Fair /
 * Poor use the husi status palette so the on-screen chip reads as a
 * network-quality verdict, not an abstract "accuracy" tag.
 *
 * Unrated means the metric was not part of the latest test (or carries no
 * useful interpretation, like total elapsed time).
 */
internal enum class MetricRating { Good, Fair, Poor, Unrated }

/**
 * Light-mode base colours for the husi status palette, taken straight from
 * AsteriskBOX @ 68251ffdc298351db53bd824bac8dbdc06dd7069 (which itself
 * ports `fr.husi.compose.colorForUrlTestDelay`). In dark mode each value is
 * multiplied by 0.7 per channel so the chip stays legible on a dark
 * surface without resorting to a brighter hue.
 */
private val GoodColor = Color(0xFF84DE02)
private val FairColor = Color(0xFFFFA500)
private val PoorColor = Color.Red

internal data class RatingColors(
    val container: Color,
    val onContainer: Color,
)

@Composable
internal fun MetricRating.colors(): RatingColors? = when (this) {
    MetricRating.Good -> RatingColors(
        container = if (isInDarkTheme()) GoodColor.dim(0.7f) else GoodColor,
        onContainer = Color.Black,
    )
    MetricRating.Fair -> RatingColors(
        container = if (isInDarkTheme()) FairColor.dim(0.7f) else FairColor,
        onContainer = Color.Black,
    )
    MetricRating.Poor -> RatingColors(
        container = if (isInDarkTheme()) PoorColor.dim(0.7f) else PoorColor,
        onContainer = if (isInDarkTheme()) Color.Black else Color.White,
    )
    MetricRating.Unrated -> null
}

private fun Color.dim(factor: Float): Color = Color(
    red = (red * factor).coerceIn(0f, 1f),
    green = (green * factor).coerceIn(0f, 1f),
    blue = (blue * factor).coerceIn(0f, 1f),
    alpha = alpha,
)

// ----- Idle latency -------------------------------------------------------
// 300 ms / 700 ms bands. Apple runs idle probes against Apple's own CDN
// with no cross-traffic, so anything under 300 ms is comfortably real-time.
// 300..700 is acceptable; over 700 ms is unresponsive for interactive use.
private const val IDLE_LATENCY_GOOD_MAX_MS = 300L
private const val IDLE_LATENCY_FAIR_MAX_MS = 700L

internal fun rateIdleLatency(ms: Int): MetricRating = when {
    ms <= 0 -> MetricRating.Unrated
    ms < IDLE_LATENCY_GOOD_MAX_MS -> MetricRating.Good
    ms <= IDLE_LATENCY_FAIR_MAX_MS -> MetricRating.Fair
    else -> MetricRating.Poor
}

// ----- Throughput (bits per second) --------------------------------------
// Download: >150 Mbps = Good, 50..150 = Fair, <50 = Poor.
// Upload: >100 Mbps = Good, 30..100 = Fair, <30 = Poor.
private const val DOWNLOAD_GOOD_BPS = 150L * 1_000_000L
private const val DOWNLOAD_FAIR_BPS = 50L * 1_000_000L
private const val UPLOAD_GOOD_BPS = 100L * 1_000_000L
private const val UPLOAD_FAIR_BPS = 30L * 1_000_000L

internal fun rateDownloadCapacity(bitsPerSecond: Long): MetricRating = when {
    bitsPerSecond <= 0L -> MetricRating.Unrated
    bitsPerSecond > DOWNLOAD_GOOD_BPS -> MetricRating.Good
    bitsPerSecond >= DOWNLOAD_FAIR_BPS -> MetricRating.Fair
    else -> MetricRating.Poor
}

internal fun rateUploadCapacity(bitsPerSecond: Long): MetricRating = when {
    bitsPerSecond <= 0L -> MetricRating.Unrated
    bitsPerSecond > UPLOAD_GOOD_BPS -> MetricRating.Good
    bitsPerSecond >= UPLOAD_FAIR_BPS -> MetricRating.Fair
    else -> MetricRating.Poor
}

// ----- Responsiveness (round-trips per minute) ---------------------------
// Apple's NQE picks "responsive" around 500 RPM; below 200 it usually labels
// the link as unsuitable for interactive workloads.
private const val RPM_GOOD_MIN = 500
private const val RPM_FAIR_MIN = 200

internal fun rateRpm(rpm: Int, hasMeasurement: Boolean): MetricRating = when {
    !hasMeasurement -> MetricRating.Unrated
    rpm >= RPM_GOOD_MIN -> MetricRating.Good
    rpm >= RPM_FAIR_MIN -> MetricRating.Fair
    else -> MetricRating.Poor
}

// ----- Elapsed time ------------------------------------------------------
// Always unrated: total runtime is an artefact of the test, not a property
// of the link.
internal fun rateElapsedTime(@Suppress("UNUSED_PARAMETER") ms: Long): MetricRating =
    MetricRating.Unrated
