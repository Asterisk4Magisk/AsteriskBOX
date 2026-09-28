// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.outbound

import app.OutboundState
import engine.singbox.config.SingBoxJson
import features.importing.ImportFingerprint
import features.importing.importFingerprint
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
        // Match identical occurrences one-to-one before the unique-connection
        // fallback so repeated subscription entries retain their managed tags.
        val reusedIds = mutableSetOf<Int>()
        fun matchContent(ignoreRewrittenReferences: Boolean) {
            val previousByContent = previous.filterNot { it.id in reusedIds }.groupBy { outbound ->
                outboundIdentityFingerprint(outbound.type, outbound.remarks, outbound.json, ignoreRewrittenReferences)
            }.mapValues { (_, matches) -> ArrayDeque(matches) }
            imported.forEachIndexed { index, outbound ->
                if (index in this) return@forEachIndexed
                val fingerprint = outboundIdentityFingerprint(
                    outbound.type, outbound.remarks, outbound.json, ignoreRewrittenReferences,
                ) ?: return@forEachIndexed
                val matched = previousByContent[fingerprint]?.removeFirstOrNull()
                    ?: return@forEachIndexed
                put(index, matched.id)
                reusedIds += matched.id
            }
        }
        matchContent(ignoreRewrittenReferences = false)
        // Storage resolves/removes these references. Treat reference-only changes
        // as configuration updates, retaining names, credentials and protocol fields
        // for matching otherwise identical occurrences.
        matchContent(ignoreRewrittenReferences = true)
        importedByIdentity.forEach { (identity, importedMatches) ->
            val previousMatches = previousByIdentity[identity]
            if (importedMatches.size == 1 && previousMatches?.size == 1) {
                val index = importedMatches.single().second
                val id = previousMatches.single().second.id
                if (index !in this && id !in reusedIds) {
                    put(index, id)
                    reusedIds += id
                }
            }
        }
    }
}

private fun outboundIdentityFingerprint(
    type: String,
    remarks: String,
    json: String,
    ignoreRewrittenReferences: Boolean,
): ImportFingerprint? = runCatching {
    val normalized = if (ignoreRewrittenReferences) {
        val outbound = SingBoxJson.parseToJsonElement(json) as? JsonObject ?: return@runCatching null
        JsonObject(outbound - "detour" - "domain_resolver").toString()
    } else {
        json
    }
    importFingerprint(type, remarks, normalized)
}.getOrNull()

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
