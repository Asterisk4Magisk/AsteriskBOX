// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package engine.singbox

import android.content.Context
import app.AppState
import app.modes.SingBoxModeDirect
import app.modes.SingBoxModeGlobal
import engine.singbox.config.SingBoxConfigCompiler

internal object SingBoxConfigFactory {
    fun buildConfig(
        context: Context,
        appState: AppState,
        runMode: Int = appState.runMode,
        exposePorts: Boolean = true,
    ): String = SingBoxConfigCompiler.compile(context, appState, runMode, exposePorts)
}

internal fun AppState.singBoxModeName(): String = when (singBoxMode) {
    SingBoxModeGlobal -> "global"
    SingBoxModeDirect -> "direct"
    else -> "rule"
}
