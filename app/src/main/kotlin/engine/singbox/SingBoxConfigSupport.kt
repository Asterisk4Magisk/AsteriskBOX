// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package engine.singbox

import android.content.Context
import features.logs.androidCoreLogErrorFile
import java.io.File

internal data class SingBoxCoreLogPaths(
    val errorLogPath: String,
)

internal object SingBoxTags {
    const val DNS_OUT = "dns-out"
}

internal fun Context.prepareSingBoxCoreLogPaths(): SingBoxCoreLogPaths {
    return SingBoxCoreLogPaths(
        errorLogPath = androidCoreLogErrorFile().absolutePath,
    )
}

internal fun SingBoxCoreLogPaths.logDirectoryPath(): String {
    return File(errorLogPath).parentFile?.absolutePath
        ?: error("SingBox log directory is unavailable")
}
