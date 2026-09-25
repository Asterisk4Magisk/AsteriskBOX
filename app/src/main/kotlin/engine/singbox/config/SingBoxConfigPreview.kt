// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package engine.singbox.config

import java.io.File

internal fun loadSingBoxConfigPreview(
    running: Boolean,
    configFile: File,
    generateConfig: () -> String,
): String {
    val content = if (running) configFile.readText() else generateConfig()
    return encodeSingBoxJson(parseSingBoxJson(content))
}
