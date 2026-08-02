// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.resources

internal enum class ResourceCatalogSource(
    val repository: String,
    val fileNamePrefix: String,
) {
    SingGeosite("sing-geosite", "geosite-"),
    SingGeoip("sing-geoip", "geoip-"),
    ;

    val apiUrl: String
        get() = "https://api.github.com/repos/SagerNet/$repository/git/trees/rule-set"

    fun rawUrl(path: String): String =
        "https://raw.githubusercontent.com/SagerNet/$repository/rule-set/$path"
}

internal data class ResourceCatalogEntry(
    val name: String,
    val url: String,
)

internal sealed interface ResourceCatalogLoadState {
    data object Loading : ResourceCatalogLoadState

    data class Loaded(
        val entries: List<ResourceCatalogEntry>,
    ) : ResourceCatalogLoadState

    data class Failed(
        val error: Throwable,
    ) : ResourceCatalogLoadState
}
