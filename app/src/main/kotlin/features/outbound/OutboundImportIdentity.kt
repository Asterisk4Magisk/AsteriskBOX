// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.outbound

import app.OutboundState
import engine.singbox.config.SingBoxJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.util.Locale

internal data class OutboundConnectionIdentity(
    val type: String,
    val server: String,
    val port: String,
)

internal fun connectionIdentity(type: String, json: String): OutboundConnectionIdentity? {
    val outbound = runCatching {
        SingBoxJson.parseToJsonElement(json) as? JsonObject
    }.getOrNull() ?: return null
    val normalizedType = type.trim().lowercase(Locale.ROOT).takeIf(String::isNotBlank) ?: return null
    val server = outbound.identityStringValue("server")
        ?.trim()
        ?.removePrefix("[")
        ?.removeSuffix("]")
        ?.lowercase(Locale.ROOT)
        ?.takeIf(String::isNotBlank)
        ?: return null
    val port = outbound.connectionPort() ?: return null
    return OutboundConnectionIdentity(normalizedType, server, port)
}

internal fun matchImportedOutbounds(
    previous: List<OutboundState>,
    imported: List<ImportedSingBoxOutbound>,
): Map<Int, Int> {
    val previousByIdentity = previous
        .mapNotNull { outbound ->
            connectionIdentity(outbound.type, outbound.json)?.let { identity -> identity to outbound }
        }
        .groupBy(Pair<OutboundConnectionIdentity, OutboundState>::first)
    val importedByIdentity = imported
        .mapIndexedNotNull { index, outbound ->
            connectionIdentity(outbound.type, outbound.json)?.let { identity -> identity to index }
        }
        .groupBy(Pair<OutboundConnectionIdentity, Int>::first)

    return buildMap {
        importedByIdentity.forEach { (identity, importedMatches) ->
            val previousMatches = previousByIdentity[identity]
            if (importedMatches.size == 1 && previousMatches?.size == 1) {
                put(importedMatches.single().second, previousMatches.single().second.id)
            }
        }
    }
}

private fun JsonObject.connectionPort(): String? =
    identityStringValue("server_port")?.canonicalPort()
        ?: (get("server_ports") as? JsonArray)?.canonicalPorts()

private fun JsonArray.canonicalPorts(): String? =
    map { port -> (port as? JsonPrimitive)?.contentOrNull?.canonicalPort() ?: return null }
        .takeIf(List<String>::isNotEmpty)
        ?.sorted()
        ?.joinToString(",")

private fun String.canonicalPort(): String? =
    trim()
        .takeIf(String::isNotBlank)
        ?.let { value ->
            val normalizedRange = value.replace(Regex("""^(\d+)-(\d+)$"""), "$1:$2")
            normalizedRange
                .split(':')
                .takeIf { parts -> parts.isNotEmpty() && parts.all(String::allDigits) }
                ?.joinToString(":") { part -> part.toLongOrNull()?.toString() ?: part }
                ?: normalizedRange
        }

private fun String.allDigits(): Boolean = isNotEmpty() && all(Char::isDigit)

private fun JsonObject.identityStringValue(name: String): String? =
    (get(name) as? JsonPrimitive)?.contentOrNull
