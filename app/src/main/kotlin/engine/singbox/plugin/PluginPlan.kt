// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package engine.singbox.plugin

/** One file the core writes into the plugin working directory before exec. */
internal data class PluginFile(
    val name: String,
    val content: String,
)

/** One plugin-backed outbound compiled into a local SOCKS hand-off. */
internal data class PluginOutboundPlan(
    val requirement: PluginRequirement,
    val tag: String,
    val socksPort: Int,
    val mappingPort: Int,
    val commandArguments: List<String>,
    val files: List<PluginFile>,
    val environment: Map<String, String>,
)
