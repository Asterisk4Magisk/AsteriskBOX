// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.logs

internal enum class LogLevelFilter(val rawLevel: String?) {
    All(null),
    Trace("trace"),
    Debug("debug"),
    Info("info"),
    Warning("warning"),
    Warn("warn"),
    Error("error"),
    Fatal("fatal"),
    Panic("panic"),
}

internal val CoreLogLevelFilters = listOf(
    LogLevelFilter.All,
    LogLevelFilter.Trace,
    LogLevelFilter.Debug,
    LogLevelFilter.Info,
    LogLevelFilter.Warn,
    LogLevelFilter.Error,
    LogLevelFilter.Fatal,
    LogLevelFilter.Panic,
)

internal val LogcatLogLevelFilters = listOf(
    LogLevelFilter.All,
    LogLevelFilter.Debug,
    LogLevelFilter.Info,
    LogLevelFilter.Warning,
    LogLevelFilter.Error,
)

internal fun reduceLogEntries(
    entries: List<CoreLogEntry>,
    query: String,
    filter: LogLevelFilter = LogLevelFilter.All,
): List<CoreLogEntry> {
    val normalizedQuery = query.trim()
    return entries.filter { entry ->
        val matchesQuery = normalizedQuery.isEmpty() || listOf(
            entry.time,
            entry.level,
            entry.message,
        ).any { value -> value.contains(normalizedQuery, ignoreCase = true) }
        val level = entry.level.trim().lowercase()
        val matchesFilter = when (filter) {
            LogLevelFilter.All -> true
            LogLevelFilter.Warning -> level == "warning" || level == "warn"
            else -> level == filter.rawLevel
        }
        matchesQuery && matchesFilter
    }
}

internal fun logEntriesForExport(entries: List<CoreLogEntry>): List<CoreLogEntry> = entries
