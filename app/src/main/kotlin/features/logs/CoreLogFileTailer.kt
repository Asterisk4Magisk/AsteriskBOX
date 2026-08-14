// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.logs

import android.os.FileObserver
import android.system.Os
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.milliseconds

internal data class CoreLogFile(
    val path: String,
    val defaultLevel: String,
)

internal class CoreLogFileTailer(
    private val logFiles: List<CoreLogFile>,
    private val repository: CoreLogRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun start() {
        logFiles
            .filter { logFile -> logFile.path.isNotBlank() }
            .forEach { logFile ->
                scope.launch {
                    tail(logFile)
                }
            }
    }

    fun stop() {
        scope.cancel()
    }

    private suspend fun tail(logFile: CoreLogFile) {
        val file = File(logFile.path)
        val directory = file.parentFile ?: return
        directory.mkdirs()
        var position = runCatching { file.length() }.getOrDefault(0L)
        var failureLogged = false
        var reader: RandomAccessFile? = null
        var fileIdentity: Long? = null
        val signals = Channel<Unit>(Channel.CONFLATED)
        val reopenRequested = AtomicBoolean(false)
        @Suppress("DEPRECATION")
        val observer = object : FileObserver(directory.absolutePath, FileEventMask) {
            override fun onEvent(event: Int, path: String?) {
                if (path != file.name) return
                if (event and ReopenEventMask != 0) {
                    reopenRequested.set(true)
                }
                signals.trySend(Unit)
            }
        }

        try {
            observer.startWatching()
            signals.trySend(Unit)
            while (currentCoroutineContext().isActive) {
                withTimeoutOrNull(TailFallbackIntervalMillis.milliseconds) {
                    signals.receive()
                }

                if (!file.exists()) {
                    reader?.close()
                    reader = null
                    fileIdentity = null
                    continue
                }
                val currentFileIdentity = runCatching { Os.stat(file.absolutePath).st_ino }.getOrNull()
                if (reader != null && fileIdentity != currentFileIdentity) {
                    reader.close()
                    reader = null
                    position = 0L
                }
                if (reopenRequested.getAndSet(false)) {
                    reader?.close()
                    reader = null
                    position = 0L
                }

                runCatching {
                    val activeReader = reader ?: RandomAccessFile(file, "r").also { opened ->
                        reader = opened
                        fileIdentity = currentFileIdentity
                    }
                    if (position > activeReader.length()) {
                        position = 0L
                    }
                    activeReader.seek(position)

                    var line = activeReader.readUtf8Line()
                    while (line != null) {
                        repository.appendParsedCoreLogLine(line, logFile.defaultLevel)
                        line = activeReader.readUtf8Line()
                    }
                    position = activeReader.filePointer
                }.onSuccess {
                    failureLogged = false
                }.onFailure { error ->
                    runCatching { reader?.close() }
                    reader = null
                    fileIdentity = null
                    if (!failureLogged) {
                        AndroidAppLogger.warn(LogTag, "Failed to tail SingBox log file: ${file.absolutePath}", error)
                        failureLogged = true
                    }
                }
            }
        } finally {
            observer.stopWatching()
            signals.close()
            runCatching { reader?.close() }
        }
    }

    private fun RandomAccessFile.readUtf8Line(): String? {
        return readLine()?.let { line ->
            String(line.toByteArray(Charsets.ISO_8859_1), Charsets.UTF_8)
        }
    }

    private companion object {
        private const val LogTag = "CoreLogFileTailer"
        private const val TailFallbackIntervalMillis = 5_000L
        private const val ReopenEventMask = FileObserver.CREATE or FileObserver.MOVED_TO or
            FileObserver.DELETE or FileObserver.MOVED_FROM
        private const val FileEventMask = FileObserver.CLOSE_WRITE or FileObserver.MODIFY or ReopenEventMask
    }
}

internal data class ParsedCoreLogLine(
    val time: String?,
    val level: String,
    val message: String,
)

private val SingBoxLogLineRegex = Regex("""^(\d{4}[-/]\d{2}[-/]\d{2}\s+\d{2}:\d{2}:\d{2})\s+\[([A-Za-z]+)]\s*(.*)$""")
private val SingBoxLogLineWithoutLevelRegex = Regex("""^(\d{4}[-/]\d{2}[-/]\d{2}\s+\d{2}:\d{2}:\d{2})\s+(.*)$""")
private val SingBoxRawLogLineRegex = Regex("""^(TRACE|DEBUG|INFO|WARN|ERROR|FATAL|PANIC)\[\d+]\s*(.*)$""")

internal fun CoreLogRepository.appendParsedCoreLogLine(line: String, defaultLevel: String) {
    val parsedLine = parseCoreLogLine(line, defaultLevel) ?: return
    if (parsedLine.time == null) {
        append(level = parsedLine.level, message = parsedLine.message)
    } else {
        append(level = parsedLine.level, message = parsedLine.message, time = parsedLine.time)
    }
}

internal fun parseCoreLogLine(line: String, defaultLevel: String): ParsedCoreLogLine? {
    val trimmedLine = line.trim()
    if (trimmedLine.isEmpty()) {
        return null
    }

    parseAsteriskdJsonLogLine(trimmedLine)?.let { parsed -> return parsed }

    SingBoxLogLineRegex.matchEntire(trimmedLine)?.let { match ->
        val (time, level, message) = match.destructured
        return ParsedCoreLogLine(
            time = time.replace('/', '-'),
            level = level,
            message = message,
        )
    }

    SingBoxLogLineWithoutLevelRegex.matchEntire(trimmedLine)?.let { match ->
        val (time, message) = match.destructured
        return ParsedCoreLogLine(
            time = time.replace('/', '-'),
            level = defaultLevel,
            message = message,
        )
    }

    SingBoxRawLogLineRegex.matchEntire(trimmedLine)?.let { match ->
        return ParsedCoreLogLine(
            time = null,
            level = match.groupValues[1].lowercase(),
            message = trimmedLine,
        )
    }

    return ParsedCoreLogLine(
        time = null,
        level = defaultLevel,
        message = trimmedLine,
    )
}

private fun parseAsteriskdJsonLogLine(line: String): ParsedCoreLogLine? {
    if (!line.startsWith('{')) return null
    return runCatching {
        val value = LogJson.parseToJsonElement(line) as? JsonObject ?: return@runCatching null
        if (value.keys != AsteriskdLogKeys) return@runCatching null
        val timestamp = value.getValue("timestamp").jsonPrimitive.content
        val level = value.getValue("level").jsonPrimitive.content
        value.getValue("component").jsonPrimitive.content
        value.getValue("event").jsonPrimitive.content
        value.getValue("stream").jsonPrimitive.contentOrNull
        val message = value.getValue("message").jsonPrimitive.content
        value.getValue("truncated").jsonPrimitive.booleanOrNull ?: return@runCatching null
        ParsedCoreLogLine(time = timestamp, level = level, message = message)
    }.getOrNull()
}

private val AsteriskdLogKeys = setOf(
    "timestamp",
    "level",
    "component",
    "event",
    "stream",
    "message",
    "truncated",
)

private val LogJson = Json {
    isLenient = false
}
