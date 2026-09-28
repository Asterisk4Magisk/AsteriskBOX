// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.outbound

import app.OutboundState
import engine.singbox.config.SingBoxJson
import features.importing.ImportFingerprint
import features.importing.importFingerprint
import kotlinx.serialization.json.JsonObject

internal fun matchImportedOutbounds(
    previous: List<OutboundState>,
    imported: List<ImportedSingBoxOutbound>,
): Map<Int, Int> {
    // A name is an identity only when it is unique in both complete snapshots.
    // Do not turn ambiguous duplicate names into "unique" names after other matches.
    val previousByName = previous.groupBy { it.type to it.remarks.trim() }
    val importedByName = imported.withIndex().groupBy { it.value.type to it.value.remarks.trim() }

    return buildMap {
        // Match identical occurrences one-to-one before the unique-name pass,
        // so duplicate names never cause different configurations to be paired.
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
        importedByName.forEach { (name, importedMatches) ->
            if (name.second.isBlank()) return@forEach
            val old = previousByName[name]?.singleOrNull() ?: return@forEach
            val item = importedMatches.singleOrNull() ?: return@forEach
            if (item.index !in this && old.id !in reusedIds) {
                put(item.index, old.id)
                reusedIds += old.id
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
