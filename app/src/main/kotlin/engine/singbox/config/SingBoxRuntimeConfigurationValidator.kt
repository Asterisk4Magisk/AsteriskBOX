// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package engine.singbox.config

import android.content.Context
import app.AppState

internal fun validateSingBoxRuntimeConfiguration(
    context: Context,
    state: AppState,
) {
    SingBoxConfigCompiler.compile(
        context = context,
        appState = state,
        exposePorts = false,
    )
}
