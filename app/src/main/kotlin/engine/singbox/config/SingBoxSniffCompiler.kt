// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package engine.singbox.config

import app.AppState
import engine.singbox.sanitizedSnifferProtocols
import engine.singbox.sanitizedSnifferTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

internal object SingBoxSniffCompiler {
    fun compile(appState: AppState): List<JsonObject> {
        if (!appState.enableSniffer) return emptyList()

        return listOf(
            buildJsonObject {
                put("action", "sniff")
                putJsonArray("sniffer") {
                    appState.snifferProtocols.sanitizedSnifferProtocols().forEach(::add)
                }
                put("timeout", appState.snifferTimeout.sanitizedSnifferTimeout())
            },
        )
    }
}
