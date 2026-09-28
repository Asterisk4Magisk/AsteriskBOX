// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.outbound

import app.OutboundState
import engine.singbox.config.SingBoxDeprecatedConfigValidator
import engine.singbox.config.SingBoxJson
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject

internal enum class OutboundBatchDeleteAction {
    DUPLICATES,
    INVALID,
    ALL,
}

internal fun outboundUrls(outbounds: List<OutboundState>): String = outbounds
    .mapNotNull { outbound ->
        (encodeOutboundShareUrl(outbound.json, outbound.remarks) as? AvailableOutboundShareUrl)?.url
    }
    .joinToString("\n")

internal fun duplicateOutbounds(
    outbounds: List<OutboundState>,
    selectedTags: Set<String>,
): List<OutboundState> {
    // Sharing omits dial options such as detour. Keep nodes with different configurations.
    val kept = mutableMapOf<Pair<String, JsonObject>, OutboundState>()
    return buildList {
        outbounds.forEach { outbound ->
            val url = (encodeOutboundShareUrl(outbound.json, outbound.remarks)
                as? AvailableOutboundShareUrl)?.url ?: return@forEach
            val root = SingBoxJson.parseToJsonElement(outbound.json) as JsonObject
            val key = url to JsonObject(root - "tag")
            val previous = kept[key]
            when {
                previous == null -> kept[key] = outbound
                outbound.tag !in selectedTags -> add(outbound)
                previous.tag !in selectedTags -> {
                    add(previous)
                    kept[key] = outbound
                }
                // Different selectors may currently use different copies; preserve both.
                else -> Unit
            }
        }
    }
}

internal fun isInvalidOutbound(
    outbound: OutboundState,
    formatter: SingBoxOutboundConfigFormatter = LibboxSingBoxOutboundConfigFormatter,
): Boolean = try {
    val root = requireNotNull(SingBoxJson.parseToJsonElement(outbound.json) as? JsonObject)
    val document = OutboundEditorDocument(root)
    require(document.type.isNotBlank())
    // Form-required credentials are not always core-required (for example Shadowsocks
    // with method "none"). Check the endpoint separately and let the core decode options.
    if (OutboundEditorRegistry.descriptors.any { it.type == document.type }) {
        val realm = document.type == "hysteria2" && root["realm"] is JsonObject
        if (!realm) {
            require(document.text("server").isNotBlank())
            val hopping = document.type in setOf("hysteria", "hysteria2") &&
                document.text("server_ports").isNotBlank()
            val defaultSshPort = document.type == "ssh" && document.text("server_port").isBlank()
            if (!hopping && !defaultSshPort) {
                require(document.text("server_port").toIntOrNull() in 1..65535)
            }
        }
    }
    val configuration = buildJsonObject { put("outbounds", JsonArray(listOf(root))) }
    SingBoxDeprecatedConfigValidator.validate(configuration)
    formatter.format(configuration.toString())
    false
} catch (error: CancellationException) {
    throw error
} catch (_: Exception) {
    true
}
