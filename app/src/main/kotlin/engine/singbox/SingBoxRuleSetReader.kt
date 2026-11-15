// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package engine.singbox

import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext

/** Decode with the bundled core, independently of any VPN or ROOT session. */
internal suspend fun readSingBoxBinaryRuleSet(
    coreFile: File,
    sourceFile: File,
    cacheDirectory: File,
): String = withContext(Dispatchers.IO) {
    if (!sourceFile.isFile) throw FileNotFoundException(sourceFile.name)
    val output = File.createTempFile("srs-preview-", ".json", cacheDirectory)
    val diagnostics = File(output.path + ".log")
    var process: Process? = null
    try {
        val activeProcess = ProcessBuilder(
            coreFile.absolutePath,
            "--disable-color", "rule-set", "decompile",
            "--output", output.absolutePath,
            sourceFile.absolutePath,
        )
            .redirectErrorStream(true)
            .redirectOutput(diagnostics)
            .start()
        process = activeProcess
        val exitCode = runInterruptible { activeProcess.waitFor() }
        if (exitCode != 0) {
            throw IOException(diagnostics.readText().trim().ifBlank { "SRS decoding failed (exit code $exitCode)" })
        }
        output.readText()
    } finally {
        process?.destroy()
        process?.waitFor()
        output.delete()
        diagnostics.delete()
    }
}
