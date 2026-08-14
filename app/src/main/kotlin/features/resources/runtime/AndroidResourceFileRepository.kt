// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.resources.runtime

import android.content.Context
import android.net.Uri
import org.asterisk.zcc.abox.R
import app.CustomResourceFileState
import app.ResourceFileKind
import app.ResourceFileUpdateSource
import app.ResourceFilesStatus
import app.urlFor
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import features.resources.ResourceFileUpdateOptions
import engine.root.runtime.RootCorePublicationCoordinator
import system.AndroidRootShellGateway
import system.RootShellGateway

internal class AndroidResourceFileRepository(
    context: Context,
    private val rootShell: RootShellGateway = AndroidRootShellGateway(),
) {
    private val appContext = context.applicationContext
    private val store = AndroidResourceFileStore(appContext)
    private val downloader = AndroidResourceFileDownloader()
    private val corePublication = RootCorePublicationCoordinator(appContext, rootShell)

    suspend fun status(customResourceFiles: List<CustomResourceFileState> = emptyList()): ResourceFilesStatus =
        withContext(Dispatchers.IO) {
            store.status(customResourceFiles)
        }

    suspend fun restoreBundledDefaults(resourceFileSource: Int): ResourceFilesStatus = withContext(Dispatchers.IO) {
        store.restoreBundledDefaults(resourceFileSource)
        if (store.shouldPublishBundledSingBoxCore(resourceFileSource)) {
            publishBundledCoreIfPossible()
        }
        store.currentStatus()
    }

    suspend fun deleteCustom(
        customFile: CustomResourceFileState,
        customResourceFiles: List<CustomResourceFileState>,
    ): ResourceFilesStatus = withContext(Dispatchers.IO) {
        store.deleteCustom(customFile)
        store.currentStatus(customResourceFiles)
    }

    suspend fun renameCustom(
        previousFile: CustomResourceFileState,
        customFile: CustomResourceFileState,
        customResourceFiles: List<CustomResourceFileState>,
    ): ResourceFilesStatus = withContext(Dispatchers.IO) {
        store.renameCustom(previousFile, customFile)
        store.currentStatus(customResourceFiles)
    }

    suspend fun update(
        source: ResourceFileUpdateSource,
        options: ResourceFileUpdateOptions,
        customResourceFiles: List<CustomResourceFileState> = emptyList(),
    ): ResourceFilesStatus = withContext(Dispatchers.IO) {
        updateTargets(
            downloads = ResourceFileKind.entries.mapNotNull { kind -> kind.toDownloadTargetOrNull(source) } +
                customResourceFiles.mapNotNull { customFile -> customFile.toDownloadTargetOrNull() },
            options = options,
            customResourceFiles = customResourceFiles,
        )
    }

    suspend fun update(
        kind: ResourceFileKind,
        source: ResourceFileUpdateSource,
        options: ResourceFileUpdateOptions,
        customResourceFiles: List<CustomResourceFileState> = emptyList(),
    ): ResourceFilesStatus = withContext(Dispatchers.IO) {
        updateTargets(
            downloads = listOfNotNull(kind.toDownloadTargetOrNull(source)),
            options = options,
            customResourceFiles = customResourceFiles,
        )
    }

    suspend fun updateCustom(
        customFile: CustomResourceFileState,
        options: ResourceFileUpdateOptions,
        customResourceFiles: List<CustomResourceFileState> = emptyList(),
    ): ResourceFilesStatus = withContext(Dispatchers.IO) {
        updateTargets(
            downloads = listOfNotNull(customFile.toDownloadTargetOrNull()),
            options = options,
            customResourceFiles = customResourceFiles,
        )
    }

    suspend fun updateCustomBatch(
        customFiles: List<CustomResourceFileState>,
        options: ResourceFileUpdateOptions,
        allCustomResourceFiles: List<CustomResourceFileState> = emptyList(),
    ): ResourceFilesStatus = withContext(Dispatchers.IO) {
        updateTargets(
            downloads = customFiles.mapNotNull { customFile -> customFile.toDownloadTargetOrNull() },
            options = options,
            customResourceFiles = allCustomResourceFiles,
            continueAfterFailure = true,
        )
    }

    private suspend fun updateTargets(
        downloads: List<ResourceFileDownloadTarget>,
        options: ResourceFileUpdateOptions,
        customResourceFiles: List<CustomResourceFileState>,
        continueAfterFailure: Boolean = false,
    ): ResourceFilesStatus {
        if (downloads.isEmpty()) {
            return store.currentStatus(customResourceFiles)
        }
        store.dataDir.mkdirs()
        AndroidResourceFileDownloadCancellation.begin()
        val notifier = AndroidResourceFileDownloadNotifier(appContext)
        val downloadProxy = options.toHttpProxy()
        if (downloadProxy != null) {
            AndroidResourceFileLogger.info(
                "Resource file update will use local proxy ${downloadProxy.host}:${downloadProxy.port}",
            )
        }
        fun downloadOnly(indexed: IndexedValue<ResourceFileDownloadTarget>) {
            val index = indexed.index
            val download = indexed.value
            try {
                notifier.showProgress(download.displayName, progress = null, force = true)
                downloader.download(download.url, download.targetFile, downloadProxy) { downloadedBytes, totalBytes ->
                    notifier.showProgress(
                        fileName = download.displayName,
                        progress = overallProgress(
                            fileIndex = index,
                            fileCount = downloads.size,
                            downloadedBytes = downloadedBytes,
                            totalBytes = totalBytes,
                        ),
                    )
                }
                download.applyPermissions()
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                if (error is AndroidResourceFileDownloadCancelledException) throw error
                throw ResourceFileDownloadFailedException(download.displayName, error)
            }
        }
        suspend fun download(indexed: IndexedValue<ResourceFileDownloadTarget>) {
            val download = indexed.value
            try {
                downloadOnly(indexed)
                if (download.coreCandidate) {
                    val normalized = store.normalizeSingBoxCoreCandidate(download.targetFile)
                    installOrPublishCoreCandidate(requireExistingRoot = true) { normalized }
                }
            } finally {
                if (download.coreCandidate) download.targetFile.delete()
            }
        }
        val indexedDownloads = downloads.withIndex().toList()
        val result = runCatching {
            if (continueAfterFailure) {
                require(indexedDownloads.none { it.value.coreCandidate })
                val batchResult = runResourceFileBatch(indexedDownloads, ::downloadOnly)
                if (batchResult.failures.isNotEmpty()) {
                    throw ResourceFileBatchDownloadFailedException(
                        succeededFileNames = batchResult.succeeded.map { indexed -> indexed.value.displayName },
                        failures = batchResult.failures.map { failure ->
                            ResourceFileBatchDownloadFailure(
                                fileName = failure.target.value.displayName,
                                error = failure.error,
                            )
                        },
                    )
                }
            } else {
                indexedDownloads.forEach { indexed -> download(indexed) }
            }
            store.currentStatus(customResourceFiles)
        }
        result.onSuccess {
            runCatching { notifier.showComplete() }
        }.onFailure { error ->
            if (error is CancellationException) {
                throw error
            } else if (error is AndroidResourceFileDownloadCancelledException) {
                AndroidResourceFileLogger.info("Resource file update cancelled")
                runCatching { notifier.showCancelled() }
            } else {
                AndroidResourceFileLogger.error("Failed to update resource files", error)
                runCatching { notifier.showFailed() }
            }
        }
        return result.getOrElse { error ->
            if (error is CancellationException) {
                throw error
            }
            if (error is AndroidResourceFileDownloadCancelledException) {
                throw AndroidResourceFileDownloadCancelledException(
                    appContext.getString(R.string.resource_file_download_notification_cancelled),
                )
            }
            throw error
        }
    }

    private fun CustomResourceFileState.toDownloadTargetOrNull(): ResourceFileDownloadTarget? {
        val target = store.file(this)
        if (ResourceFileKind.entries.any { kind -> kind.fileName == target.name }) return null
        val updateUrl = url.trim()
        if (updateUrl.isBlank()) return null
        return ResourceFileDownloadTarget(
            displayName = name,
            url = updateUrl,
            targetFile = target,
        )
    }

    private fun ResourceFileKind.toDownloadTargetOrNull(source: ResourceFileUpdateSource): ResourceFileDownloadTarget? {
        val updateUrl = source.urlFor(this)?.trim().orEmpty()
        if (updateUrl.isBlank()) return null
        val coreCandidate = this == ResourceFileKind.SingBoxCore
        return ResourceFileDownloadTarget(
            displayName = displayName,
            url = updateUrl,
            targetFile = if (coreCandidate) store.createSingBoxCoreDownloadCandidate() else store.file(this),
            applyPermissions = { if (!coreCandidate) store.applyPermissions(this) },
            coreCandidate = coreCandidate,
        )
    }

    suspend fun replaceCustom(
        customFile: CustomResourceFileState,
        uri: Uri,
        customResourceFiles: List<CustomResourceFileState> = emptyList(),
    ): ResourceFilesStatus = withContext(Dispatchers.IO) {
        store.replaceCustom(customFile, uri)
        store.currentStatus(customResourceFiles)
    }

    suspend fun replace(
        kind: ResourceFileKind,
        uri: Uri,
        customResourceFiles: List<CustomResourceFileState> = emptyList(),
    ): ResourceFilesStatus = withContext(Dispatchers.IO) {
        if (kind == ResourceFileKind.SingBoxCore) {
            installOrPublishCoreCandidate(requireExistingRoot = true) { store.stageSingBoxCoreCandidate(uri) }
        } else {
            store.replace(kind, uri)
        }
        store.currentStatus(customResourceFiles)
    }

    suspend fun restoreBundled(
        kind: ResourceFileKind,
        customResourceFiles: List<CustomResourceFileState> = emptyList(),
    ): ResourceFilesStatus = withContext(Dispatchers.IO) {
        if (kind == ResourceFileKind.SingBoxCore) {
            installOrPublishCoreCandidate(requireExistingRoot = true) {
                store.stageBundledSingBoxCoreCandidate()
            }
        } else {
            store.restoreBundled(kind)
        }
        store.currentStatus(customResourceFiles)
    }

    private suspend fun publishBundledCoreIfPossible() {
        when (chooseCoreCandidateInstallPath(rootShell.hasRootAccess(), java.io.File(corePublication.corePath).exists())) {
            CoreCandidateInstallPath.AtomicPublication -> {
                if (!corePublication.isAvailable()) return
                corePublication.prepareDirectories()
                publishCoreCandidate(store.stageBundledSingBoxCoreCandidate())
            }
            CoreCandidateInstallPath.InitialNoReplace -> {
                installInitialCoreCandidate(store.stageBundledSingBoxCoreCandidate())
            }
            CoreCandidateInstallPath.Defer -> AndroidResourceFileLogger.info(
                "Bundled sing-box core replacement deferred because ROOT access is unavailable",
            )
        }
    }

    private suspend fun installOrPublishCoreCandidate(
        requireExistingRoot: Boolean,
        candidateFactory: () -> java.io.File,
    ) {
        when (chooseCoreCandidateInstallPath(rootShell.hasRootAccess(), java.io.File(corePublication.corePath).exists())) {
            CoreCandidateInstallPath.AtomicPublication -> {
                corePublication.requireAvailable()
                corePublication.prepareDirectories()
                publishCoreCandidate(candidateFactory())
            }
            CoreCandidateInstallPath.InitialNoReplace -> installInitialCoreCandidate(candidateFactory())
            CoreCandidateInstallPath.Defer -> check(!requireExistingRoot) {
                appContext.getString(R.string.settings_root_required)
            }
        }
    }

    private fun installInitialCoreCandidate(candidate: java.io.File) {
        try {
            corePublication.validate(candidate)
            val installed = store.installInitialSingBoxCoreCandidate(candidate)
            require(installed || java.io.File(corePublication.corePath).isFile) {
                "Failed to install the initial sing-box core"
            }
        } finally {
            candidate.delete()
        }
    }

    private suspend fun publishCoreCandidate(candidate: java.io.File) {
        try {
            corePublication.publish(candidate)
        } finally {
            candidate.delete()
        }
    }
}

private data class ResourceFileDownloadTarget(
    val displayName: String,
    val url: String,
    val targetFile: java.io.File,
    val applyPermissions: () -> Unit = {},
    val coreCandidate: Boolean = false,
)

private class ResourceFileDownloadFailedException(
    fileName: String,
    cause: Throwable,
) : RuntimeException("$fileName: ${cause.message ?: cause::class.simpleName.orEmpty()}", cause)
