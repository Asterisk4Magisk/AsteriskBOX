// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package engine.singbox.config

import com.dokar.quickjs.quickJs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

internal data class SingBoxScriptLog(val level: String, val message: String)

internal data class SingBoxScriptResult(
    val logs: List<SingBoxScriptLog> = emptyList(),
    val output: JsonObject? = null,
    val error: String? = null,
) {
    val success: Boolean get() = error == null && output != null
}

internal fun applySingBoxConfigOverride(
    root: JsonObject,
    enabled: Boolean,
    script: String,
): JsonObject {
    if (!enabled || script.isBlank()) return root
    val result = runSingBoxConfigOverride(root, script)
    // Script errors can contain config secrets. Details are only shown in explicit debug output.
    check(result.success) { "Configuration override failed; open override script debug for details" }
    return checkNotNull(result.output)
}

internal fun runSingBoxConfigOverride(root: JsonObject, script: String): SingBoxScriptResult {
    if (script.isBlank()) return SingBoxScriptResult(output = root)
    return try {
        val raw = runBlocking(Dispatchers.Default) {
            quickJs {
                memoryLimit = 64L * 1024 * 1024
                maxStackSize = 1024L * 1024
                evaluationTimeoutMillis = 3_000
                evaluate<String>(
                    code = buildSingBoxOverrideScript(script, root),
                    filename = "asteriskbox-config-override.js",
                )
            }
        }
        val result = Json.parseToJsonElement(raw) as JsonObject
        val logs = (result["logs"] as? JsonArray).orEmpty().mapNotNull { item ->
            val log = item as? JsonObject ?: return@mapNotNull null
            SingBoxScriptLog(log.string("level").orEmpty(), log.string("message").orEmpty())
        }
        val error = result.string("error")
        val output = result["config"] as? JsonObject
        SingBoxScriptResult(
            logs = logs,
            output = output,
            error = error ?: if (output == null) "Override script must return a config object" else null,
        )
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        SingBoxScriptResult(error = error.message ?: "Override script failed")
    }
}

internal fun debugSingBoxConfigOverride(root: JsonObject, script: String): SingBoxScriptResult {
    val result = runSingBoxConfigOverride(root, script)
    if (!result.success) return result
    return try {
        SingBoxConfigChecker.check(encodeSingBoxJson(checkNotNull(result.output)))
        result
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        result.copy(error = error.message ?: "Configuration validation failed")
    }
}

private fun JsonObject.string(key: String): String? = (get(key) as? JsonPrimitive)?.contentOrNull

internal fun buildSingBoxOverrideScript(script: String, root: JsonObject): String = """
    (function() {
      const logs = [];
      const stringify = JSON.stringify;
      function format(value) {
        if (typeof value === 'string') return value;
        try { return stringify(value) ?? String(value); } catch (_) { return String(value); }
      }
      function record(level, args) {
        if (logs.length < 500) logs.push({level: level, message: Array.from(args).map(format).join(' ').slice(0, 8192)});
      }
      const console = {
        log: function() { record('log', arguments); },
        info: function() { record('info', arguments); },
        warn: function() { record('warn', arguments); },
        error: function() { record('error', arguments); },
        debug: function() { record('debug', arguments); }
      };
      try {
        const userMain = (function() {
          $script
          ;if (typeof main !== 'function') throw new Error('Override script must define main(config)');
          return main;
        })();
        const input = JSON.parse(${JsonPrimitive(root.toString())});
        const result = userMain(input);
        if (result && typeof result.then === 'function') throw new Error('main(config) must be synchronous');
        const config = result == null ? input : result;
        if (typeof config !== 'object' || config === null || Array.isArray(config)) {
          throw new Error('Override script must return a config object');
        }
        return stringify({logs: logs, config: config});
      } catch (error) {
        return stringify({logs: logs, error: String(error) + (error && error.stack ? '\n' + error.stack : '')});
      }
    })()
""".trimIndent()
