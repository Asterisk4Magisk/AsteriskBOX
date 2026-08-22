// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package engine.singbox

import features.logs.AndroidAppLogger
import java.io.File

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
