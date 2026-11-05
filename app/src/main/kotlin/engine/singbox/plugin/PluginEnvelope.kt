// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package engine.singbox.plugin

import android.content.Context
import app.modes.isRootRunMode
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import utils.writeAtomically
import java.io.File

/**
 * The envelope the core reads beside its config: the VPN service on every start
 * and reload, the ROOT core through `-c`. A reload therefore always runs the
 * plugin set compiled with the config it reloads.
 */
internal const val PluginEnvelopeFileName = "plugin.json"

/**
 * VPN and ROOT keep separate plugin directories: a ROOT core writes root-owned
 * files there, which the VPN core could not replace.
 */
internal fun Context.pluginTarget(runMode: Int): PluginTarget {
    val root = runMode.isRootRunMode()
    val directory = File(noBackupFilesDir, if (root) "plugin-root" else "plugin-vpn")
    return PluginTarget(
        workingDir = directory.absolutePath,
        protectPath = if (root) null else File(directory, "protect.sock").absolutePath,
    )
}

/**
 * Builds the envelope for [plans]; empty when there are none. A plugin that is
 * not installed fails here, before anything is published, instead of leaving
 * an outbound that can never connect.
 */
internal fun Context.buildPluginEnvelope(plans: List<PluginOutboundPlan>, target: PluginTarget): String {
    if (plans.isEmpty()) return ""
    val plugins = PluginRegistry.resolve(this, plans.map(PluginOutboundPlan::requirement))
    val envelope = buildJsonObject {
        put("workingDir", target.workingDir)
        target.protectPath?.let { path ->
            put("protectPath", path)
            put("verifyPeer", true)
        }
        putJsonArray("plugins") {
            plans.forEach { plan ->
                addJsonObject {
                    put("name", "${plan.requirement.pluginId}-${plan.socksPort}")
                    putJsonArray("command") {
                        add(plugins.getValue(plan.requirement.pluginId).executable.absolutePath)
                        plan.commandArguments.forEach(::add)
                    }
                    putJsonObject("environment") {
                        plan.environment.forEach { (key, value) -> put(key, value) }
                    }
                    putJsonArray("files") {
                        plan.files.forEach { file ->
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
    // The core owns the files; the app process only provides the directory.
    File(target.workingDir).mkdirs()
    return envelope.toString()
}

/** Writes the envelope beside the config, or removes it when there is none. */
internal fun publishPluginEnvelope(configDirectory: File, content: String) {
    val envelope = File(configDirectory, PluginEnvelopeFileName)
    if (content.isEmpty()) {
        envelope.delete()
        return
    }
    writeAtomically(envelope) { output -> output.write(content.toByteArray(Charsets.UTF_8)) }
}
