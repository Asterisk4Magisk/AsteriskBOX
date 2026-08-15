// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.resources

import app.CustomResourceFileState
import app.ResourceFileKind
import app.ResourceFileStatus
import app.ResourceFilesStatus
import app.customResourceFileNameOrNull
import app.statusOf
import java.net.URI

internal enum class ResourceDisplayAction {
    Update,
    Replace,
    Restore,
    Edit,
    Modify,
    Delete,
}

internal enum class SingBoxRuleSetFileFormat(
    val configValue: String,
    val fileExtension: String,
) {
    Binary(configValue = "binary", fileExtension = ".srs"),
    Source(configValue = "source", fileExtension = ".json"),
}

internal enum class ResourceVisualKind {
    Core,
    AdRuleSet,
    DomainRuleSet,
    IpRuleSet,
    RuleSet,
    Cidr,
    Custom,
}

internal data class ResourceOverviewState(
    val readyCount: Int,
    val totalCount: Int,
    val totalSizeBytes: Long,
)

internal enum class CustomResourceDraftError {
    InvalidName,
    UnsupportedExtension,
    DuplicateName,
    InvalidUrl,
}

internal data class CustomResourceDraftValidation(
    val name: String,
    val url: String,
    val error: CustomResourceDraftError? = null,
) {
    val valid: Boolean
        get() = error == null
}

internal fun reduceResourceOverview(
    status: ResourceFilesStatus,
    customFiles: List<CustomResourceFileState>,
): ResourceOverviewState {
    val builtInStatuses = ResourceFileKind.entries.map(status::statusOf)
    val customStatuses = customFiles.map { file ->
        status.customResourceFiles.firstOrNull { fileStatus -> fileStatus.file.id == file.id }
            ?.status ?: ResourceFileStatus()
    }
    val allStatuses = builtInStatuses + customStatuses
    return ResourceOverviewState(
        readyCount = allStatuses.count(ResourceFileStatus::exists),
        totalCount = allStatuses.size,
        totalSizeBytes = allStatuses.sumOf(ResourceFileStatus::sizeBytes),
    )
}

internal fun customResourceDisplayActions(file: CustomResourceFileState): List<ResourceDisplayAction> {
    return buildList {
        if (file.url.isNotBlank()) add(ResourceDisplayAction.Update)
        add(ResourceDisplayAction.Replace)
        add(ResourceDisplayAction.Edit)
        if (file.name.isSingBoxJsonRuleSet()) add(ResourceDisplayAction.Modify)
        add(ResourceDisplayAction.Delete)
    }
}

internal fun resourceVisualKind(fileName: String): ResourceVisualKind {
    return when {
        fileName == ResourceFileSingBoxCoreName -> ResourceVisualKind.Core
        fileName == ResourceFileGeositeCategoryAdsAllName -> ResourceVisualKind.AdRuleSet
        fileName == ResourceFileGeositeGoogleName ||
            fileName == ResourceFileGeositeCnName -> ResourceVisualKind.DomainRuleSet
        fileName == ResourceFileGeoipCnName -> ResourceVisualKind.IpRuleSet
        fileName == ResourceFileDirectCidrIpv4Name ||
            fileName == ResourceFileDirectCidrIpv6Name -> ResourceVisualKind.Cidr
        fileName.hasSingBoxRuleSetExtension() -> ResourceVisualKind.RuleSet
        else -> ResourceVisualKind.Custom
    }
}

internal fun validateCustomResourceDraft(
    name: String,
    url: String,
    reservedNames: Set<String>,
): CustomResourceDraftValidation {
    val cleanName = name.trim()
    val cleanUrl = url.trim()
    val fileName = customResourceFileNameOrNull(cleanName)
        ?: return CustomResourceDraftValidation(cleanName, cleanUrl, CustomResourceDraftError.InvalidName)
    val format = fileName.singBoxRuleSetFormatOrNull()
    if (format == null) {
        return CustomResourceDraftValidation(fileName, cleanUrl, CustomResourceDraftError.UnsupportedExtension)
    }
    if (fileName.dropLast(format.fileExtension.length).isBlank()) {
        return CustomResourceDraftValidation(fileName, cleanUrl, CustomResourceDraftError.InvalidName)
    }
    if (reservedNames.any { reserved -> reserved.equals(fileName, ignoreCase = true) }) {
        return CustomResourceDraftValidation(fileName, cleanUrl, CustomResourceDraftError.DuplicateName)
    }
    if (cleanUrl.isNotEmpty() && !cleanUrl.isValidHttpResourceUrl()) {
        return CustomResourceDraftValidation(fileName, cleanUrl, CustomResourceDraftError.InvalidUrl)
    }
    return CustomResourceDraftValidation(fileName, cleanUrl)
}

internal fun String.hasSingBoxRuleSetExtension(): Boolean =
    singBoxRuleSetFormatOrNull() != null

internal fun String.singBoxRuleSetFormatOrNull(): SingBoxRuleSetFileFormat? = when {
    endsWith(SingBoxRuleSetFileFormat.Binary.fileExtension, ignoreCase = true) ->
        SingBoxRuleSetFileFormat.Binary
    endsWith(SingBoxRuleSetFileFormat.Source.fileExtension, ignoreCase = true) ->
        SingBoxRuleSetFileFormat.Source
    else -> null
}

internal fun String.isSingBoxJsonRuleSet(): Boolean =
    singBoxRuleSetFormatOrNull() == SingBoxRuleSetFileFormat.Source

internal fun String.isValidHttpResourceUrl(): Boolean {
    return runCatching {
        val uri = URI(this)
        (uri.scheme.equals("http", ignoreCase = true) || uri.scheme.equals("https", ignoreCase = true)) &&
            !uri.host.isNullOrBlank()
    }.getOrDefault(false)
}
