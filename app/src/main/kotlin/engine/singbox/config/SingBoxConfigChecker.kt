// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package engine.singbox.config

import io.nekohasekai.libbox.Libbox

internal object SingBoxConfigChecker {
    fun check(content: String) {
        val root = parseSingBoxJson(content)
        SingBoxDeprecatedConfigValidator.validate(root)
        Libbox.checkConfig(content)
    }

    fun format(content: String): String {
        val source = parseSingBoxJson(content)
        SingBoxDeprecatedConfigValidator.validate(source)
        val formatted = Libbox.formatConfig(content).value
        val formattedRoot = parseSingBoxJson(formatted)
        SingBoxDeprecatedConfigValidator.validate(formattedRoot)
        Libbox.checkConfig(formatted)
        return formatted
    }
}
