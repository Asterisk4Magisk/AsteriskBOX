// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.subscription

import java.net.URI
import java.util.regex.PatternSyntaxException

internal fun String.isValidManualSubscriptionUrl(): Boolean =
    runCatching {
        val uri = URI(trim())
        (uri.scheme == "http" || uri.scheme == "https") && !uri.host.isNullOrBlank()
    }.getOrDefault(false)

internal fun String.compileSubscriptionAliasFilter(): Regex? {
    if (isBlank()) return null
    return try {
        Regex(this)
    } catch (_: PatternSyntaxException) {
        throw IllegalArgumentException("Invalid subscription alias filter")
    }
}
