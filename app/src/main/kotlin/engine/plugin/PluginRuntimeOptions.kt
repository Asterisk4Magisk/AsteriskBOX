// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package engine.plugin

import android.content.Context
import app.R
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.File

/** Where the core writes plugin config files and the protect socket. */
internal fun pluginWorkingDir(context: Context): File =
    File(context.noBackupFilesDir, "plugin").apply { mkdirs() }

internal fun pluginProtectSocketPath(context: Context): String =
    File(pluginWorkingDir(context), "protect.sock").absolutePath

/** The core resolves this file next to its config (`-c`) in ROOT mode and the
 * service reads it before starting in VPN mode, so both modes share one path. */
internal const val PluginEnvelopeFileName = "plugin.json"

private val PluginOptionsJson = Json {
    prettyPrint = false
}

/**
 * Builds the JSON envelope the core decodes into `PluginProcessSpec`s.
 *
 * Every plugin must be installed: a missing binary fails the start instead of
 * leaving the core with an outbound that can never connect.
 */
internal fun buildPluginOptionsJson(
    context: Context,
    plans: List<PluginOutboundPlan>,
    protectPath: String,
    verifyPeer: Boolean,
): String {
    if (plans.isEmpty()) {
        return ""
    }
    val installed = PluginRegistry.installedPlugins(context)
    val envelope = buildJsonObject {
        put("workingDir", pluginWorkingDir(context).absolutePath)
        put("protectPath", protectPath)
        put("verifyPeer", verifyPeer)
        putJsonArray("plugins") {
            plans.forEach { plan ->
                val plugin = resolveForPlan(context, installed, plan.requirement)
                addJsonObject {
                    put("name", plan.requirement.pluginId)
                    putJsonArray("command") {
                        add(plugin.executable.absolutePath)
                        plan.commandArguments.forEach { argument -> add(argument) }
                    }
                    putJsonObject("environment") {
                        plan.environment.forEach { (key, value) -> put(key, value) }
                    }
                    putJsonArray("files") {
                        plan.files.forEach { file ->
                            require(
                                file.name.isNotEmpty() &&
                                    file.name != "." &&
                                    file.name != ".." &&
                                    file.name.none { character -> character == '/' || character == '\\' },
                            ) { "Invalid plugin file name: ${file.name}" }
                            addJsonObject {
                                put("name", file.name)
                                put("content", file.content)
                            }
                        }
                    }
                }
            }
        }
    }
    return PluginOptionsJson.encodeToString(JsonObject.serializer(), envelope)
}

/** Writes (or removes) the envelope beside the core config. */
internal fun publishPluginEnvelope(configDirectory: File, content: String) {
    val envelope = File(configDirectory, PluginEnvelopeFileName)
    if (content.isEmpty()) {
        envelope.delete()
        return
    }
    configDirectory.mkdirs()
    val temporary = File(configDirectory, "$PluginEnvelopeFileName.tmp")
    temporary.writeText(content)
    check(temporary.setReadable(false, false) && temporary.setReadable(true, true))
    check(temporary.setWritable(false, false) && temporary.setWritable(true, true))
    if (!temporary.renameTo(envelope)) {
        temporary.delete()
        error("Cannot publish $PluginEnvelopeFileName")
    }
}

private fun resolveForPlan(
    context: Context,
    installed: List<InstalledPlugin>,
    requirement: PluginRequirement,
): InstalledPlugin {
    val candidates = installed.filter { plugin -> plugin.pluginId == requirement.pluginId }
    if (candidates.isEmpty()) {
        throw PluginNotFoundException(
            pluginId = requirement.pluginId,
            message = context.getString(
                app.R.string.plugin_missing_message,
                requirement.displayName,
                requirement.pluginId,
            ),
        )
    }
    return candidates.firstOrNull { plugin -> plugin.publisher == PluginPublisher.HUSI.displayName }
        ?: candidates.first()
}
