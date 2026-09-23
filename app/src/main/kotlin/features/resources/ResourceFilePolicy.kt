// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.resources

import app.ResourceFileKind
import app.customResourceFileNameOrNull
import java.util.Locale

internal fun isSupportedCustomResourceName(name: String): Boolean {
    if (customResourceFileNameOrNull(name) != name) return false
    return name.substringAfterLast('.', "").lowercase(Locale.ROOT) in SupportedResourceExtensions
}

internal fun isSupportedResourceName(name: String): Boolean =
    ResourceFileKind.entries.any { it != ResourceFileKind.SingBoxCore && it.fileName == name } ||
        isSupportedCustomResourceName(name)

private val SupportedResourceExtensions = setOf("srs", "json")
