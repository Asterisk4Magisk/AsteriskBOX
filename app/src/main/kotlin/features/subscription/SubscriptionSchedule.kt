// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.subscription

import java.math.BigDecimal

internal sealed interface SubscriptionSchedule {
    data object Disabled : SubscriptionSchedule

    data class Enabled(
        val hours: Double,
        val repeatIntervalMillis: Long,
    ) : SubscriptionSchedule

    data object Invalid : SubscriptionSchedule
}

internal fun parseSubscriptionSchedule(value: String): SubscriptionSchedule {
    val normalized = value.trim()
    if (normalized.isEmpty()) return SubscriptionSchedule.Disabled
    val hours = normalized.toBigDecimalOrNull() ?: return SubscriptionSchedule.Invalid
    if (hours < MinimumSubscriptionHours) return SubscriptionSchedule.Invalid
    val milliseconds = hours.multiply(MillisecondsPerHour)
    if (milliseconds > MaximumLongDecimal) return SubscriptionSchedule.Invalid
    val hoursAsDouble = hours.toDouble()
    if (!hoursAsDouble.isFinite()) return SubscriptionSchedule.Invalid
    return SubscriptionSchedule.Enabled(
        hours = hoursAsDouble,
        repeatIntervalMillis = milliseconds.toLong(),
    )
}

private val MinimumSubscriptionHours = BigDecimal("0.25")
private val MillisecondsPerHour = BigDecimal(60L * 60L * 1_000L)
private val MaximumLongDecimal = BigDecimal.valueOf(Long.MAX_VALUE)
