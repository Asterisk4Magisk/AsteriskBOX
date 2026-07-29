// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.importing

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

internal data class ImportFingerprint(
    val type: String,
    val remarks: String,
    val normalizedJson: String,
)

internal data class IndexedImportCandidate<T>(
    val sourceIndex: Int?,
    val value: T,
)

internal data class ImportDeduplicationResult<T>(
    val accepted: List<T>,
    val duplicateCount: Int,
    val mutations: List<ImportMutation>,
)

internal fun importFingerprint(
    type: String,
    remarks: String,
    json: String,
): ImportFingerprint {
    val parsed = Json.parseToJsonElement(json)
    val withoutManagedTag = if (parsed is JsonObject) {
        JsonObject(parsed - "tag")
    } else {
        parsed
    }
    return ImportFingerprint(
        type = type,
        remarks = remarks.trim(),
        normalizedJson = Json.encodeToString(
            JsonElement.serializer(),
            withoutManagedTag.canonicalized(),
        ),
    )
}

internal fun <T> deduplicateImportCandidates(
    candidates: List<IndexedImportCandidate<T>>,
    existingFingerprints: Set<ImportFingerprint> = emptySet(),
    fingerprint: (T) -> ImportFingerprint,
): ImportDeduplicationResult<T> {
    val seen = existingFingerprints.toMutableSet()
    val accepted = mutableListOf<T>()
    val mutations = mutableListOf<ImportMutation>()
    candidates.forEach { candidate ->
        if (seen.add(fingerprint(candidate.value))) {
            accepted += candidate.value
        } else {
            mutations += ImportMutation(
                code = ImportMutationCode.DUPLICATE_SKIPPED,
                sourceIndex = candidate.sourceIndex,
                message = "Duplicate import candidate skipped",
            )
        }
    }
    return ImportDeduplicationResult(
        accepted = accepted,
        duplicateCount = mutations.size,
        mutations = mutations,
    )
}

private fun JsonElement.canonicalized(): JsonElement = when (this) {
    is JsonObject -> JsonObject(
        entries
            .sortedBy(Map.Entry<String, JsonElement>::key)
            .associate { (key, value) -> key to value.canonicalized() },
    )
    is JsonArray -> JsonArray(map(JsonElement::canonicalized))
    else -> this
}
