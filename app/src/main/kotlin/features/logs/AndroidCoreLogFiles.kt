// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.logs

import android.content.Context
import java.io.File

internal fun Context.androidSingBoxErrorLog(): CoreLogFile {
    return CoreLogFile(path = androidCoreLogErrorFile().absolutePath, defaultLevel = "error")
}

internal fun Context.androidCoreLogErrorFile(): File {
    return File(androidSingBoxLogDirectory(), "error.log")
}

internal fun Context.androidAppLogcatFile(): File {
    return File(androidSingBoxLogDirectory(), "logcat.log")
}

private fun Context.androidSingBoxLogDirectory(): File {
    return File(filesDir, AndroidSingBoxLogDirectoryPath).apply {
        mkdirs()
    }
}

private const val AndroidSingBoxLogDirectoryPath = "sing-box/logs"
