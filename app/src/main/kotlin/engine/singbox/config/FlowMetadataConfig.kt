// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package engine.singbox.config

import android.content.Context
import app.AppState
import engine.proxy.FlowMetadataInboundTag
import engine.proxy.flowMetadataOptions
import engine.proxy.usesFlowMetadata
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

internal fun String.withFlowMetadata(
    context: Context,
    state: AppState,
    runMode: Int = state.runMode,
    exposePorts: Boolean = true,
): String {
    if (!exposePorts || !state.usesFlowMetadata(runMode)) return this

    val root = Json.parseToJsonElement(this).jsonObject
    val options = context.flowMetadataOptions()
    val inbound = buildJsonObject {
        put("type", "socks")
        put("tag", FlowMetadataInboundTag)
        put("listen", options.listenAddress)
        put("listen_port", options.port)
        putJsonArray("users") {
            add(
                buildJsonObject {
                    put("username", options.username)
                    put("password", options.password)
                }
            )
        }
    }
    val existing = root["inbounds"]?.jsonArray.orEmpty().filterNot {
        it.jsonObject["tag"]?.jsonPrimitive?.content == FlowMetadataInboundTag
    }
    return JsonObject(root + ("inbounds" to JsonArray(existing + inbound))).toString()
}
