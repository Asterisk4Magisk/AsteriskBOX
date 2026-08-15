// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.resources.runtime

import android.content.Context
import android.net.Uri
import org.asterisk.zcc.abox.R
import app.AppState
import app.CustomResourceFileState
import app.ResourceFileKind
import app.ResourceFileUpdateSource
import app.ResourceFilesStatus
import app.urlFor
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import features.resources.ResourceFileUpdateOptions
import features.resources.ResourceJsonEditorSnapshot
import features.resources.ResourceJsonFileOrigin
import engine.root.runtime.RootCorePublicationCoordinator
import engine.singbox.config.validateSingBoxRuntimeConfiguration
import features.resources.isSingBoxJsonRuleSet
import features.resources.InvalidSingBoxJsonRuleSetException
import features.resources.resourceJsonEditorSnapshot
import features.resources.requireValidJsonRuleSetStructure
import system.AndroidRootShellGateway
import system.RootShellGateway

internal class AndroidResourceFileRepository(
    context: Context,
    private val currentAppState: () -> AppState,
    private val rootShell: RootShellGateway = AndroidRootShellGateway(),
) {
    private val appContext = context.applicationContext
    private val store = AndroidResourceFileStore(appContext)
    private val downloader = AndroidResourceFileDownloader()
    private val corePublication = RootCorePublicationCoordinator(appContext, rootShell)
    private val customResourceMutationLock = Any()

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
        synchronized(customResourceMutationLock) {
            store.deleteCustom(customFile)
        }
        store.currentStatus(customResourceFiles)
    }

    suspend fun renameCustom(
        previousFile: CustomResourceFileState,
        customFile: CustomResourceFileState,
        customResourceFiles: List<CustomResourceFileState>,
    ): ResourceFilesStatus = withContext(Dispatchers.IO) {
        synchronized(customResourceMutationLock) {
            val source = store.file(previousFile)
            val target = store.file(customFile)
            if (source.absolutePath != target.absolutePath) {
                val candidate = store.stageCustomCandidate(customFile, source)
                publishCustomCandidate(
                    customFile = customFile,
                    candidate = candidate,
                    requireCurrentMetadata = false,
                    expectedLiveFile = previousFile,
                )
                try {
                    requireCurrentCustomFile(previousFile, currentAppState())
                    store.deleteCustom(previousFile)
                } catch (error: Throwable) {
                    runCatching { store.deleteCustom(customFile) }
                    throw error
                }
            }
        }
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
            val workingFile = download.candidateFactory?.invoke() ?: download.targetFile
            try {
                notifier.showProgress(download.displayName, progress = null, force = true)
                downloader.download(download.url, workingFile, downloadProxy) { downloadedBytes, totalBytes ->
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
                download.publishCandidate?.invoke(workingFile) ?: download.applyPermissions()
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                if (error is AndroidResourceFileDownloadCancelledException) throw error
                throw ResourceFileDownloadFailedException(download.displayName, error)
            } finally {
                if (download.candidateFactory != null) workingFile.delete()
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
        if (!name.isSingBoxJsonRuleSet()) {
            return ResourceFileDownloadTarget(
                displayName = name,
                url = updateUrl,
                targetFile = target,
            )
        }
        val expectedTargetRevision = target.resourceFileRevision()
        return ResourceFileDownloadTarget(
            displayName = name,
            url = updateUrl,
            targetFile = target,
            candidateFactory = { store.createCustomDownloadCandidate(this) },
            publishCandidate = { candidate ->
                publishCustomCandidate(
                    customFile = this,
                    candidate = candidate,
                    expectedTargetRevision = expectedTargetRevision,
                )
            },
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
        if (customFile.name.isSingBoxJsonRuleSet()) {
            val expectedTargetRevision = store.file(customFile).resourceFileRevision()
            publishCustomCandidate(
                customFile = customFile,
                candidate = store.stageCustomCandidate(customFile, uri),
                expectedTargetRevision = expectedTargetRevision,
            )
        } else {
            store.replaceCustom(customFile, uri)
        }
        store.currentStatus(customResourceFiles)
    }

    suspend fun readCustomJson(
        customFile: CustomResourceFileState,
    ): ResourceJsonEditorSnapshot = withContext(Dispatchers.IO) {
        require(customFile.name.isSingBoxJsonRuleSet()) { "${customFile.name} is not a JSON rule set" }
        resourceJsonEditorSnapshot(store.readCustomTextOrNull(customFile))
    }

    suspend fun saveCustomJson(
        customFile: CustomResourceFileState,
        content: String,
        expectedOrigin: ResourceJsonFileOrigin,
    ): ResourceFilesStatus = withContext(Dispatchers.IO) {
        require(customFile.name.isSingBoxJsonRuleSet()) { "${customFile.name} is not a JSON rule set" }
        val liveFiles = currentAppState().customResourceFiles
        publishCustomCandidate(
            customFile = customFile,
            candidate = store.stageCustomCandidate(customFile, content),
            expectedTargetRevision = expectedOrigin.expectedResourceFileRevision(),
        )
        store.currentStatus(liveFiles)
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

    private fun publishCustomCandidate(
        customFile: CustomResourceFileState,
        candidate: java.io.File,
        requireCurrentMetadata: Boolean = true,
        expectedTargetRevision: ResourceFileRevision? = null,
        expectedLiveFile: CustomResourceFileState? = if (requireCurrentMetadata) customFile else null,
    ) {
        synchronized(customResourceMutationLock) {
            val target = store.file(customFile)
            publishValidatedResourceCandidate(
                candidate = candidate,
                target = target,
                mode = expectedTargetRevision.publicationMode(),
            ) { stagedFile ->
                expectedLiveFile?.let { expected ->
                    requireCurrentCustomFile(expected, currentAppState())
                }
                val validationState = if (requireCurrentMetadata) {
                    currentAppState()
                } else {
                    val expected = checkNotNull(expectedLiveFile)
                    val liveState = currentAppState()
                    requireCurrentCustomFile(expected, liveState)
                    liveState.copy(
                        customResourceFiles = liveState.customResourceFiles.map { liveFile ->
                            if (liveFile.id == expected.id) customFile else liveFile
                        },
                    )
                }
                expectedTargetRevision?.let { expected ->
                    requireResourceFileRevisionUnchanged(target, expected)
                }
                if (customFile.name.isSingBoxJsonRuleSet()) {
                    requireValidJsonRuleSetStructure(stagedFile.readText())
                }
                try {
                    validateSingBoxRuntimeConfiguration(
                        context = appContext,
                        state = validationState,
                        customRuleSetFileOverrides = mapOf(customFile.id to stagedFile),
                    )
                } catch (error: Throwable) {
                    if (!customFile.name.isSingBoxJsonRuleSet()) throw error
                    throw InvalidSingBoxJsonRuleSetException(error)
                }
                expectedLiveFile?.let { expected ->
                    requireCurrentCustomFile(expected, currentAppState())
                }
            }
        }
    }

    private fun requireCurrentCustomFile(
        customFile: CustomResourceFileState,
        state: AppState,
    ) {
        check(state.customResourceFiles.any { liveFile -> liveFile == customFile }) {
            "${customFile.name} is no longer the current resource file"
        }
    }
}

private fun ResourceFileRevision?.publicationMode(): ResourceFilePublicationMode {
    return if (this?.exists == false) {
        ResourceFilePublicationMode.CreateNew
    } else {
        ResourceFilePublicationMode.Replace
    }
}

private data class ResourceFileDownloadTarget(
    val displayName: String,
    val url: String,
    val targetFile: java.io.File,
    val applyPermissions: () -> Unit = {},
    val coreCandidate: Boolean = false,
    val candidateFactory: (() -> java.io.File)? = null,
    val publishCandidate: ((java.io.File) -> Unit)? = null,
)

private class ResourceFileDownloadFailedException(
    fileName: String,
    cause: Throwable,
) : RuntimeException("$fileName: ${cause.message ?: cause::class.simpleName.orEmpty()}", cause)
