// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package engine.singbox.config

import android.content.Context
import app.AppState
import features.resources.runtime.skipsBundledCoreConfigCheck
import java.io.File

internal fun validateSingBoxRuntimeConfiguration(
    context: Context,
    state: AppState,
    customResourceFileOverrides: Map<Int, File> = emptyMap(),
) {
    // Validate editable settings independently of the script used for runtime configs and previews.
    val generated = SingBoxConfigCompiler.generate(
        context = context,
        appState = state,
        exposePorts = false,
        customResourceFileOverrides = customResourceFileOverrides,
    )
    if (context.skipsBundledCoreConfigCheck(state.runMode)) return
    // Plugin-backed outbounds never reach the core as written: compile them into
    // the SOCKS hand-off first, otherwise the core rejects their type.
    val checked = compilePluginOutbounds(
        root = generated,
        useProtect = false,
        protectPath = "",
        workingDir = engine.plugin.pluginWorkingDir(context).absolutePath,
        validationOnly = true,
        // Validate exactly what will run: a protocol switched to its plugin has
        // to be compiled into the SOCKS hand-off here as well.
        pluginBacked = { type -> engine.plugin.PluginProviderPreferences.pluginBacked(context, type) },
    ).root
    SingBoxConfigChecker.check(encodeSingBoxJson(checked))
}
