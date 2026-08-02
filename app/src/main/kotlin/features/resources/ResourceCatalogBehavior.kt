// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.resources

import app.CustomResourceFileState
import app.ResourceFileKind
import java.util.Locale

internal fun filterResourceCatalogEntries(
    entries: List<ResourceCatalogEntry>,
    query: String,
): List<ResourceCatalogEntry> {
    val normalizedQuery = query.trim()
    if (normalizedQuery.isEmpty()) return entries
    return entries.filter { entry ->
        entry.name.contains(normalizedQuery, ignoreCase = true)
    }
}

internal data class CatalogResourceAdditionPlan(
    val added: List<CustomResourceFileState>,
    val skipped: List<ResourceCatalogEntry>,
    val nextCustomResourceFileId: Int,
)

internal fun planCatalogResourceAddition(
    customFiles: List<CustomResourceFileState>,
    nextCustomResourceFileId: Int,
    selectedEntries: List<ResourceCatalogEntry>,
): CatalogResourceAdditionPlan {
    val reservedNames = buildSet {
        ResourceFileKind.entries.forEach { kind -> add(kind.fileName.lowercase(Locale.ROOT)) }
        customFiles.forEach { file -> add(file.name.lowercase(Locale.ROOT)) }
    }.toMutableSet()
    val usedIds = customFiles.mapTo(mutableSetOf()) { file -> file.id }
    val added = mutableListOf<CustomResourceFileState>()
    val skipped = mutableListOf<ResourceCatalogEntry>()
    var candidateId = nextCustomResourceFileId.coerceAtLeast(1)

    selectedEntries.forEach { entry ->
        val normalizedName = entry.name.lowercase(Locale.ROOT)
        if (!reservedNames.add(normalizedName)) {
            skipped += entry
        } else {
            while (candidateId in usedIds) candidateId += 1
            added += CustomResourceFileState(
                id = candidateId,
                name = entry.name,
                url = entry.url,
            )
            usedIds += candidateId
            candidateId += 1
        }
    }
    while (candidateId in usedIds) candidateId += 1

    return CatalogResourceAdditionPlan(
        added = added,
        skipped = skipped,
        nextCustomResourceFileId = candidateId,
    )
}
