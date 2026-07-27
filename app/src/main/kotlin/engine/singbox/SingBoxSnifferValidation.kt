// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package engine.singbox

import java.math.BigDecimal
import java.math.BigInteger

internal fun List<String>.sanitizedSnifferProtocols(): List<String> =
    map { value -> value.trim().lowercase() }
        .distinct()
        .filter(SingBoxSnifferProtocols::contains)
        .ifEmpty { DefaultSingBoxSnifferProtocols }

internal fun String.sanitizedSnifferTimeout(): String =
    trim().takeIf(::isNonNegativeSingBoxDuration) ?: DefaultSingBoxSnifferTimeout

internal fun isSingBoxDuration(value: String): Boolean =
    singBoxDurationNanosOrNull(value) != null

internal fun isNonNegativeSingBoxDuration(value: String): Boolean =
    singBoxDurationNanosOrNull(value)?.let { duration -> duration.signum() >= 0 } == true

internal fun isSingBoxDurationNotGreaterThan(
    value: String,
    upperBound: String,
): Boolean {
    val duration = singBoxDurationNanosOrNull(value) ?: return false
    val maximum = singBoxDurationNanosOrNull(upperBound) ?: return false
    return duration <= maximum
}

internal fun singBoxDurationNanosOrNull(value: String): BigInteger? {
    val normalized = value.trim()
    if (!SingBoxDurationRegex.matches(normalized)) {
        return null
    }

    val negative = normalized.startsWith('-')
    val unsigned = normalized.removePrefix("-").removePrefix("+")
    if (unsigned == "0") return BigInteger.ZERO

    val maxNanos = if (negative) NegativeDurationMagnitudeLimit else PositiveDurationLimit
    var totalNanos = BigInteger.ZERO
    DurationPartRegex.findAll(unsigned).forEach { match ->
        val amount = BigDecimal(match.groupValues[1])
        val unitNanos = DurationUnitNanos.getValue(match.groupValues[2])
        totalNanos += amount.multiply(unitNanos).toBigInteger()
        if (totalNanos > maxNanos) return null
    }
    return if (negative) totalNanos.negate() else totalNanos
}

private val SingBoxDurationRegex = Regex(
    """^[+-]?(?:0|(?:(?:\d+(?:\.\d*)?|\.\d+)(?:ns|us|µs|μs|ms|s|m|h|d))+)$""",
)
private val DurationPartRegex = Regex(
    """(\d+(?:\.\d*)?|\.\d+)(ns|us|µs|μs|ms|s|m|h|d)""",
)
private val DurationUnitNanos = mapOf(
    "ns" to BigDecimal.ONE,
    "us" to BigDecimal("1000"),
    "µs" to BigDecimal("1000"),
    "μs" to BigDecimal("1000"),
    "ms" to BigDecimal("1000000"),
    "s" to BigDecimal("1000000000"),
    "m" to BigDecimal("60000000000"),
    "h" to BigDecimal("3600000000000"),
    "d" to BigDecimal("86400000000000"),
)
private val PositiveDurationLimit = BigInteger.valueOf(Long.MAX_VALUE)
private val NegativeDurationMagnitudeLimit = PositiveDurationLimit + BigInteger.ONE
