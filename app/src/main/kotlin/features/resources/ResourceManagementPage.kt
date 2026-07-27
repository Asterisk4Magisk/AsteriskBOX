// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package features.resources

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.AppState
import app.CustomResourceFileState
import app.CustomResourceFileStatus
import app.LocalAppServices
import app.LocalAppStateStore
import app.LocalIsWideScreen
import app.LocalNavigator
import app.LocalUpdateAppState
import org.asterisk.zcc.abox.R
import app.ResourceFileKind
import app.ResourceFilesStatus
import app.collectAppState
import app.customResourceFileNameOrNull
import app.nextAvailableCustomResourceFileId
import app.resourceFileUpdateSource
import app.statusOf
import app.withRemovedManagedRuleSets
import engine.network.toPortOrNull
import features.resources.runtime.AndroidResourceFileDownloadCancellation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import ui.layout.pageContentPaddingWithCutout
import ui.layout.pageListPadding
import ui.text.formatTemplate
import kotlin.coroutines.cancellation.CancellationException
import ui.icons.AsteriskIcons as Icons

@Composable
fun ResourceManagementPage(
    padding: PaddingValues,
) {
    val isWideScreen = LocalIsWideScreen.current
    val navigator = LocalNavigator.current
    val appState by LocalAppStateStore.current.collectAppState()
    val updateAppState = LocalUpdateAppState.current
    val services = LocalAppServices.current
    val resourceFileUseCase = services.resourceFileUseCase
    val sourceOptions = settingsResourceFileSourceOptions()
    val tipNotifier = services.tipNotifier
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf(ResourceFilesStatus()) }
    var updating by remember { mutableStateOf(false) }
    val showCustomResourceFileDialog = remember { mutableStateOf(false) }
    var editingCustomResourceFile by remember { mutableStateOf<CustomResourceFileState?>(null) }
    val customResourceFileNameState = rememberTextFieldState()
    val customResourceFileUrlState = rememberTextFieldState()
    val editCustomResourceFileNameState = rememberTextFieldState()
    val editCustomResourceFileUrlState = rememberTextFieldState()
    var showCustomSourceEditor by remember { mutableStateOf(false) }
    val sourceGeositeCategoryAdsAllUrlState = rememberTextFieldState()
    val sourceGeositeGoogleUrlState = rememberTextFieldState()
    val sourceGeositeCnUrlState = rememberTextFieldState()
    val sourceGeoipCnUrlState = rememberTextFieldState()
    val sourceDirectCidrIpv4UrlState = rememberTextFieldState()
    val sourceDirectCidrIpv6UrlState = rememberTextFieldState()
    val updatingMessage = stringResource(R.string.settings_resource_files_updating)
    val updatedMessage = stringResource(R.string.settings_resource_files_updated)
    val updatedOneMessage = stringResource(R.string.settings_resource_file_updated)
    val replacedMessage = stringResource(R.string.settings_resource_files_replaced)
    val restoredMessage = stringResource(R.string.settings_resource_files_restored)
    val deletedMessage = stringResource(R.string.settings_resource_files_deleted)
    val resourceFileActionFailedMessage = stringResource(
        R.string.settings_resource_files_action_failed,
    )
    val customResourceFileNameInvalidMessage = stringResource(
        R.string.settings_resource_files_custom_name_invalid,
    )
    val customResourceFileNameSrsRequiredMessage = stringResource(
        R.string.settings_resource_files_custom_name_srs_required,
    )
    val customResourceFileNameDuplicateMessage = stringResource(
        R.string.settings_resource_files_custom_name_duplicate,
    )

    fun runResourceFileAction(
        action: suspend () -> ResourceFilesStatus?,
        successMessage: String?,
        onSuccess: (() -> Unit)? = null,
        failureStatusCustomResourceFiles: (() -> List<CustomResourceFileState>)? = null,
    ) {
        if (updating) return
        updating = true
        val result = CompletableDeferred<ResourceFilesStatus?>()
        services.appScope.launch {
            try {
                val nextStatus = action()
                if (nextStatus != null) {
                    successMessage?.let { message -> tipNotifier.show(message) }
                }
                result.complete(nextStatus)
            } catch (error: CancellationException) {
                result.completeExceptionally(error)
                throw error
            } catch (error: Throwable) {
                val failureStatus = failureStatusCustomResourceFiles?.let { customResourceFiles ->
                    runCatching {
                        resourceFileUseCase.status(customResourceFiles())
                    }.getOrNull()
                }
                tipNotifier.showError(error, resourceFileActionFailedMessage)
                result.complete(failureStatus)
            }
        }
        scope.launch {
            try {
                result.await()?.let { nextStatus ->
                    status = nextStatus
                    onSuccess?.invoke()
                }
            } finally {
                updating = false
            }
        }
    }

    fun showResourceFileEditorError(message: String) {
        services.appScope.launch {
            tipNotifier.show(message)
        }
    }

    fun updateResourceFile(kind: ResourceFileKind) {
        runResourceFileAction(
            action = {
                tipNotifier.show(updatingMessage)
                resourceFileUseCase.update(
                    kind = kind,
                    source = appState.resourceFileUpdateSource(),
                    options = appState.resourceFileUpdateOptions(),
                    customResourceFiles = appState.customResourceFiles,
                )
            },
            successMessage = updatedOneMessage.formatTemplate("name" to kind.displayName),
            failureStatusCustomResourceFiles = { appState.customResourceFiles },
        )
    }

    fun updateCustomResourceFile(file: CustomResourceFileState) {
        runResourceFileAction(
            action = {
                tipNotifier.show(updatingMessage)
                resourceFileUseCase.updateCustom(
                    customFile = file,
                    options = appState.resourceFileUpdateOptions(),
                    customResourceFiles = appState.customResourceFiles,
                )
            },
            successMessage = updatedOneMessage.formatTemplate("name" to file.name),
            failureStatusCustomResourceFiles = { appState.customResourceFiles },
        )
    }

    fun customResourceFileReservedNames(editingFileId: Int? = null): Set<String> {
        return ResourceFileKind.entries.map { kind -> kind.fileName }.toSet() +
            appState.customResourceFiles
                .filterNot { file -> file.id == editingFileId }
                .map { file -> file.name }
    }

    fun validatedCustomResourceFileName(name: String, reservedNames: Set<String>): String? {
        val fileName = customResourceFileNameOrNull(name)
        if (fileName == null) {
            showResourceFileEditorError(customResourceFileNameInvalidMessage)
            return null
        }
        if (!fileName.hasSingBoxRuleSetExtension()) {
            showResourceFileEditorError(customResourceFileNameSrsRequiredMessage)
            return null
        }
        if (fileName.dropLast(".srs".length).isBlank()) {
            showResourceFileEditorError(customResourceFileNameInvalidMessage)
            return null
        }
        if (reservedNames.any { reserved -> reserved.equals(fileName, ignoreCase = true) }) {
            showResourceFileEditorError(customResourceFileNameDuplicateMessage)
            return null
        }
        return fileName
    }

    fun addCustomResourceFile(name: String, url: String): Boolean {
        val fileName = validatedCustomResourceFileName(
            name = name,
            reservedNames = customResourceFileReservedNames(),
        ) ?: return false
        var addedFile: CustomResourceFileState? = null
        var nextCustomResourceFiles = appState.customResourceFiles
        updateAppState { state ->
            val updateUrl = url.trim()
            val fileId = state.nextAvailableCustomResourceFileId()
            val nextCustomFile = CustomResourceFileState(
                id = fileId,
                name = fileName,
                url = updateUrl,
            )
            addedFile = nextCustomFile
            nextCustomResourceFiles = state.customResourceFiles + nextCustomFile
            state.copy(
                customResourceFiles = nextCustomResourceFiles,
                nextCustomResourceFileId = fileId + 1,
            )
        }
        addedFile?.takeIf { file -> file.url.isBlank() }?.let { file ->
            runResourceFileAction(
                action = {
                    resourceFileUseCase.replaceCustom(
                        customFile = file,
                        customResourceFiles = nextCustomResourceFiles,
                    )
                },
                successMessage = replacedMessage.formatTemplate("name" to file.name),
            )
        }
        return true
    }

    fun editCustomResourceFile(file: CustomResourceFileState, name: String, url: String): Boolean {
        val fileName = validatedCustomResourceFileName(
            name = name,
            reservedNames = customResourceFileReservedNames(editingFileId = file.id),
        ) ?: return false
        val nextCustomFile = file.copy(
            name = fileName,
            url = url.trim(),
        )
        val nextCustomResourceFiles = appState.customResourceFiles.map { customFile ->
            if (customFile.id == file.id) nextCustomFile else customFile
        }
        runResourceFileAction(
            action = {
                resourceFileUseCase.renameCustom(
                    previousFile = file,
                    customFile = nextCustomFile,
                    customResourceFiles = nextCustomResourceFiles,
                )
            },
            successMessage = null,
            onSuccess = {
                updateAppState { state ->
                    state.copy(
                        customResourceFiles = state.customResourceFiles.map { customFile ->
                            if (customFile.id == file.id) nextCustomFile else customFile
                        },
                    )
                }
            },
        )
        return true
    }

    fun openCustomSourceEditor() {
        val source = appState.resourceFileUpdateSource()
        sourceGeositeCategoryAdsAllUrlState.setTextAndPlaceCursorAtEnd(
            appState.customResourceFileGeositeCategoryAdsAllUrl.ifBlank {
                source.geositeCategoryAdsAllUrl
            },
        )
        sourceGeositeGoogleUrlState.setTextAndPlaceCursorAtEnd(
            appState.customResourceFileGeositeGoogleUrl.ifBlank { source.geositeGoogleUrl },
        )
        sourceGeositeCnUrlState.setTextAndPlaceCursorAtEnd(
            appState.customResourceFileGeositeCnUrl.ifBlank { source.geositeCnUrl },
        )
        sourceGeoipCnUrlState.setTextAndPlaceCursorAtEnd(
            appState.customResourceFileGeoipCnUrl.ifBlank { source.geoipCnUrl },
        )
        sourceDirectCidrIpv4UrlState.setTextAndPlaceCursorAtEnd(
            appState.customResourceFileDirectCidrIpv4Url.ifBlank { source.directCidrIpv4Url },
        )
        sourceDirectCidrIpv6UrlState.setTextAndPlaceCursorAtEnd(
            appState.customResourceFileDirectCidrIpv6Url.ifBlank { source.directCidrIpv6Url },
        )
        showCustomSourceEditor = true
    }

    LaunchedEffect(appState.customResourceFiles) {
        status = resourceFileUseCase.status(appState.customResourceFiles)
    }

    val overview = reduceResourceOverview(status, appState.customResourceFiles)
    val lastUpdatedAtMillis = (
        ResourceFileKind.entries.map { kind -> status.statusOf(kind).updatedAtMillis } +
            status.customResourceFiles.map { file -> file.status.updatedAtMillis }
        ).maxOrNull() ?: 0L

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_resource_management)) },
                navigationIcon = {
                    IconButton(onClick = { navigator.pop() }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.common_back),
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            customResourceFileNameState.clearText()
                            customResourceFileUrlState.clearText()
                            showCustomResourceFileDialog.value = true
                        },
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Add,
                            contentDescription = stringResource(
                                R.string.settings_resource_files_add_custom,
                            ),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        val contentPadding = pageContentPaddingWithCutout(
            innerPadding = innerPadding,
            outerPadding = padding,
            isWideScreen = isWideScreen,
        )
        val listPadding = pageListPadding(contentPadding)

        LazyColumn(
            contentPadding = listPadding,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(key = "resource_overview") {
                ResourceOverviewCard(
                    overview = overview,
                    sourceOptions = sourceOptions,
                    selectedSource = appState.resourceFileSource,
                    lastUpdatedAtMillis = lastUpdatedAtMillis,
                    updating = updating,
                    onSourceChange = { index ->
                        if (index == ResourceFileSourceCustom) {
                            openCustomSourceEditor()
                        } else {
                            updateAppState { state -> state.copy(resourceFileSource = index) }
                        }
                    },
                    onUpdate = {
                        runResourceFileAction(
                            action = {
                                tipNotifier.show(updatingMessage)
                                resourceFileUseCase.update(
                                    source = appState.resourceFileUpdateSource(),
                                    options = appState.resourceFileUpdateOptions(),
                                    customResourceFiles = appState.customResourceFiles,
                                )
                            },
                            successMessage = updatedMessage,
                            failureStatusCustomResourceFiles = { appState.customResourceFiles },
                        )
                    },
                    onCancel = { AndroidResourceFileDownloadCancellation.cancel() },
                )
            }
            item(key = "resource_core_section") {
                ResourceSectionTitle(stringResource(R.string.settings_resource_files_core_files))
            }
            item(key = ResourceFileKind.SingBoxCore.fileName) {
                val kind = ResourceFileKind.SingBoxCore
                ResourceFileCard(
                    fileName = kind.displayName,
                    status = status.statusOf(kind),
                    updating = updating,
                    description = stringResource(R.string.settings_resource_files_root_only),
                    onReplace = {
                        runResourceFileAction(
                            action = { resourceFileUseCase.replace(kind, appState.customResourceFiles) },
                            successMessage = replacedMessage.formatTemplate("name" to kind.displayName),
                        )
                    },
                    onRestore = {
                        runResourceFileAction(
                            action = { resourceFileUseCase.restoreBundled(kind, appState.customResourceFiles) },
                            successMessage = restoredMessage.formatTemplate("name" to kind.displayName),
                        )
                    },
                )
            }
            item(key = "resource_rules_section") {
                ResourceSectionTitle(stringResource(R.string.settings_resource_files_files))
            }
            ResourceFileKind.entries.filterNot { it == ResourceFileKind.SingBoxCore }.forEach { kind ->
                item(key = kind.fileName) {
                    ResourceFileCard(
                        fileName = kind.displayName,
                        status = status.statusOf(kind),
                        updating = updating,
                        onUpdate = { updateResourceFile(kind) },
                        onReplace = {
                            runResourceFileAction(
                                action = { resourceFileUseCase.replace(kind, appState.customResourceFiles) },
                                successMessage = replacedMessage.formatTemplate("name" to kind.displayName),
                            )
                        },
                        onRestore = {
                            runResourceFileAction(
                                action = { resourceFileUseCase.restoreBundled(kind, appState.customResourceFiles) },
                                successMessage = restoredMessage.formatTemplate("name" to kind.displayName),
                            )
                        },
                    )
                }
            }
            if (appState.customResourceFiles.isNotEmpty()) {
                item(key = "resource_custom_section") {
                    ResourceSectionTitle(stringResource(R.string.settings_resource_files_custom_section))
                }
            }
            appState.customResourceFiles.forEach { customFile ->
                item(key = "custom_resource_file_${customFile.id}") {
                    CustomResourceFileCard(
                        fileStatus = status.statusOf(customFile),
                        updating = updating,
                        onUpdate = ::updateCustomResourceFile,
                        onReplace = { file ->
                            runResourceFileAction(
                                action = {
                                    resourceFileUseCase.replaceCustom(file, appState.customResourceFiles)
                                },
                                successMessage = replacedMessage.formatTemplate("name" to file.name),
                            )
                        },
                        onEdit = { file ->
                            editCustomResourceFileNameState.setTextAndPlaceCursorAtEnd(file.name)
                            editCustomResourceFileUrlState.setTextAndPlaceCursorAtEnd(file.url)
                            editingCustomResourceFile = file
                        },
                        onDelete = { file ->
                            val remaining = appState.customResourceFiles
                                .filterNot { customFile -> customFile.id == file.id }
                            runResourceFileAction(
                                action = {
                                    resourceFileUseCase.deleteCustom(file, remaining)
                                },
                                successMessage = deletedMessage.formatTemplate("name" to file.name),
                                onSuccess = {
                                    updateAppState { state ->
                                        state.withRemovedManagedRuleSets(setOf(file.name))
                                            .copy(
                                                customResourceFiles =
                                                    state.customResourceFiles.filterNot {
                                                        customFile -> customFile.id == file.id
                                                    },
                                            )
                                    }
                                },
                            )
                        },
                    )
                }
            }
        }
        CustomResourceFileEditorSheet(
            show = showCustomResourceFileDialog.value,
            nameState = customResourceFileNameState,
            urlState = customResourceFileUrlState,
            reservedNames = customResourceFileReservedNames(),
            onDismissRequest = { showCustomResourceFileDialog.value = false },
            onValidationError = { error ->
                if (error == CustomResourceDraftError.InvalidSrsExtension) {
                    showResourceFileEditorError(customResourceFileNameSrsRequiredMessage)
                }
            },
            onSave = ::addCustomResourceFile,
        )
        CustomResourceFileEditorSheet(
            show = editingCustomResourceFile != null,
            nameState = editCustomResourceFileNameState,
            urlState = editCustomResourceFileUrlState,
            reservedNames = customResourceFileReservedNames(editingCustomResourceFile?.id),
            onDismissRequest = { editingCustomResourceFile = null },
            onValidationError = { error ->
                if (error == CustomResourceDraftError.InvalidSrsExtension) {
                    showResourceFileEditorError(customResourceFileNameSrsRequiredMessage)
                }
            },
            onSave = { name, url ->
                editingCustomResourceFile?.let { file -> editCustomResourceFile(file, name, url) } ?: false
            },
        )
        CustomResourceSourceEditorSheet(
            show = showCustomSourceEditor,
            geositeCategoryAdsAllUrlState = sourceGeositeCategoryAdsAllUrlState,
            geositeGoogleUrlState = sourceGeositeGoogleUrlState,
            geositeCnUrlState = sourceGeositeCnUrlState,
            geoipCnUrlState = sourceGeoipCnUrlState,
            directCidrIpv4UrlState = sourceDirectCidrIpv4UrlState,
            directCidrIpv6UrlState = sourceDirectCidrIpv6UrlState,
            onDismissRequest = { showCustomSourceEditor = false },
            onSave = {
                updateAppState { state ->
                    state.copy(
                        resourceFileSource = ResourceFileSourceCustom,
                        customResourceFileGeositeCategoryAdsAllUrl =
                            sourceGeositeCategoryAdsAllUrlState.text.toString().trim(),
                        customResourceFileGeositeGoogleUrl = sourceGeositeGoogleUrlState.text.toString().trim(),
                        customResourceFileGeositeCnUrl = sourceGeositeCnUrlState.text.toString().trim(),
                        customResourceFileGeoipCnUrl = sourceGeoipCnUrlState.text.toString().trim(),
                        customResourceFileDirectCidrIpv4Url = sourceDirectCidrIpv4UrlState.text.toString().trim(),
                        customResourceFileDirectCidrIpv6Url = sourceDirectCidrIpv6UrlState.text.toString().trim(),
                    )
                }
                showCustomSourceEditor = false
            },
        )
    }
}

@Composable
private fun ResourceSectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.fillMaxWidth().padding(start = 4.dp, top = 10.dp, bottom = 2.dp),
    )
}

private fun AppState.resourceFileUpdateOptions(): ResourceFileUpdateOptions {
    return ResourceFileUpdateOptions(
        useRunningProxy = proxyRunning,
        fallbackProxyPort = localProxyPort.toPortOrNull(),
        fallbackProxyUsername = localProxyUsername,
        fallbackProxyPassword = localProxyPassword,
    )
}

private fun ResourceFilesStatus.statusOf(customFile: CustomResourceFileState): CustomResourceFileStatus {
    return customResourceFiles.firstOrNull { fileStatus -> fileStatus.file.id == customFile.id }
        ?: CustomResourceFileStatus(file = customFile)
}
