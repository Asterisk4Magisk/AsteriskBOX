// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package engine.singbox.config

import io.nekohasekai.libbox.Libbox
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

internal object SingBoxConfigChecker {
    fun check(content: String) {
        val root = parseSingBoxJson(content)
        SingBoxDeprecatedConfigValidator.validate(root)
        val compatibleRoot = root.withLibboxCompatibleEbpfInbounds()
        Libbox.checkConfig(if (compatibleRoot === root) content else encodeSingBoxJson(compatibleRoot))
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

internal fun JsonObject.withLibboxCompatibleEbpfInbounds(): JsonObject {
    val inbounds = this["inbounds"] as? JsonArray ?: return this
    var replaced = false
    val compatibleInbounds = inbounds.map { element ->
        val inbound = element as? JsonObject ?: return@map element
        val type = (inbound["type"] as? JsonPrimitive)?.contentOrNull
        if (type != "ebpf") return@map element
        replaced = true
        buildJsonObject {
            put("type", "socks")
            put("listen", "127.0.0.1")
            inbound["tag"]?.let { tag -> put("tag", tag) }
            inbound["listen_port"]?.let { port -> put("listen_port", port) }
        }
    }
    if (!replaced) return this
    return JsonObject(
        buildMap {
            putAll(this@withLibboxCompatibleEbpfInbounds)
            put("inbounds", JsonArray(compatibleInbounds))
        },
    )
}
