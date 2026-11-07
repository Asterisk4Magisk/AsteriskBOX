// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package utils

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

// Field readers for sing-box JSON. The suffix names the result for a missing, null or
// non-primitive field, so lenient and strict readers cannot be mistaken for each other.

internal fun JsonObject.stringOrNull(name: String): String? =
    (get(name) as? JsonPrimitive)?.contentOrNull

internal fun JsonObject.stringOrEmpty(name: String): String = stringOrNull(name).orEmpty()

internal fun JsonObject.hasNonBlankString(name: String): Boolean = stringOrNull(name)?.isNotBlank() == true

internal fun JsonObject.intOrNull(name: String): Int? = (get(name) as? JsonPrimitive)?.intOrNull

internal fun JsonObject.intOrZero(name: String): Int = intOrNull(name) ?: 0

/** Strict: only `true` or `false`. */
internal fun JsonObject.booleanOrNull(name: String): Boolean? = stringOrNull(name)?.toBooleanStrictOrNull()

/** Lenient: a JSON boolean, `"true"` in any case, or `"1"`. */
internal fun JsonObject.booleanOrFalse(name: String): Boolean {
    val primitive = get(name) as? JsonPrimitive ?: return false
    return primitive.booleanOrNull ?: primitive.contentOrNull.let { value ->
        value == "1" || value.equals("true", ignoreCase = true)
    }
}

internal fun JsonObject.objectValue(name: String): JsonObject? = get(name) as? JsonObject

/** Nonblank strings from an array, or a single nonblank string as a one-item list. */
internal fun JsonObject.stringListValue(name: String): List<String> = when (val value = get(name)) {
    is JsonArray -> value.mapNotNull { item ->
        (item as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)
    }
    is JsonPrimitive -> value.contentOrNull?.takeIf(String::isNotBlank)?.let(::listOf).orEmpty()
    else -> emptyList()
}
