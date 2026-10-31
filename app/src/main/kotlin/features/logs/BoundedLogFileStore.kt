// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.logs

import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel

/**
 * A bounded log file shared by every process of the app.
 *
 * The UI process and the VPN service process both append to the same files, so the in-process
 * lock is not enough: `trimLocked` reads and then rewrites the whole file, which would drop the
 * lines the other process appended in between. A file lock (POSIX record lock, dropped by the
 * kernel when a process dies) serializes appends, trims and clears across processes.
 */
internal class BoundedLogFileStore(
    private val file: () -> File?,
    private val logTag: String,
    private val maxLines: Int = CoreLogMaxEntries,
    private val trimExtraLines: Int = LogFileTrimExtraLines,
    private val onFailure: (message: String, error: Throwable) -> Unit,
) {
    private val lock = Any()
    private var lineCount = 0
    private var lockChannel: FileChannel? = null

    fun appendLine(line: String) {
        val logFile = file() ?: return
        synchronized(lock) {
            withProcessLock(logFile) {
                runCatching {
                    logFile.parentFile?.mkdirs()
                    logFile.appendText("$line\n")
                    lineCount += 1
                    if (lineCount > maxLines + trimExtraLines) {
                        trimLocked(logFile)
                    }
                }.onFailure { error ->
                    reportFailure("append to", logFile, error)
                }
            }
        }
    }

    fun clear() {
        val logFile = file() ?: return
        synchronized(lock) {
            withProcessLock(logFile) {
                runCatching {
                    logFile.parentFile?.mkdirs()
                    logFile.writeText("")
                    lineCount = 0
                }.onFailure { error ->
                    reportFailure("clear", logFile, error)
                }
            }
        }
    }

    fun readLastLines(): List<String> {
        val logFile = file() ?: return emptyList()
        val lines = synchronized(lock) {
            withProcessLock(logFile) {
                readLastLinesLocked(logFile)
            }
        }
        lineCount = lines.size
        return lines
    }

    /**
     * Runs [block] while holding the cross-process lock for [logFile].
     *
     * The lock file is kept next to the log and opened once per store: reopening it per line would
     * add two syscalls to every log record. When the lock cannot be taken the block still runs
     * unlocked — losing a log line is better than losing logging entirely.
     */
    private fun <T> withProcessLock(logFile: File, block: () -> T): T {
        val channel = lockChannel ?: runCatching {
            val lockFile = File(logFile.absoluteFile.parentFile, "${logFile.name}.lock")
            lockFile.parentFile?.mkdirs()
            RandomAccessFile(lockFile, "rw").channel.also { opened -> lockChannel = opened }
        }.getOrNull() ?: return block()
        val acquired = runCatching { channel.lock() }.getOrNull() ?: return block()
        return try {
            block()
        } finally {
            runCatching { acquired.release() }
        }
    }

    private fun trimLocked(logFile: File) {
        val lines = readLastLinesLocked(logFile)
        logFile.writeText(
            lines.joinToString(separator = "\n", postfix = if (lines.isEmpty()) "" else "\n"),
        )
        lineCount = lines.size
    }

    private fun readLastLinesLocked(logFile: File): List<String> {
        if (!logFile.exists() || !logFile.isFile || maxLines <= 0) {
            return emptyList()
        }

        return runCatching {
            val lines = ArrayDeque<String>(maxLines)
            logFile.forEachLine(Charsets.UTF_8) { line ->
                if (lines.size == maxLines) {
                    lines.removeFirst()
                }
                lines.addLast(line)
            }
            lines.toList()
        }.onFailure { error ->
            reportFailure("read", logFile, error)
        }.getOrDefault(emptyList())
    }

    private fun reportFailure(action: String, logFile: File, error: Throwable) {
        onFailure("Failed to $action $logTag file: ${logFile.absolutePath}", error)
    }
}
