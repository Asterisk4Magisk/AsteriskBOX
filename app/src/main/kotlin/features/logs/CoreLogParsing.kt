// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.logs

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

internal data class CoreLogFile(
    val path: String,
    val defaultLevel: String,
)

internal data class ParsedCoreLogLine(
    val time: String?,
    val level: String,
    val message: String,
)

private val SingBoxLogLineRegex = Regex("""^(\d{4}[-/]\d{2}[-/]\d{2}\s+\d{2}:\d{2}:\d{2})\s+\[([A-Za-z]+)]\s*(.*)$""")
private val SingBoxLogLineWithoutLevelRegex = Regex("""^(\d{4}[-/]\d{2}[-/]\d{2}\s+\d{2}:\d{2}:\d{2})\s+(.*)$""")
private val SingBoxRawLogLineRegex = Regex("""^(TRACE|DEBUG|INFO|WARN|ERROR|FATAL|PANIC)\[\d+]\s*(.*)$""")

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
