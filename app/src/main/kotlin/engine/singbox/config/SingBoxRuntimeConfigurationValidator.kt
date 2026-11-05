// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package engine.singbox.config

import android.content.Context
import app.AppState
import engine.singbox.plugin.compilePluginOutbounds
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
    // The core never sees plugin outbound types; check what it will run.
    val checked = compilePluginOutbounds(generated, state.pluginProtocols, target = null).root
    SingBoxConfigChecker.check(encodeSingBoxJson(checked))
}
