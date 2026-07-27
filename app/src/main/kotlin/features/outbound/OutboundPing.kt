// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.outbound

import app.OutboundState
import engine.singbox.config.SingBoxJson
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlin.math.roundToLong

internal fun OutboundState.pingHostOrNull(): String? {
    val outbound = runCatching {
        SingBoxJson.parseToJsonElement(json) as? JsonObject
    }.getOrNull() ?: return null
    return (outbound["server"] as? JsonPrimitive)
        ?.contentOrNull
        ?.trim()
        ?.removeSurrounding("[", "]")
        ?.takeIf(String::isNotEmpty)
}

internal fun measureBestPingMillis(
    attempts: Int,
    ping: () -> Long,
): Long {
    var bestMillis = FailedPingMillis
    repeat(attempts.coerceAtLeast(0)) {
        val elapsedMillis = ping()
        if (elapsedMillis >= 0L && (bestMillis < 0L || elapsedMillis < bestMillis)) {
            bestMillis = elapsedMillis
        }
    }
    return bestMillis
}

internal fun parsePingMillis(output: String): Long? {
    return PingTimeRegex.find(output)
        ?.groupValues
        ?.getOrNull(1)
        ?.replace(',', '.')
        ?.toDoubleOrNull()
        ?.roundToLong()
}

internal fun buildPingCommand(host: String): List<String> = listOf(
    if (':' in host) Ping6Executable else PingExecutable,
    "-c",
    "1",
    "-W",
    PingTimeoutSeconds.toString(),
    host,
)

internal suspend fun pingOrFailure(ping: suspend () -> Long): Long {
    return try {
        ping()
    } catch (error: CancellationException) {
        throw error
    } catch (_: Throwable) {
        FailedPingMillis
    }
}

internal class AndroidOutboundPinger {
    suspend fun ping(outbound: OutboundState): Long {
        val host = outbound.pingHostOrNull() ?: return FailedPingMillis
        var bestMillis = FailedPingMillis
        repeat(PingAttempts) {
            currentCoroutineContext().ensureActive()
            val elapsedMillis = pingOnce(host)
            if (elapsedMillis >= 0L && (bestMillis < 0L || elapsedMillis < bestMillis)) {
                bestMillis = elapsedMillis
            }
        }
        return bestMillis
    }

    private suspend fun pingOnce(host: String): Long {
        return withTimeoutOrNull(PingProcessTimeoutMillis) {
            withContext(Dispatchers.IO) {
                var process: Process? = null
                try {
                    val startedAtNanos = System.nanoTime()
                    val activeProcess = ProcessBuilder(buildPingCommand(host))
                        .redirectErrorStream(true)
                        .start()
                        .also { startedProcess -> process = startedProcess }
                    runInterruptible { activeProcess.waitFor() }
                    val output = activeProcess.inputStream.bufferedReader().use { reader ->
                        reader.readText()
                    }
                    if (activeProcess.exitValue() == 0) {
                        parsePingMillis(output)
                            ?: TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos)
                    } else {
                        FailedPingMillis
                    }
                } finally {
                    process?.destroy()
                }
            }
        } ?: FailedPingMillis
    }
}

internal const val FailedPingMillis = -1L
private const val PingAttempts = 2
private const val PingTimeoutSeconds = 3L
private const val PingProcessTimeoutMillis = (PingTimeoutSeconds + 1L) * 1_000L
private const val PingExecutable = "/system/bin/ping"
private const val Ping6Executable = "/system/bin/ping6"
private val PingTimeRegex = Regex("""time[=<]\s*(\d+(?:[.,]\d+)?)\s*ms""", RegexOption.IGNORE_CASE)
