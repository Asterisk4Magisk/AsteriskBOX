// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.outbound

import android.content.Context
import engine.singbox.plugin.PluginOutbounds
import features.resources.runtime.skipsBundledCoreConfigCheck
import io.nekohasekai.libbox.Libbox
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

internal fun interface SingBoxOutboundConfigFormatter {
    fun format(content: String): String
}

internal object LibboxSingBoxOutboundConfigFormatter : SingBoxOutboundConfigFormatter {
    override fun format(content: String): String = Libbox.formatConfig(content).value
}

/** Keeps outbounds as written for a custom ROOT core, which may accept fields libbox rejects. */
internal object PassthroughSingBoxOutboundConfigFormatter : SingBoxOutboundConfigFormatter {
    override fun format(content: String): String = content
}

/**
 * Plugin-backed outbounds (`mieru`, `shadowquic`, …) are resolved by the app into
 * a SOCKS hand-off before the core sees them, so the core formatter must not see
 * them either. They are pulled out, the rest of the document is formatted (so a
 * broken plain outbound still fails), and the plugin entries go back untouched.
 */
internal class PluginAwareSingBoxOutboundConfigFormatter(
    private val delegate: SingBoxOutboundConfigFormatter,
) : SingBoxOutboundConfigFormatter {
    override fun format(content: String): String {
        if (!content.declaresPluginOutbound()) return delegate.format(content)
        val element = runCatching { OutboundImportJson.parseToJsonElement(content) }.getOrNull()
            ?: return content
        return when (element) {
            is JsonArray -> formatOutboundArray(element)
            is JsonObject -> formatDocument(element)
            else -> content
        }
    }

    private fun formatOutboundArray(outbounds: JsonArray): String {
        val plain = JsonArray(outbounds.filterNot(JsonElement::isPluginBackedOutbound))
        if (plain.isEmpty()) return JsonArray(outbounds).toString()
        val formatted = delegate.format(plain.toString())
        val formattedPlain = OutboundImportJson.parseToJsonElement(formatted) as? JsonArray
            ?: return JsonArray(outbounds).toString()
        var next = 0
        val merged = JsonArray(
            outbounds.map { outbound ->
                if (outbound.isPluginBackedOutbound()) outbound else formattedPlain[next++]
            },
        )
        return merged.toString()
    }

    private fun formatDocument(document: JsonObject): String {
        val outbounds = document["outbounds"] as? JsonArray ?: return document.toString()
        val plain = JsonArray(outbounds.filterNot(JsonElement::isPluginBackedOutbound))
        if (plain.size == outbounds.size) return document.toString()
        val stripped = JsonObject(document.toMutableMap().apply { put("outbounds", plain) })
        val formatted = delegate.format(stripped.toString())
        val formattedDocument = OutboundImportJson.parseToJsonElement(formatted) as? JsonObject
            ?: return document.toString()
        val formattedOutbounds = formattedDocument["outbounds"] as? JsonArray
            ?: return document.toString()
        var next = 0
        val merged = JsonArray(
            outbounds.map { outbound ->
                if (outbound.isPluginBackedOutbound()) outbound else formattedOutbounds[next++]
            },
        )
        return JsonObject(formattedDocument.toMutableMap().apply { put("outbounds", merged) }).toString()
    }
}

private val OutboundImportJson = Json { isLenient = true; allowTrailingComma = true }

private fun JsonElement.isPluginBackedOutbound(): Boolean {
    val type = ((this as? JsonObject)?.get("type") as? JsonPrimitive)?.contentOrNull ?: return false
    // Switchable protocols are core types too, so the core can format them.
    return PluginOutbounds.pluginOnly.any { requirement -> requirement.outboundType == type }
}

internal fun String.declaresPluginOutbound(): Boolean {
    if (!contains("\"type\"")) return false
    val element = runCatching { OutboundImportJson.parseToJsonElement(this) }.getOrNull() ?: return false
    val outbounds = when (element) {
        is JsonObject -> (element["outbounds"] as? JsonArray) ?: JsonArray(listOf(element))
        is JsonArray -> element
        else -> return false
    }
    return outbounds.any(JsonElement::isPluginBackedOutbound)
}

internal fun Context.singBoxOutboundConfigFormatter(runMode: Int): SingBoxOutboundConfigFormatter =
    if (skipsBundledCoreConfigCheck(runMode)) {
        PassthroughSingBoxOutboundConfigFormatter
    } else {
        PluginAwareSingBoxOutboundConfigFormatter(LibboxSingBoxOutboundConfigFormatter)
    }
