// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package engine.singbox.config

import android.content.Context
import app.AppState
import java.io.File

internal fun validateSingBoxRuntimeConfiguration(
    context: Context,
    state: AppState,
    customRuleSetFileOverrides: Map<Int, File> = emptyMap(),
) {
    SingBoxConfigCompiler.compile(
        context = context,
        appState = state,
        exposePorts = false,
        customRuleSetFileOverrides = customRuleSetFileOverrides,
    )
}
