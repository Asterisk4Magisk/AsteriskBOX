// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.resources

import app.AppState
import app.CustomResourceFileState
import app.LegacyManagedSingBoxTagPrefix
import app.managedBundledRuleSetTag
import app.managedCustomRuleSetTag
import app.nextAvailableCustomResourceFileId
import app.resourceFileUpdateSource
import app.withCanonicalManagedTagReferences
import app.withReplacedManagedTag

// Packaging definitions only. These files are ordinary, deletable custom resources.
internal enum class BundledRuleSet(val fileName: String, val url: String) {
    GeositeCategoryAdsAll(ResourceFileGeositeCategoryAdsAllName, ResourceFileGeositeCategoryAdsAllUrl),
    GeositeGoogle(ResourceFileGeositeGoogleName, ResourceFileGeositeGoogleUrl),
    GeositeCn(ResourceFileGeositeCnName, ResourceFileGeositeCnUrl),
    GeoipCn(ResourceFileGeoipCnName, ResourceFileGeoipCnUrl),
}

internal fun CustomResourceFileState.bundledRuleSetOrNull(): BundledRuleSet? =
    BundledRuleSet.entries.firstOrNull { bundled -> name == bundled.fileName && url == bundled.url }

internal fun AppState.withInitializedBundledRuleSets(): AppState {
    if (bundledRuleSetsInitialized) return this
    val source = resourceFileUpdateSource()
    var migrated = this
    BundledRuleSet.entries.forEach { bundled ->
        val existing = migrated.customResourceFiles.firstOrNull { it.name == bundled.fileName }
        val custom = existing ?: CustomResourceFileState(
            id = migrated.nextAvailableCustomResourceFileId(),
            name = bundled.fileName,
            url = when (bundled) {
                BundledRuleSet.GeositeCategoryAdsAll -> source.geositeCategoryAdsAllUrl
                BundledRuleSet.GeositeGoogle -> source.geositeGoogleUrl
                BundledRuleSet.GeositeCn -> source.geositeCnUrl
                BundledRuleSet.GeoipCn -> source.geoipCnUrl
            },
        )
        if (existing == null) {
            migrated = migrated.copy(
                customResourceFiles = migrated.customResourceFiles + custom,
                nextCustomResourceFileId = custom.id + 1,
            )
        }
        val tag = managedCustomRuleSetTag(custom.id, custom.name)
        migrated = migrated
            .withReplacedManagedTag(managedBundledRuleSetTag(bundled), tag)
            .withReplacedManagedTag("${LegacyManagedSingBoxTagPrefix}rule_set_${bundled.name.lowercase()}__", tag)
    }
    return migrated.copy(bundledRuleSetsInitialized = true).withCanonicalManagedTagReferences()
}
