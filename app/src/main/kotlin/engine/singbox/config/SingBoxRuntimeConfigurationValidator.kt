// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package engine.singbox.config

import android.content.Context
import app.AppState
import engine.singbox.plugin.compilePluginOutbounds
import engine.singbox.plugin.withLocalizedVlessXrayErrors
import features.resources.runtime.skipsBundledCoreConfigCheck
import java.io.File

internal fun validateSingBoxRuntimeConfiguration(
    context: Context,
    state: AppState,
    customResourceFileOverrides: Map<Int, File> = emptyMap(),
) {
    // Check editable settings without the optional group hops. Runtime compilation uses the saved hops.
    val editableState = state.copy(
        outboundGroups = state.outboundGroups.map { group -> group.copy(detour = "", landing = "") },
    )
    val generated = SingBoxConfigCompiler.generate(
        context = context,
        appState = editableState,
        exposePorts = false,
        customResourceFileOverrides = customResourceFileOverrides,
    )
    if (context.skipsBundledCoreConfigCheck(state.runMode)) return
    // The core never sees plugin outbound types; check what it will run.
    val checked = context.withLocalizedVlessXrayErrors {
        compilePluginOutbounds(generated, state.pluginProtocols, target = null).root
    }
    SingBoxConfigChecker.check(encodeSingBoxJson(checked))
}
