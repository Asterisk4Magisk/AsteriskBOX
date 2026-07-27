// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package engine.singbox

import features.logs.AndroidAppLogger
import features.logs.AndroidCoreLogRepository
import features.logs.CoreLogFile
import features.logs.CoreLogFileTailer
import java.io.File

internal fun SingBoxCoreLogPaths.startCoreLogTailers(): List<CoreLogFileTailer> {
    return buildList {
        add(
            CoreLogFileTailer(
                logFiles = listOf(errorLogFile()),
                repository = AndroidCoreLogRepository,
            ),
        )
    }.onEach { tailer -> tailer.start() }
}

internal fun SingBoxCoreLogPaths.clearCoreLogs(logTag: String) {
    AndroidCoreLogRepository.clear()
    clearCoreLogFilesAsApp(
        logPaths = logFilePaths(),
        logTag = logTag,
    )
}

internal fun SingBoxCoreLogPaths.logFilePaths(): List<String> {
    return listOf(errorLogPath).filter(String::isNotBlank)
}

internal fun clearCoreLogFilesAsApp(logPaths: List<String>, logTag: String) {
    logPaths
        .filter(String::isNotBlank)
        .forEach { logPath ->
            runCatching {
                File(logPath).apply {
                    parentFile?.mkdirs()
                    writeText("")
                }
            }.onFailure { error ->
                AndroidAppLogger.warn(logTag, "Failed to clear SingBox log file: $logPath", error)
            }
        }
}

private fun SingBoxCoreLogPaths.errorLogFile(): CoreLogFile {
    return CoreLogFile(path = errorLogPath, defaultLevel = "error")
}
