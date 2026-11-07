// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package utils

internal fun Iterable<String>.toTrimmedNonEmptyList(): List<String> {
    return mapNotNull { value -> value.trim().takeIf(String::isNotEmpty) }
}

internal fun Iterable<String>.toTrimmedNonEmptyDistinctList(): List<String> {
    return toTrimmedNonEmptyList().distinct()
}

internal fun String?.toCsvValues(): List<String> {
    return orEmpty()
        .split(',')
        .toTrimmedNonEmptyList()
}

internal fun String.toIntInRangeOrNull(range: IntRange): Int? {
    return trim().toIntOrNull()?.takeIf { value -> value in range }
}

internal fun String.toIntInRangeOrDefault(range: IntRange, default: Int): Int {
    return toIntInRangeOrNull(range) ?: default
}

internal fun String.requireIntIn(label: String, minimum: Int, maximum: Int): Int {
    val parsed = toIntOrNull()
    require(parsed != null && parsed in minimum..maximum) { "$label is invalid" }
    return parsed
}

/** A non-negative whole number of seconds, as a sing-box duration such as `30s`. */
internal fun String.requireSecondsDuration(label: String): String {
    val seconds = toLongOrNull()
    require(seconds != null && seconds >= 0) { "$label is invalid" }
    return "${seconds}s"
}
