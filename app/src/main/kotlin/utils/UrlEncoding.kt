// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package utils

import java.net.URLDecoder

internal fun String.decodeUrlComponentPreservingPlus(): String {
    return runCatching {
        decodeUrlComponentPreservingPlusStrict()
    }.getOrElse { this }
}

/** Decode a URL component once; malformed percent escapes remain an error to strict importers. */
internal fun String.decodeUrlComponentPreservingPlusStrict(): String =
    URLDecoder.decode(replace("+", "%2B"), Charsets.UTF_8.name())
