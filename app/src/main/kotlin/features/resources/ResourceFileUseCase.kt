// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.resources

import android.content.Context
import android.net.Uri
import app.AppState
import app.CustomResourceFileState
import app.ResourceFileKind
import app.ResourceFileUpdateSource
import app.ResourceFilesStatus
import features.resources.runtime.AndroidResourceFileRepository
import features.resources.runtime.AndroidResourceCatalogRepository
import system.AndroidRootShellGateway
import system.RootShellGateway

class ResourceFileUseCase(
    context: Context,
    private val resourceFilePicker: suspend () -> Uri?,
    currentAppState: () -> AppState,
    rootShell: RootShellGateway = AndroidRootShellGateway(),
) {
    private val repository = AndroidResourceFileRepository(
        context = context.applicationContext,
        currentAppState = currentAppState,
        rootShell = rootShell,
    )
    private val catalogRepository = AndroidResourceCatalogRepository()

    internal suspend fun loadCatalog(
        source: ResourceCatalogSource,
        options: ResourceFileUpdateOptions = ResourceFileUpdateOptions(),
    ): List<ResourceCatalogEntry> {
        return catalogRepository.load(source, options)
    }

    suspend fun status(customResourceFiles: List<CustomResourceFileState> = emptyList()): ResourceFilesStatus {
        return repository.status(customResourceFiles)
    }

    suspend fun restoreBundledDefaults(resourceFileSource: Int): ResourceFilesStatus {
        return repository.restoreBundledDefaults(resourceFileSource)
    }

    suspend fun update(
        source: ResourceFileUpdateSource,
        options: ResourceFileUpdateOptions = ResourceFileUpdateOptions(),
        customResourceFiles: List<CustomResourceFileState> = emptyList(),
    ): ResourceFilesStatus {
        return repository.update(source, options, customResourceFiles)
    }

    suspend fun update(
        kind: ResourceFileKind,
        source: ResourceFileUpdateSource,
        options: ResourceFileUpdateOptions = ResourceFileUpdateOptions(),
        customResourceFiles: List<CustomResourceFileState> = emptyList(),
    ): ResourceFilesStatus {
        return repository.update(kind, source, options, customResourceFiles)
    }

    suspend fun updateCustom(
        customFile: CustomResourceFileState,
        options: ResourceFileUpdateOptions = ResourceFileUpdateOptions(),
        customResourceFiles: List<CustomResourceFileState> = emptyList(),
    ): ResourceFilesStatus {
        return repository.updateCustom(customFile, options, customResourceFiles)
    }

    internal suspend fun updateCustomBatch(
        customFiles: List<CustomResourceFileState>,
        options: ResourceFileUpdateOptions = ResourceFileUpdateOptions(),
        allCustomResourceFiles: List<CustomResourceFileState> = emptyList(),
    ): ResourceFilesStatus {
        return repository.updateCustomBatch(customFiles, options, allCustomResourceFiles)
    }

    suspend fun renameCustom(
        previousFile: CustomResourceFileState,
        customFile: CustomResourceFileState,
        customResourceFiles: List<CustomResourceFileState> = emptyList(),
    ): ResourceFilesStatus {
        return repository.renameCustom(previousFile, customFile, customResourceFiles)
    }

    suspend fun replace(
        kind: ResourceFileKind,
        customResourceFiles: List<CustomResourceFileState> = emptyList(),
    ): ResourceFilesStatus? {
        val uri = resourceFilePicker() ?: return null
        return repository.replace(kind, uri, customResourceFiles)
    }

    suspend fun replaceCustom(
        customFile: CustomResourceFileState,
        customResourceFiles: List<CustomResourceFileState> = emptyList(),
    ): ResourceFilesStatus? {
        val uri = resourceFilePicker() ?: return null
        return repository.replaceCustom(customFile, uri, customResourceFiles)
    }

    internal suspend fun readCustomJson(
        customFile: CustomResourceFileState,
    ): ResourceJsonEditorSnapshot {
        return repository.readCustomJson(customFile)
    }

    internal suspend fun saveCustomJson(
        customFile: CustomResourceFileState,
        content: String,
        expectedOrigin: ResourceJsonFileOrigin,
    ): ResourceFilesStatus {
        return repository.saveCustomJson(customFile, content, expectedOrigin)
    }

    suspend fun restoreBundled(
        kind: ResourceFileKind,
        customResourceFiles: List<CustomResourceFileState> = emptyList(),
    ): ResourceFilesStatus {
        return repository.restoreBundled(kind, customResourceFiles)
    }

    suspend fun deleteCustom(
        customFile: CustomResourceFileState,
        customResourceFiles: List<CustomResourceFileState> = emptyList(),
    ): ResourceFilesStatus {
        return repository.deleteCustom(customFile, customResourceFiles)
    }
}

data class ResourceFileUpdateOptions(
    val useRunningProxy: Boolean = false,
    val fallbackProxyPort: Int? = null,
    val fallbackProxyUsername: String = "",
    val fallbackProxyPassword: String = "",
)
