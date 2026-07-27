// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package engine.singbox.config

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

@OptIn(ExperimentalSerializationApi::class)
internal val SingBoxJson = Json {
    ignoreUnknownKeys = false
    isLenient = false
    explicitNulls = false
    prettyPrint = true
    prettyPrintIndent = "  "
}

internal fun parseSingBoxJson(content: String): JsonObject {
    if (content.isBlank()) {
        error("sing-box configuration is empty")
    }
    return SingBoxJson.parseToJsonElement(content) as? JsonObject
        ?: error("sing-box configuration root must be a JSON object")
}

internal fun encodeSingBoxJson(root: JsonObject): String =
    SingBoxJson.encodeToString(JsonElement.serializer(), root)

internal fun JsonObject.updated(
    name: String,
    value: JsonElement?,
): JsonObject = JsonObject(
    buildMap {
        putAll(this@updated)
        if (value == null) {
            remove(name)
        } else {
            put(name, value)
        }
    },
)

internal fun jsonPointerToken(value: String): String =
    value.replace("~", "~0").replace("/", "~1")
