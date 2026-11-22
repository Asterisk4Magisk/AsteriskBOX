// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.logs

import android.content.Context
import engine.singbox.SingBoxCoreLogPaths
import engine.singbox.clearCoreLogFilesAsApp
import engine.singbox.prepareSingBoxCoreLogPaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal suspend fun Context.clearCoreLogFile(logFile: SingBoxLogFile) {
    val logPath = applicationContext.prepareSingBoxCoreLogPaths().pathOf(logFile)
    if (logPath.isBlank()) {
        return
    }

    withContext(Dispatchers.IO) {
        clearCoreLogFilesAsApp(
            logPaths = listOf(logPath),
            logTag = LogTag,
        )
    }
}

private fun SingBoxCoreLogPaths.pathOf(logFile: SingBoxLogFile): String {
    return when (logFile) {
        SingBoxLogFile.Error -> errorLogPath
    }
}

internal enum class SingBoxLogFile {
    Error,
}

private const val LogTag = "CoreLogClear"
