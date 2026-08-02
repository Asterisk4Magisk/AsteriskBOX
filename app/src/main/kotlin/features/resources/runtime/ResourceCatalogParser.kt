// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.resources.runtime

import app.customResourceFileNameOrNull
import features.resources.ResourceCatalogEntry
import features.resources.ResourceCatalogSource
import features.resources.hasSingBoxRuleSetExtension
import java.util.Locale
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
private data class GitHubTreeResponse(
    val truncated: Boolean = false,
    val tree: List<GitHubTreeEntry> = emptyList(),
)

@Serializable
private data class GitHubTreeEntry(
    val path: String,
    val type: String,
)

internal fun parseResourceCatalog(
    source: ResourceCatalogSource,
    json: String,
): List<ResourceCatalogEntry> {
    val response = ResourceCatalogJson.decodeFromString<GitHubTreeResponse>(json)
    if (response.truncated) throw ResourceCatalogTruncatedException()
    val entries = response.tree
        .asSequence()
        .filter { entry -> entry.type == "blob" }
        .map { entry -> entry.path }
        .filter { path -> path.substringAfterLast('/') == path }
        .filter { path -> path.startsWith(source.fileNamePrefix) }
        .filter(String::hasSingBoxRuleSetExtension)
        .filter { path -> customResourceFileNameOrNull(path) == path }
        .distinctBy { path -> path.lowercase(Locale.ROOT) }
        .map { path -> ResourceCatalogEntry(name = path, url = source.rawUrl(path)) }
        .sortedWith(
            compareBy<ResourceCatalogEntry> { entry -> entry.name.lowercase(Locale.ROOT) }
                .thenBy { entry -> entry.name },
        )
        .toList()
    if (entries.isEmpty()) throw ResourceCatalogEmptyException()
    return entries
}

internal class ResourceCatalogTruncatedException :
    IllegalStateException("GitHub returned an incomplete resource catalog")

internal class ResourceCatalogEmptyException :
    IllegalStateException("GitHub returned an empty resource catalog")

private val ResourceCatalogJson = Json {
    ignoreUnknownKeys = true
}
