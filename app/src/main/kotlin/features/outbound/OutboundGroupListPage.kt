// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.outbound

import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.geometry.Offset
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material3.DropdownMenu
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import ui.components.AsteriskScaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import ui.components.AsteriskTopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import app.DefaultOutboundSubscriptionUserAgent
import app.AppState
import app.selectableGroupDetourOutbounds
import app.LocalAppServices
import app.LocalAppStateStore
import app.LocalIsWideScreen
import app.LocalNavigator
import app.LocalUpdateAppState
import app.OutboundGroupState
import app.OutboundGroupUpdateStatus
import app.OutboundState
import app.SubscriptionInfo
import app.collectAppState
import features.importing.ImportOperation
import features.importing.ImportResultDetail
import features.importing.ImportResultDialog
import features.importing.ImportResultPresentation
import features.importing.ImportResultStatus
import features.importing.ImportSource
import features.importing.ImportStage
import features.importing.importFailureResultPresentation
import features.importing.reportImportFailure
import features.importing.readImportUtf8WithinLimit
import features.importing.sanitizeImportMessage
import features.importing.toImportResultPresentation
import features.logs.FailureLogContext
import engine.proxy.ProxyServiceResult
import features.settings.SettingsDropdownRow
import features.settings.SettingsSwitchRow
import features.subscription.SubscriptionSchedule
import features.subscription.SubscriptionInstallConfig
import features.subscription.parseSubscriptionDeepLink
import features.subscription.SubscriptionUserAgentOption
import features.subscription.SubscriptionUserAgentOptions
import features.subscription.parseSubscriptionSchedule
import features.subscription.resolveUserAgent
import features.subscription.subscriptionUserAgentOptionFor
import features.subscription.usecase.OutboundSubscriptionUpdateResult
import features.subscription.usecase.SubscriptionUpdateTrigger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import app.R
import sh.calvin.reorderable.ReorderableItem
import ui.components.AsteriskActionButton
import ui.components.AsteriskConfigApplyBanner
import ui.components.AsteriskModalBottomSheet
import ui.components.WarningConfirmDialog
import ui.components.localizedLabel
import ui.components.draggedCardShadow
import ui.components.rememberReorderPreview
import ui.components.longPressReorderDragHandle
import ui.components.rememberAsteriskReorderableLazyListState
import ui.components.verticalReorderScrollThresholdPadding
import ui.layout.pageContentPaddingWithCutout
import ui.layout.pageListPadding
import ui.theme.AsteriskMotion
import ui.theme.AsteriskShapeTokens
import utils.toReadableBytes
import utils.toReadableDateOrDash
import utils.toReadableDateTimeOrDash
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean
import features.home.HomeServiceOperation
import utils.disambiguateName
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import ui.icons.AsteriskIcons as Icons

@Composable
internal fun OutboundGroupListPage(
    padding: PaddingValues,
    createOnOpen: Boolean = false,
    onBack: (() -> Unit)? = null,
) {
    val stateStore = LocalAppStateStore.current
    val appState by stateStore.collectAppState()
    val updateAppState = LocalUpdateAppState.current
    val navigator = LocalNavigator.current
    val services = LocalAppServices.current
    val resources = LocalResources.current
    val context = LocalContext.current
    val isWideScreen = LocalIsWideScreen.current
    val scope = rememberCoroutineScope()
    var editorGroup by remember { mutableStateOf<OutboundGroupState?>(null) }
    var showGroupEditor by remember { mutableStateOf(createOnOpen) }
    var groupEditorSession by remember { mutableIntStateOf(0) }
    var savingGroupEditorSession by remember { mutableStateOf<Int?>(null) }
    var pendingDelete by remember { mutableStateOf<OutboundGroupState?>(null) }
    var deletingGroupId by remember { mutableStateOf<Int?>(null) }
    var enabledChangingGroupIds by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var syncingGroupIds by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var batchSyncJob by remember { mutableStateOf<Job?>(null) }
    var batchSyncProgress by remember { mutableStateOf<OutboundGroupBatchProgress?>(null) }
    var batchSyncCancelling by remember { mutableStateOf(false) }
    var batchSyncResults by remember {
        mutableStateOf<List<OutboundGroupBatchEntryPresentation>?>(null)
    }
    var importResultPresentation by remember {
        mutableStateOf<ImportResultPresentation?>(null)
    }
    // Set only when the dialog reports a failure for a single-group sync, so 重试
    // re-runs exactly the update that failed.
    var retrySyncGroupId by remember { mutableStateOf<Int?>(null) }
    // The apply shares the home screen's service operation state, so the power button and this
    // banner exclude each other and the spinner survives leaving and reopening the page.
    val serviceOperationState = services.homeServiceOperationState
    val applyingConfig = serviceOperationState.operation == HomeServiceOperation.Applying
    val serviceBusy = serviceOperationState.operation != HomeServiceOperation.Idle ||
        serviceOperationState.modeOperationInProgress
    // Work that must outlive this page (a sync started right before navigator.pop(), an
    // install the user walked away from) runs on the app scope. Its results reach this page's
    // dialogs only while the page is still composed, and fall back to a tip otherwise.
    val pageActive = remember { AtomicBoolean(true) }
    DisposableEffect(Unit) { onDispose { pageActive.set(false) } }
    var showAddMethodSheet by remember { mutableStateOf(false) }
    var addingFromQrCode by remember { mutableStateOf(false) }
    var addingFromFile by remember { mutableStateOf(false) }
    val applyFailedMessage = stringResource(R.string.outbound_group_config_apply_failed)
    val appliedMessage = stringResource(R.string.outbound_group_config_applied)
    val qrInvalidMessage = stringResource(R.string.outbound_group_add_qr_invalid)
    val addInstalledMessage = stringResource(R.string.outbound_group_add_installed)
    val addExistingMessage = stringResource(R.string.outbound_group_add_existing)
    val addFailedMessage = stringResource(R.string.outbound_group_add_failed)
    val addFileFailedMessage = stringResource(R.string.outbound_group_add_file_failed)
    val importFailedMessage = stringResource(R.string.outbound_group_sync_failed)
    val stateChangedMessage = stringResource(R.string.outbound_group_sync_failed)
    val subscriptionGroups = appState.outboundGroups.outboundSubscriptionGroups()

    fun openGroupEditor() {
        editorGroup = null
        groupEditorSession += 1
        showGroupEditor = true
    }

    /**
     * Shows [presentation] in this page's result dialog, or [fallbackMessage] as a tip when
     * the page has already been left (for example a sync started right before navigator.pop()).
     */
    suspend fun presentImportResult(
        presentation: ImportResultPresentation,
        fallbackMessage: String,
        retryGroupId: Int? = null,
    ) {
        if (pageActive.get()) {
            importResultPresentation = presentation
            retrySyncGroupId = retryGroupId
                ?.takeIf { presentation.status == ImportResultStatus.FAILURE }
        } else {
            services.tipNotifier.show(fallbackMessage)
        }
    }

    /**
     * Scans a subscription QR code and installs it through the same use case the
     * clash://, v2rayng:// and sing-box:// deep links use, so a scanned link and a
     * tapped link produce identical groups.
     */
    fun addFromQrCode() {
        if (addingFromQrCode) return
        addingFromQrCode = true
        services.appScope.launch(Dispatchers.Main.immediate) {
            try {
                val scanned = services.qrCodeScanner()?.trim().orEmpty()
                if (scanned.isBlank()) return@launch
                val config = subscriptionInstallConfigFromScan(scanned)
                if (config == null) {
                    services.tipNotifier.show(qrInvalidMessage)
                    return@launch
                }
                // The install matches an existing group by URL and re-syncs it instead.
                val existing = stateStore.state.value.outboundGroups.any { group -> group.url == config.url }
                when (val result = services.subscriptionInstallConfig.install(config)) {
                    is OutboundSubscriptionUpdateResult.Success,
                    is OutboundSubscriptionUpdateResult.Partial,
                    OutboundSubscriptionUpdateResult.NotModified,
                    -> services.tipNotifier.show(if (existing) addExistingMessage else addInstalledMessage)

                    is OutboundSubscriptionUpdateResult.Failed -> services.tipNotifier.showError(
                        result.error,
                        addFailedMessage,
                        FailureLogContext(
                            operation = "outbound_group_add_qr",
                            source = ImportSource.QR_CODE.logValue,
                            stage = result.stage.logValue,
                        ),
                    )

                    is OutboundSubscriptionUpdateResult.Cancelled -> Unit
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                services.tipNotifier.showError(error, addFailedMessage)
            } finally {
                addingFromQrCode = false
            }
        }
    }

    /**
     * Imports a local sing-box JSON (or Mihomo YAML, or a list of proxy links) as a new
     * local group. The group and its outbounds are planned into one state and committed
     * once, so a file that parses badly cannot leave an empty group behind.
     */
    fun addFromFile() {
        if (addingFromFile) return
        addingFromFile = true
        val appContext = context.applicationContext
        services.appScope.launch(Dispatchers.Main.immediate) {
            var stage = ImportStage.READ
            try {
                val uri = services.importFilePicker() ?: return@launch
                val file = withContext(Dispatchers.IO) { appContext.readGroupImportFile(uri) }
                stage = ImportStage.PARSE
                val parsed = withContext(Dispatchers.Default) {
                    parseOutboundImportContent(file.content)
                }
                if (parsed.accepted.isEmpty()) {
                    presentImportResult(
                        parsed.toImportResultPresentation(committed = false),
                        addFileFailedMessage,
                    )
                    return@launch
                }
                val snapshot = stateStore.state.value
                val groupName = disambiguateName(
                    base = file.groupName,
                    takenNames = snapshot.outboundGroups.map(OutboundGroupState::name),
                ) { name, ordinal ->
                    resources.getString(R.string.outbound_group_name_duplicate, name, ordinal)
                }
                val withGroup = snapshot.withSavedOutboundGroup(
                    OutboundGroupState(id = 0, name = groupName),
                )
                val group = withGroup.outboundGroups.last()
                val plan = withContext(Dispatchers.Default) {
                    withGroup.planOutboundImport(
                        groupId = group.id,
                        parsed = parsed,
                        replaceGroup = false,
                        strict = false,
                    )
                }
                if (!plan.committed) {
                    presentImportResult(
                        plan.outcome.toImportResultPresentation(committed = false),
                        addFileFailedMessage,
                    )
                    return@launch
                }
                // persistImport validates the candidate itself; validating here as well would
                // double the most expensive step and widen the window for a Conflict.
                stage = ImportStage.COMMIT
                when (
                    val persist = services.outboundRepository.persistImport(snapshot, plan.state)
                ) {
                    OutboundCommandResult.ImportPersisted -> {
                        val presentation = plan.outcome.toImportResultPresentation(committed = true)
                        val successMessage = resources.getQuantityString(
                            R.plurals.outbound_import_success,
                            plan.outcome.accepted.size,
                            plan.outcome.accepted.size,
                        )
                        if (presentation.showDialog) {
                            presentImportResult(presentation, successMessage)
                        } else {
                            services.tipNotifier.show(successMessage)
                        }
                    }

                    OutboundCommandResult.Conflict -> {
                        reportImportFailure(
                            operation = ImportOperation.OUTBOUND,
                            source = ImportSource.FILE,
                            stage = ImportStage.COMMIT,
                        )
                        presentImportResult(
                            importFailureResultPresentation(stateChangedMessage),
                            stateChangedMessage,
                        )
                    }

                    is OutboundCommandResult.Invalid -> {
                        reportImportFailure(
                            operation = ImportOperation.OUTBOUND,
                            source = ImportSource.FILE,
                            stage = ImportStage.VALIDATE,
                            error = persist.error,
                        )
                        presentImportResult(
                            importFailureResultPresentation(persist.error.message ?: addFileFailedMessage),
                            addFileFailedMessage,
                        )
                    }

                    is OutboundCommandResult.PersistenceFailed -> {
                        reportImportFailure(
                            operation = ImportOperation.OUTBOUND,
                            source = ImportSource.FILE,
                            stage = ImportStage.COMMIT,
                            error = persist.error,
                        )
                        presentImportResult(
                            importFailureResultPresentation(persist.error.message ?: addFileFailedMessage),
                            addFileFailedMessage,
                        )
                    }

                    else -> error("Unexpected outbound import result: $persist")
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                reportImportFailure(
                    operation = ImportOperation.OUTBOUND,
                    source = ImportSource.FILE,
                    stage = stage,
                    error = error,
                )
                services.tipNotifier.showError(error, addFileFailedMessage)
            } finally {
                addingFromFile = false
            }
        }
    }

    /**
     * Applies the pending outbound changes to the running core: an in-place reload in VPN
     * mode, a supervised restart in ROOT modes. Runs on the app scope like the home power
     * button, so leaving the page cannot cancel it halfway through a restart.
     */
    fun applyPendingConfig() {
        if (serviceBusy) return
        val snapshot = stateStore.state.value
        serviceOperationState.operation = HomeServiceOperation.Applying
        services.appScope.launch(Dispatchers.Main.immediate) {
            try {
                val result = services.proxyServiceUseCase.applyConfiguration(
                    snapshot,
                    services.singBoxRuntime::reloadVpnConfiguration,
                )
                when (result) {
                    is ProxyServiceResult.Success -> {
                        updateAppState { state ->
                            state.copy(
                                proxyRunning = result.proxyRunning,
                                localProxyPort = result.appState?.localProxyPort
                                    ?: state.localProxyPort,
                                singBoxControlPort = result.appState?.singBoxControlPort
                                    ?: state.singBoxControlPort,
                                pendingConfigRestart = false,
                            )
                        }
                        if (result.proxyRunning) services.tipNotifier.show(appliedMessage)
                    }

                    is ProxyServiceResult.Failed -> {
                        // A failed restart may already have stopped the core, so read back
                        // what is really running instead of assuming either way.
                        val running = services.proxyServiceUseCase.isRunning(snapshot)
                        if (running != null) {
                            updateAppState { state -> state.copy(proxyRunning = running) }
                        }
                        services.tipNotifier.showError(result.error, applyFailedMessage)
                    }
                }
            } finally {
                serviceOperationState.operation = HomeServiceOperation.Idle
            }
        }
    }

    suspend fun updateGroupSubscription(
        requestedGroup: OutboundGroupState,
        trigger: SubscriptionUpdateTrigger = SubscriptionUpdateTrigger.MANUAL,
        onStage: (ImportStage) -> Unit = {},
    ): OutboundGroupUpdateResult {
        return when (
            val result = services.outboundSubscriptionUpdater.update(
                groupId = requestedGroup.id,
                trigger = trigger,
                onStage = onStage,
            )
        ) {
            is OutboundSubscriptionUpdateResult.Success ->
                OutboundGroupUpdateResult.Success(
                    outboundCount = result.outcome.accepted.size,
                    presentation = result.outcome.toImportResultPresentation(committed = true),
                )
            is OutboundSubscriptionUpdateResult.Partial ->
                OutboundGroupUpdateResult.Success(
                    outboundCount = result.outcome.accepted.size,
                    presentation = result.outcome.toImportResultPresentation(committed = true),
                )
            OutboundSubscriptionUpdateResult.NotModified ->
                OutboundGroupUpdateResult.Success(
                    outboundCount = 0,
                    notModified = true,
                )
            is OutboundSubscriptionUpdateResult.Failed ->
                OutboundGroupUpdateResult.Failure(
                    stage = result.stage,
                    error = result.error,
                    presentation = result.outcome?.toImportResultPresentation(committed = false),
                )
            is OutboundSubscriptionUpdateResult.Cancelled ->
                OutboundGroupUpdateResult.Failure(
                    stage = ImportStage.COMMIT,
                    error = IllegalStateException(result.reason),
                )
        }
    }

    fun syncGroup(group: OutboundGroupState) {
        if (
            group.url.isBlank() ||
            group.id in syncingGroupIds ||
            batchSyncJob?.isActive == true
        ) {
            return
        }
        syncingGroupIds += group.id
        // App scope: the sync that follows saving a new group must survive navigator.pop().
        services.appScope.launch(Dispatchers.Main.immediate) {
            try {
                when (val result = updateGroupSubscription(group)) {
                    is OutboundGroupUpdateResult.Success -> {
                        val successMessage = resources.getQuantityString(
                            R.plurals.outbound_import_success,
                            result.outboundCount,
                            result.outboundCount,
                        )
                        if (result.presentation?.showDialog == true) {
                            presentImportResult(
                                result.presentation,
                                if (result.presentation.status == ImportResultStatus.FAILURE) {
                                    importFailedMessage
                                } else {
                                    successMessage
                                },
                                retryGroupId = group.id,
                            )
                        } else if (result.notModified) {
                            services.tipNotifier.show(
                                resources.getString(
                                    R.string.outbound_group_sync_not_modified,
                                ),
                            )
                        } else {
                            services.tipNotifier.show(successMessage)
                        }
                    }
                    is OutboundGroupUpdateResult.Failure -> {
                        reportImportFailure(
                            operation = ImportOperation.OUTBOUND_SUBSCRIPTION,
                            source = ImportSource.SUBSCRIPTION,
                            stage = result.stage,
                            error = result.error,
                        )
                        presentImportResult(
                            result.presentation
                                ?: importFailureResultPresentation(
                                    result.error.message ?: importFailedMessage,
                                ),
                            importFailedMessage,
                        )
                        // Any Failure is worth retrying, whatever status its outcome reports.
                        if (pageActive.get()) retrySyncGroupId = group.id
                    }
                }
            } finally {
                syncingGroupIds -= group.id
            }
        }
    }

    /**
     * Saves the editor's group and, when it points at a new or changed subscription URL,
     * fetches it straight away. META does the same on save: without it a freshly added
     * subscription stays empty until the user reopens the card menu and runs 更新.
     *
     * Declared after [syncGroup] because local functions must be declared before use.
     */
    fun saveGroup(group: OutboundGroupState) {
        val session = groupEditorSession
        if (savingGroupEditorSession != null) return
        val expected = editorGroup
        savingGroupEditorSession = session
        scope.launch {
            try {
                when (val result = services.outboundRepository.saveGroup(expected, group)) {
                    is OutboundCommandResult.GroupSaved -> {
                        if (groupEditorSession == session) {
                            showGroupEditor = false
                            if (createOnOpen) navigator.pop()
                        }
                        val saved = result.group
                        if (saved.url.isNotBlank() && expected?.url != saved.url) {
                            syncGroup(saved)
                        }
                    }
                    OutboundCommandResult.Conflict ->
                        services.tipNotifier.show(stateChangedMessage)
                    is OutboundCommandResult.Invalid ->
                        services.tipNotifier.show(importFailedMessage)
                    is OutboundCommandResult.PersistenceFailed ->
                        services.tipNotifier.showError(
                            result.error,
                            importFailedMessage,
                            FailureLogContext(operation = "outbound_group_save", stage = "persist"),
                        )
                    OutboundCommandResult.Deleted,
                    OutboundCommandResult.GroupDeleted,
                    OutboundCommandResult.GroupEnabledChanged,
                    OutboundCommandResult.GroupsReordered,
                    OutboundCommandResult.ImportPersisted,
                    OutboundCommandResult.Reordered,
                    is OutboundCommandResult.Saved,
                    -> error("Unexpected outbound group save result: $result")
                }
            } finally {
                if (savingGroupEditorSession == session) savingGroupEditorSession = null
            }
        }
    }

    fun syncAllSubscriptions() {
        val groups = stateStore.state.value.outboundGroups.outboundSubscriptionGroups()
        if (
            groups.isEmpty() ||
            syncingGroupIds.isNotEmpty() ||
            batchSyncJob?.isActive == true
        ) {
            return
        }

        val groupIds = groups.mapTo(mutableSetOf()) { it.id }
        syncingGroupIds += groupIds
        batchSyncCancelling = false
        batchSyncResults = null
        var completedCount = 0
        var updatedCount = 0
        var failedCount = 0
        val entryResults = mutableListOf<OutboundGroupBatchEntryPresentation>()
        lateinit var syncJob: Job
        syncJob = scope.launch(start = CoroutineStart.LAZY) {
            try {
                updateOutboundGroupsSequentially(
                    groups = groups,
                    updateGroup = { group, onStage ->
                        updateGroupSubscription(
                            requestedGroup = group,
                            trigger = SubscriptionUpdateTrigger.BATCH,
                            onStage = onStage,
                        )
                    },
                    onGroupStarted = { group, currentIndex, totalCount ->
                        batchSyncProgress = OutboundGroupBatchProgress(
                            groupId = group.id,
                            groupName = group.name,
                            currentIndex = currentIndex,
                            totalCount = totalCount,
                            completedCount = completedCount,
                            stage = ImportStage.DOWNLOAD,
                        )
                    },
                    onStage = { group, stage ->
                        batchSyncProgress = batchSyncProgress
                            ?.takeIf { it.groupId == group.id }
                            ?.copy(stage = stage)
                    },
                    onGroupCompleted = { group, updateResult ->
                        completedCount += 1
                        entryResults += updateResult.toBatchEntryPresentation(group.name)
                        when (updateResult) {
                            is OutboundGroupUpdateResult.Success -> updatedCount += 1
                            is OutboundGroupUpdateResult.Failure -> {
                                failedCount += 1
                                reportImportFailure(
                                    operation = ImportOperation.OUTBOUND_SUBSCRIPTION,
                                    source = ImportSource.SUBSCRIPTION,
                                    stage = updateResult.stage,
                                    error = updateResult.error,
                                )
                            }
                        }
                        batchSyncProgress = batchSyncProgress?.copy(
                            completedCount = completedCount,
                        )
                    },
                )
                batchSyncResults = entryResults.toList()
            } catch (_: CancellationException) {
                val skippedCount = groups.size - completedCount
                withContext(NonCancellable) {
                    services.tipNotifier.show(
                        resources.getString(
                            R.string.outbound_group_sync_all_cancelled,
                            updatedCount,
                            failedCount,
                            skippedCount,
                        ),
                    )
                }
            } finally {
                syncingGroupIds -= groupIds
                batchSyncProgress = null
                batchSyncCancelling = false
                if (batchSyncJob === syncJob) {
                    batchSyncJob = null
                }
            }
        }
        batchSyncJob = syncJob
        syncJob.start()
    }

    AsteriskScaffold(
        topBar = {
            AsteriskTopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.outbound_group_management))
                        Text(
                            text = pluralStringResource(
                                R.plurals.outbound_group_count,
                                appState.outboundGroups.size,
                                appState.outboundGroups.size,
                            ),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    onBack?.let { navigateBack ->
                        IconButton(onClick = navigateBack) {
                            Icon(
                                Icons.AutoMirrored.Rounded.ArrowBack,
                                stringResource(R.string.common_back),
                            )
                        }
                    }
                },
                actions = {
                    IconButton(
                        onClick = ::syncAllSubscriptions,
                        enabled = subscriptionGroups.isNotEmpty() &&
                            syncingGroupIds.isEmpty() &&
                            batchSyncJob?.isActive != true,
                    ) {
                        Icon(
                            Icons.Rounded.Sync,
                            stringResource(R.string.outbound_group_sync_all),
                        )
                    }
                    IconButton(
                        onClick = { showAddMethodSheet = true },
                    ) {
                        Icon(Icons.Rounded.Add, stringResource(R.string.common_add))
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
        val listContentPadding = pageListPadding(contentPadding)
        val layoutDirection = LocalLayoutDirection.current
        /*
         * The restart banner is pinned above the list rather than added as list item 0: as an item
         * it inherited the list's scroll anchor, so when it appeared while the list was scrolled it
         * was laid out above the viewport and hidden behind the top bar. Pinning it also keeps the
         * groups at index 0, which the reorder drag mapping below relies on.
         */
        val pinnedRestartBanner = appState.pendingConfigRestart
        val groupListPadding = if (pinnedRestartBanner) {
            // The banner carries the top bar inset, so the list only keeps the inter-card gap.
            pageListPadding(
                PaddingValues(
                    top = 0.dp,
                    start = contentPadding.calculateStartPadding(layoutDirection),
                    end = contentPadding.calculateEndPadding(layoutDirection),
                    bottom = contentPadding.calculateBottomPadding(),
                ),
                topExtra = 12.dp,
            )
        } else {
            listContentPadding
        }
        val listState = rememberLazyListState()
        val preview = rememberReorderPreview(
            appState.outboundGroups,
            OutboundGroupState::id,
            commitScope = scope,
        ) { ids ->
            when (val result = services.outboundRepository.reorderGroups(ids)) {
                OutboundCommandResult.GroupsReordered -> true
                OutboundCommandResult.Conflict -> {
                    services.tipNotifier.show(stateChangedMessage)
                    false
                }
                is OutboundCommandResult.PersistenceFailed -> {
                    services.tipNotifier.showError(
                        result.error,
                        importFailedMessage,
                        FailureLogContext(operation = "outbound_group_reorder", stage = "persist"),
                    )
                    false
                }
                is OutboundCommandResult.Invalid -> {
                    services.tipNotifier.show(importFailedMessage)
                    false
                }
                else -> error("Unexpected outbound group reorder result: $result")
            }
        }
        val displayedGroups = preview.items
        // One pass over the outbounds instead of one per card on every recomposition; a large
        // subscription makes the per-card count O(groups x outbounds) per frame.
        val outboundCountsByGroup = remember(appState.outbounds) {
            appState.outbounds.groupingBy(OutboundState::groupId).eachCount()
        }
        val reorderableState = rememberAsteriskReorderableLazyListState(
            lazyListState = listState,
            itemCount = displayedGroups.size,
            scrollThresholdPadding = verticalReorderScrollThresholdPadding(groupListPadding),
            onMove = preview.onMove,
        )
        Column(modifier = Modifier.fillMaxSize()) {
            if (pinnedRestartBanner) {
                AsteriskConfigApplyBanner(
                    title = stringResource(R.string.outbound_group_config_apply_title),
                    summary = stringResource(R.string.outbound_group_config_apply_summary),
                    actionLabel = stringResource(R.string.outbound_group_config_apply_action),
                    applying = applyingConfig,
                    enabled = !serviceBusy,
                    onApply = { applyPendingConfig() },
                    modifier = Modifier.padding(
                        start = listContentPadding.calculateStartPadding(layoutDirection),
                        end = listContentPadding.calculateEndPadding(layoutDirection),
                        top = listContentPadding.calculateTopPadding(),
                    ),
                )
            }
            LazyColumn(
                modifier = Modifier.weight(1f),
                state = listState,
                contentPadding = groupListPadding,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (displayedGroups.isEmpty()) {
                    item(key = "empty") {
                        OutboundGroupEmptyState(
                            onAdd = { showAddMethodSheet = true },
                        )
                    }
                }
                items(
                    items = displayedGroups,
                    key = OutboundGroupState::id,
                    contentType = { "outbound-group" },
                ) { group ->
                    ReorderableItem(
                        state = reorderableState.reorderableState,
                        key = group.id,
                        modifier = Modifier.fillMaxWidth(),
                        animateItemModifier = Modifier.animateItem(),
                    ) { isDragging ->
                        OutboundGroupCard(
                            group = group,
                            outboundCount = outboundCountsByGroup[group.id] ?: 0,
                            syncing = group.id in syncingGroupIds,
                            isDragging = isDragging,
                            enabledChangeBusy = group.id in enabledChangingGroupIds,
                            onEnabledChange = { enabled ->
                                val groupId = group.id
                                if (groupId in enabledChangingGroupIds) return@OutboundGroupCard
                                enabledChangingGroupIds += groupId
                                scope.launch {
                                    try {
                                        when (
                                            val result = services.outboundRepository.setGroupEnabled(
                                                groupId,
                                                enabled,
                                            )
                                        ) {
                                            OutboundCommandResult.GroupEnabledChanged -> Unit
                                            OutboundCommandResult.Conflict ->
                                                services.tipNotifier.show(stateChangedMessage)
                                            is OutboundCommandResult.PersistenceFailed ->
                                                services.tipNotifier.showError(
                                                    result.error,
                                                    importFailedMessage,
                                                    FailureLogContext(
                                                        operation = "outbound_group_enabled",
                                                        stage = "persist",
                                                    ),
                                                )
                                            is OutboundCommandResult.Invalid ->
                                                services.tipNotifier.show(importFailedMessage)
                                            OutboundCommandResult.Deleted,
                                            OutboundCommandResult.GroupDeleted,
                                            OutboundCommandResult.GroupsReordered,
                                            OutboundCommandResult.ImportPersisted,
                                            OutboundCommandResult.Reordered,
                                            is OutboundCommandResult.Saved,
                                            is OutboundCommandResult.GroupSaved,
                                            -> error("Unexpected outbound group enable result: $result")
                                        }
                                    } finally {
                                        enabledChangingGroupIds -= groupId
                                    }
                                }
                            },
                            onSync = { syncGroup(group) },
                            onEdit = {
                                editorGroup = group
                                groupEditorSession += 1
                                showGroupEditor = true
                            },
                            onDelete = { pendingDelete = group },
                            modifier = Modifier
                                .fillMaxWidth()
                                .longPressReorderDragHandle(
                                    scope = this,
                                    enabled = displayedGroups.size > 1,
                                    state = reorderableState,
                                    onDragStarted = preview.onDragStarted,
                                    onDragStopped = preview.onDragStopped,
                                ),
                        )
                    }
                }
            }
        }
        OutboundGroupAddMethodSheet(
            show = showAddMethodSheet,
            onDismissRequest = { showAddMethodSheet = false },
            onScanQrCode = {
                showAddMethodSheet = false
                addFromQrCode()
            },
            onEnterManually = {
                showAddMethodSheet = false
                openGroupEditor()
            },
            onImportFile = {
                showAddMethodSheet = false
                addFromFile()
            },
        )
        OutboundGroupEditorSheet(
            show = showGroupEditor,
            appState = appState,
            group = editorGroup,
            editorSession = groupEditorSession,
            busy = savingGroupEditorSession == groupEditorSession,
            onDismissRequest = {
                if (savingGroupEditorSession != groupEditorSession) {
                    showGroupEditor = false
                    if (createOnOpen) navigator.pop()
                }
            },
            onSave = ::saveGroup,
        )
    }

    batchSyncProgress?.let { progress ->
        OutboundGroupBatchProgressDialog(
            progress = progress,
            cancelling = batchSyncCancelling,
            onCancel = {
                batchSyncCancelling = true
                batchSyncJob?.cancel()
            },
        )
    }

    WarningConfirmDialog(
        show = pendingDelete != null,
        title = stringResource(R.string.outbound_group_delete_title),
        summary = stringResource(
            R.string.outbound_group_delete_message,
            pendingDelete?.name.orEmpty(),
        ),
        dismissText = stringResource(R.string.common_cancel),
        confirmText = stringResource(R.string.common_delete),
        onDismissRequest = { pendingDelete = null },
        onConfirm = {
            val groupId = pendingDelete?.id ?: return@WarningConfirmDialog
            if (deletingGroupId != null) return@WarningConfirmDialog
            deletingGroupId = groupId
            scope.launch {
                try {
                    when (val result = services.outboundRepository.deleteGroup(groupId)) {
                        OutboundCommandResult.GroupDeleted -> {
                            if (pendingDelete?.id == groupId) pendingDelete = null
                        }
                        OutboundCommandResult.Conflict ->
                            services.tipNotifier.show(stateChangedMessage)
                        is OutboundCommandResult.PersistenceFailed ->
                            services.tipNotifier.showError(
                                result.error,
                                importFailedMessage,
                                FailureLogContext(
                                    operation = "outbound_group_delete",
                                    stage = "persist",
                                ),
                            )
                        is OutboundCommandResult.Invalid ->
                            services.tipNotifier.show(importFailedMessage)
                        OutboundCommandResult.Deleted,
                        OutboundCommandResult.GroupEnabledChanged,
                        OutboundCommandResult.GroupsReordered,
                        OutboundCommandResult.ImportPersisted,
                        OutboundCommandResult.Reordered,
                        is OutboundCommandResult.Saved,
                        is OutboundCommandResult.GroupSaved,
                        -> error("Unexpected outbound group delete result: $result")
                    }
                } finally {
                    if (deletingGroupId == groupId) deletingGroupId = null
                }
            }
        },
        busy = deletingGroupId != null,
    )
    importResultPresentation?.let { presentation ->
        ImportResultDialog(
            presentation = presentation,
            onDismissRequest = {
                importResultPresentation = null
                retrySyncGroupId = null
            },
            onRetry = retrySyncGroupId?.let { groupId ->
                {
                    importResultPresentation = null
                    retrySyncGroupId = null
                    appState.outboundGroups
                        .firstOrNull { group -> group.id == groupId }
                        ?.let(::syncGroup)
                }
            },
        )
    }
    batchSyncResults?.let { results ->
        OutboundGroupBatchResultDialog(
            results = results,
            onDismissRequest = { batchSyncResults = null },
        )
    }
}

private data class OutboundGroupBatchEntryPresentation(
    val groupName: String,
    val succeeded: Boolean,
    val notModified: Boolean,
    val importedCount: Int,
    val skippedCount: Int,
    val duplicateCount: Int,
    val details: List<ImportResultDetail>,
)

private fun OutboundGroupUpdateResult.toBatchEntryPresentation(
    groupName: String,
): OutboundGroupBatchEntryPresentation = when (this) {
    is OutboundGroupUpdateResult.Success -> OutboundGroupBatchEntryPresentation(
        groupName = groupName,
        succeeded = true,
        notModified = notModified,
        importedCount = presentation?.acceptedCount ?: outboundCount,
        skippedCount = presentation?.skippedCount ?: 0,
        duplicateCount = presentation?.duplicateCount ?: 0,
        details = presentation?.details.orEmpty(),
    )
    is OutboundGroupUpdateResult.Failure -> OutboundGroupBatchEntryPresentation(
        groupName = groupName,
        succeeded = false,
        notModified = false,
        importedCount = presentation?.acceptedCount ?: 0,
        skippedCount = presentation?.skippedCount ?: 0,
        duplicateCount = presentation?.duplicateCount ?: 0,
        details = presentation?.details ?: listOf(
            ImportResultDetail(
                sourceIndex = null,
                message = sanitizeImportMessage(
                    error.message ?: "Subscription update failed",
                ),
                isError = true,
            ),
        ),
    )
}

@Composable
private fun OutboundGroupBatchResultDialog(
    results: List<OutboundGroupBatchEntryPresentation>,
    onDismissRequest: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = {
            Text(stringResource(R.string.outbound_group_sync_all_result_title))
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                results.forEach { result ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = result.groupName,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = when {
                                !result.succeeded -> stringResource(
                                    R.string.outbound_group_sync_all_result_failed,
                                )
                                result.notModified -> stringResource(
                                    R.string.outbound_group_sync_not_modified,
                                )
                                else -> stringResource(
                                    R.string.import_result_summary,
                                    result.importedCount,
                                    result.skippedCount,
                                    result.duplicateCount,
                                )
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (result.succeeded) {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else {
                                MaterialTheme.colorScheme.error
                            },
                        )
                        if (!result.succeeded) {
                            Text(
                                text = stringResource(
                                    R.string.import_result_summary,
                                    result.importedCount,
                                    result.skippedCount,
                                    result.duplicateCount,
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        result.details.forEach { detail ->
                            Text(
                                text = detail.message,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (detail.isError) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismissRequest) {
                Text(stringResource(R.string.common_close))
            }
        },
    )
}

@Composable
private fun OutboundGroupBatchProgressDialog(
    progress: OutboundGroupBatchProgress,
    cancelling: Boolean,
    onCancel: () -> Unit,
) {
    val stageText = stringResource(
        when (progress.stage) {
            ImportStage.READ,
            ImportStage.DOWNLOAD,
            -> R.string.outbound_group_sync_stage_downloading
            ImportStage.DECRYPT -> R.string.outbound_group_sync_stage_decrypting
            ImportStage.VERIFY -> R.string.outbound_group_sync_stage_verifying
            ImportStage.PARSE -> R.string.outbound_group_sync_stage_parsing
            ImportStage.VALIDATE -> R.string.outbound_group_sync_stage_validating
            ImportStage.COMMIT -> R.string.outbound_group_sync_stage_committing
        },
    )
    AlertDialog(
        onDismissRequest = {},
        icon = {
            CircularProgressIndicator(
                modifier = Modifier.size(30.dp),
                strokeWidth = 3.dp,
            )
        },
        title = {
            Text(stringResource(R.string.outbound_group_sync_all_progress_title))
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = progress.groupName,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stageText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LinearProgressIndicator(
                    progress = { progress.fraction },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = stringResource(
                        R.string.outbound_group_sync_all_progress,
                        progress.currentIndex,
                        progress.totalCount,
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            AsteriskActionButton(
                text = stringResource(R.string.common_cancel),
                icon = Icons.Rounded.Close,
                enabled = !cancelling,
                onClick = onCancel,
            )
        },
    )
}

/**
 * Reads a picked file and derives a group name from it: the display name without its
 * extension, falling back to the URI's last segment so a file provider that publishes no
 * name still yields a usable group.
 */
private data class GroupImportFile(
    val content: String,
    val groupName: String,
)

private fun Context.readGroupImportFile(uri: Uri): GroupImportFile {
    val displayName = runCatching {
        contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (column >= 0 && cursor.moveToFirst()) cursor.getString(column) else null
        }
    }.getOrNull()
        ?.takeIf(String::isNotBlank)
        ?: uri.lastPathSegment
    val content = contentResolver.openInputStream(uri)?.use { input ->
        input.readImportUtf8WithinLimit()
    } ?: throw IllegalArgumentException("Unable to open the import file")
    require(content.isNotBlank()) { "The import file is empty" }
    val groupName = displayName
        ?.substringBeforeLast('.', missingDelimiterValue = displayName)
        ?.trim()
        ?.takeIf(String::isNotEmpty)
        ?: "Imported"
    return GroupImportFile(content = content, groupName = groupName)
}

/**
 * Turns scanned QR content into an install request. Hosted deep links are accepted as
 * they are; a bare http(s) link is treated as a plain sing-box subscription.
 * Anything else (a single node link, free text) is rejected.
 */
internal fun subscriptionInstallConfigFromScan(raw: String): SubscriptionInstallConfig? {
    parseSubscriptionDeepLink(raw)?.let { return it }
    val trimmed = raw.trim()
    if (trimmed.isBlank() || trimmed.any(Char::isWhitespace)) return null
    val uri = runCatching { URI(trimmed) }.getOrNull() ?: return null
    if (uri.scheme?.lowercase() !in setOf("http", "https") || uri.host.isNullOrBlank()) return null
    return SubscriptionInstallConfig(
        name = "",
        url = trimmed,
        userAgent = DefaultOutboundSubscriptionUserAgent,
    )
}

@Composable
private fun OutboundGroupAddMethodSheet(
    show: Boolean,
    onDismissRequest: () -> Unit,
    onScanQrCode: () -> Unit,
    onEnterManually: () -> Unit,
    onImportFile: () -> Unit,
) {
    AsteriskModalBottomSheet(
        show = show,
        onDismissRequest = onDismissRequest,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = stringResource(R.string.outbound_group_add_method_title),
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
            )
            OutboundGroupAddMethodOption(
                title = stringResource(R.string.outbound_group_add_from_qr),
                summary = stringResource(R.string.outbound_group_add_from_qr_summary),
                icon = Icons.Rounded.QrCodeScanner,
                onClick = onScanQrCode,
            )
            OutboundGroupAddMethodOption(
                title = stringResource(R.string.outbound_group_add_from_file),
                summary = stringResource(R.string.outbound_group_add_from_file_summary),
                icon = Icons.Rounded.FileDownload,
                onClick = onImportFile,
            )
            OutboundGroupAddMethodOption(
                title = stringResource(R.string.outbound_group_add_manually),
                summary = stringResource(R.string.outbound_group_add_manually_summary),
                icon = Icons.Rounded.Edit,
                onClick = onEnterManually,
            )
        }
    }
}

@Composable
private fun OutboundGroupAddMethodOption(
    title: String,
    summary: String,
    icon: ImageVector,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 76.dp),
        shape = AsteriskShapeTokens.InnerContainer,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                shape = AsteriskShapeTokens.SmallContainer,
                color = MaterialTheme.colorScheme.secondaryContainer,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.padding(10.dp),
                )
            }
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
        }
    }
}

@Composable
internal fun OutboundGroupEmptyState(onAdd: () -> Unit) {
    AnimatedVisibility(
        visible = true,
        enter = AsteriskMotion.fadeEnter(AsteriskMotion.effects()),
        exit = AsteriskMotion.fadeExit(AsteriskMotion.effects()),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 72.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                Icons.Rounded.Folder,
                contentDescription = null,
                modifier = Modifier.size(42.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(R.string.outbound_group_empty),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                stringResource(R.string.outbound_group_empty_summary),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.size(4.dp))
            AsteriskActionButton(
                text = stringResource(R.string.outbound_group_add),
                icon = Icons.Rounded.Add,
                onClick = onAdd,
            )
        }
    }
}

@Composable
internal fun OutboundGroupCard(
    group: OutboundGroupState,
    outboundCount: Int,
    syncing: Boolean,
    isDragging: Boolean,
    enabledChangeBusy: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onSync: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val containerColor by animateColorAsState(
        targetValue = if (isDragging) {
            MaterialTheme.colorScheme.surfaceContainerHigh
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        },
        animationSpec = AsteriskMotion.effects(),
        label = "outbound-group-card-color",
    )
    val scale by animateFloatAsState(
        targetValue = if (isDragging) 1.025f else 1f,
        animationSpec = AsteriskMotion.fastSpatial(),
        label = "outbound-group-card-scale",
    )
    val shadowAlpha by animateFloatAsState(
        targetValue = if (isDragging) 1f else 0f,
        animationSpec = AsteriskMotion.fastEffects(),
        label = "outbound-group-card-shadow",
    )
    var showUpdateInfo by remember(group.id) { mutableStateOf(false) }
    var menuExpanded by remember(group.id) { mutableStateOf(false) }
    var infoAnchorPosition by remember(group.id) { mutableStateOf<Offset?>(null) }
    LaunchedEffect(syncing, isDragging, enabledChangeBusy, group) {
        showUpdateInfo = false
    }
    Card(
        onClick = { showUpdateInfo = false; onEdit() },
        enabled = !enabledChangeBusy,
        modifier = modifier
            .fillMaxWidth()
            .zIndex(if (isDragging) 1f else 0f)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .draggedCardShadow(
                alpha = shadowAlpha,
                color = MaterialTheme.colorScheme.primary,
                cornerRadius = AsteriskShapeTokens.ListCardRadius,
            )
            .animateContentSize(animationSpec = AsteriskMotion.contentSize()),
        shape = AsteriskShapeTokens.ListCard,
        colors = CardDefaults.cardColors(containerColor = containerColor),
    ) {
        val status = group.subscriptionStatusPresentation()
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, top = 14.dp, end = 8.dp, bottom = 16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = group.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = pluralStringResource(R.plurals.outbound_count, outboundCount, outboundCount),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if ((group.url.isNotBlank() && status.status != OutboundGroupUpdateStatus.NEVER) ||
                            group.subscriptionInfo.expireAtSeconds > 0L) {
                            val statusColor = when (status.status) {
                                OutboundGroupUpdateStatus.FAILED -> MaterialTheme.colorScheme.error
                                OutboundGroupUpdateStatus.PARTIAL -> MaterialTheme.colorScheme.tertiary
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            }
                            val statusLabel = stringResource(when (status.status) {
                                OutboundGroupUpdateStatus.FAILED -> R.string.outbound_group_status_failed
                                OutboundGroupUpdateStatus.PARTIAL -> R.string.outbound_group_status_partial
                                OutboundGroupUpdateStatus.NOT_MODIFIED -> R.string.outbound_group_status_not_modified
                                OutboundGroupUpdateStatus.NEVER -> R.string.outbound_group_subscription_info
                                else -> R.string.outbound_group_update_details
                            })
                            val infoIconSize = with(LocalDensity.current) {
                                MaterialTheme.typography.bodySmall.fontSize.toDp()
                            }
                            Box(
                                modifier = Modifier.onGloballyPositioned { coordinates ->
                                    val position = coordinates.positionInWindow()
                                    if (infoAnchorPosition != null && infoAnchorPosition != position) {
                                        showUpdateInfo = false
                                    }
                                    infoAnchorPosition = position
                                },
                            ) {
                                IconButton(
                                    onClick = { showUpdateInfo = !showUpdateInfo },
                                    modifier = Modifier.size(width = 24.dp, height = 20.dp),
                                ) {
                                    Icon(
                                        Icons.Rounded.Info,
                                        contentDescription = statusLabel,
                                        tint = statusColor,
                                        modifier = Modifier.size(infoIconSize),
                                    )
                                }
                                DropdownMenu(
                                    expanded = showUpdateInfo,
                                    onDismissRequest = { showUpdateInfo = false },
                                    properties = PopupProperties(focusable = false),
                                    modifier = Modifier.widthIn(max = 320.dp).heightIn(max = 360.dp),
                                ) {
                                    Column(
                                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        Text(statusLabel, style = MaterialTheme.typography.titleSmall, color = statusColor)
                                        status.line?.let { line ->
                                            Text(
                                                stringResource(
                                                    if (line.kind == OutboundGroupStatusLineKind.UPDATED ||
                                                        line.kind == OutboundGroupStatusLineKind.PARTIALLY_UPDATED)
                                                        R.string.outbound_group_status_updated
                                                    else R.string.outbound_group_status_checked_at,
                                                    line.timestampMillis.toReadableDateTimeOrDash(),
                                                ),
                                                style = MaterialTheme.typography.bodySmall,
                                            )
                                            if ((line.kind == OutboundGroupStatusLineKind.FAILED ||
                                                    line.kind == OutboundGroupStatusLineKind.NOT_MODIFIED) &&
                                                group.lastUpdatedAtMillis > 0L) {
                                                Text(
                                                    stringResource(R.string.outbound_group_status_updated,
                                                        group.lastUpdatedAtMillis.toReadableDateTimeOrDash()),
                                                    style = MaterialTheme.typography.bodySmall,
                                                )
                                            }
                                            if (line.kind == OutboundGroupStatusLineKind.UPDATED ||
                                                line.kind == OutboundGroupStatusLineKind.PARTIALLY_UPDATED) {
                                                Text(
                                                    stringResource(R.string.outbound_group_status_import_counts,
                                                        line.importedCount, line.skippedCount, line.duplicateCount),
                                                    style = MaterialTheme.typography.bodySmall,
                                                )
                                            }
                                        }
                                        if (group.updateInterval.isNotBlank()) Text(
                                            stringResource(R.string.outbound_group_update_interval_value, group.updateInterval),
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                        if (status.summary.isNotBlank()) Text(
                                            status.summary, style = MaterialTheme.typography.bodySmall, color = statusColor,
                                        )
                                    }
                                }
                            }
                        }
                        if (group.url.isBlank()) Text(
                            text = " · " + stringResource(R.string.outbound_group_local),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Switch(
                    checked = group.enabled,
                    onCheckedChange = { showUpdateInfo = false; onEnabledChange(it) },
                    enabled = !enabledChangeBusy,
                )
                Box {
                    IconButton(
                        onClick = { showUpdateInfo = false; menuExpanded = true },
                        enabled = !enabledChangeBusy,
                    ) {
                        Icon(Icons.Rounded.MoreVert, stringResource(R.string.common_more))
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                    ) {
                        if (group.url.isNotBlank()) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.common_update)) },
                                leadingIcon = {
                                    if (syncing) {
                                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                    } else {
                                        Icon(Icons.Rounded.Refresh, contentDescription = null)
                                    }
                                },
                                enabled = !syncing,
                                onClick = {
                                    menuExpanded = false
                                    showUpdateInfo = false
                                    onSync()
                                },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.common_edit)) },
                            leadingIcon = { Icon(Icons.Rounded.Edit, contentDescription = null) },
                            onClick = {
                                menuExpanded = false
                                showUpdateInfo = false
                                onEdit()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.common_delete)) },
                            leadingIcon = { Icon(Icons.Rounded.Delete, contentDescription = null) },
                            onClick = {
                                menuExpanded = false
                                showUpdateInfo = false
                                onDelete()
                            },
                        )
                    }
                }
            }
            OutboundGroupSubscriptionInfo(info = group.subscriptionInfo)
            OutboundGroupCardMeta(group = group)
        }
    }
}

/**
 * Last-update time and expiry, the two facts worth seeing without opening the status
 * popup. Each half is omitted when the group does not carry it, so local groups and
 * subscriptions without a quota stay one line shorter.
 */
@Composable
private fun OutboundGroupCardMeta(group: OutboundGroupState) {
    val updatedText = group.lastUpdatedAtMillis
        .takeIf { it > 0L && group.url.isNotBlank() }
        ?.let {
            stringResource(
                R.string.outbound_group_status_updated,
                it.toReadableDateTimeOrDash(),
            )
        }
    val expireText = group.subscriptionInfo.expireAtSeconds
        .takeIf { it > 0L }
        ?.let {
            stringResource(
                R.string.outbound_group_subscription_expire,
                (it * 1000L).toReadableDateOrDash(),
            )
        }
    val meta = listOfNotNull(updatedText, expireText).joinToString(" · ")
    if (meta.isEmpty()) return
    Text(
        text = meta,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        // Directly under the quota it continues that block; otherwise it starts a new one.
        modifier = Modifier.padding(
            top = if (group.subscriptionInfo.hasMeteredQuota) 4.dp else 8.dp,
            end = 8.dp,
        ),
    )
}

@Composable
private fun OutboundGroupEditorSheet(
    show: Boolean,
    appState: AppState,
    group: OutboundGroupState?,
    editorSession: Int,
    busy: Boolean,
    onDismissRequest: () -> Unit,
    onSave: (OutboundGroupState) -> Unit,
) {
    var name by remember(editorSession) { mutableStateOf(group?.name.orEmpty()) }
    var url by remember(editorSession) { mutableStateOf(group?.url.orEmpty()) }
    var hwid by remember(editorSession) { mutableStateOf(group?.hwid.orEmpty()) }
    var detour by remember(editorSession) { mutableStateOf(group?.detour.orEmpty()) }
    val detourChoices = remember(
        appState.outboundGroups, appState.outbounds, appState.endpoints, appState.selectors, group?.id,
    ) {
        selectableGroupDetourOutbounds(appState, group?.id ?: 0)
    }
    val detourValues = listOf("") + detourChoices.map { it.tag } +
        listOfNotNull(detour.takeIf { value ->
            value.isNotBlank() && detourChoices.none { it.tag == value }
        })
    val detourLabels = detourChoices.associate { it.tag to it.localizedLabel() }
    val notSpecified = stringResource(R.string.common_not_specified)
    val unavailable = stringResource(R.string.common_unavailable)
    val initialUserAgent = group?.userAgent ?: DefaultOutboundSubscriptionUserAgent
    var userAgentOption by remember(editorSession) {
        mutableStateOf(subscriptionUserAgentOptionFor(initialUserAgent))
    }
    var customUserAgent by remember(editorSession) {
        mutableStateOf(
            initialUserAgent.takeIf {
                subscriptionUserAgentOptionFor(it) == SubscriptionUserAgentOption.Custom
            }.orEmpty(),
        )
    }
    var customUserAgentDraft by remember(editorSession) { mutableStateOf(customUserAgent) }
    var showCustomUserAgentDialog by remember(editorSession) { mutableStateOf(false) }
    var updateInterval by remember(editorSession) { mutableStateOf(group?.updateInterval.orEmpty()) }
    var updateViaProxy by remember(editorSession) { mutableStateOf(group?.updateViaProxy == true) }
    var strictImport by remember(editorSession) { mutableStateOf(group?.strictImport == true) }
    var ageSecretKey by remember(editorSession) { mutableStateOf(group?.ageSecretKey.orEmpty()) }
    val validUrl = url.isBlank() || url.isHttpUrl()
    val validInterval =
        parseSubscriptionSchedule(updateInterval) !is SubscriptionSchedule.Invalid
    // A blank name is only safe when the subscription URL can supply one on the
    // first successful sync. A local group has no such source, so it still needs
    // a title; a typed name always wins and is never auto-overwritten.
    val canSave = validUrl && validInterval && (url.isNotBlank() || name.isNotBlank())
    val hasSubscription = url.isNotBlank()
    val userAgent = userAgentOption.resolveUserAgent(customUserAgent)
    val userAgentLabels = SubscriptionUserAgentOptions.map { option ->
        option.localizedLabel()
    }
    val selectedUserAgentIndex = SubscriptionUserAgentOptions
        .indexOf(userAgentOption)
        .coerceAtLeast(0)
    val selectedUserAgentLabel = userAgentLabels[selectedUserAgentIndex]
    AsteriskModalBottomSheet(
        show = show,
        title = stringResource(
            if (group == null) R.string.outbound_group_add else R.string.outbound_group_edit,
        ),
        dismissEnabled = !busy,
        onDismissRequest = onDismissRequest,
        startAction = {
            AsteriskActionButton(
                text = stringResource(R.string.common_cancel),
                icon = Icons.Rounded.Close,
                onClick = onDismissRequest,
                enabled = !busy,
            )
        },
        endAction = {
            AsteriskActionButton(
                text = stringResource(R.string.common_save),
                icon = Icons.Rounded.Save,
                enabled = canSave && !busy,
                loading = busy,
                onClick = {
                    val trimmedUrl = url.trim()
                    val trimmedHwid = hwid.trim()
                    val trimmedAgeSecretKey = ageSecretKey.trim()
                    val edited = group?.copy(
                        name = name.trim(),
                        url = trimmedUrl,
                        userAgent = userAgent,
                        detour = detour,
                        updateInterval = updateInterval.trim(),
                        hwid = trimmedHwid,
                        updateViaProxy = updateViaProxy,
                        ageSecretKey = trimmedAgeSecretKey,
                        strictImport = strictImport,
                    ) ?: OutboundGroupState(
                        id = 0,
                        name = name.trim(),
                        url = trimmedUrl,
                        userAgent = userAgent,
                        detour = detour,
                        updateInterval = updateInterval.trim(),
                        hwid = trimmedHwid,
                        updateViaProxy = updateViaProxy,
                        ageSecretKey = trimmedAgeSecretKey,
                        strictImport = strictImport,
                    )
                    onSave(
                        edited.clearingSubscriptionMetadataChangedFrom(group),
                    )
                },
            )
        },
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 720.dp),
            contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 24.dp),
        ) {
            item(key = "name") {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.outbound_group_name)) },
                    placeholder = {
                        Text(stringResource(R.string.outbound_group_name_placeholder))
                    },
                    singleLine = true,
                    shape = AsteriskShapeTokens.InnerContainer,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item(key = "url") {
                Column {
                    Spacer(Modifier.height(GroupEditorSectionSpacing))
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it },
                        label = { Text(stringResource(R.string.outbound_group_url_optional)) },
                        supportingText = if (!validUrl) {
                            { Text(stringResource(R.string.outbound_group_url_invalid)) }
                        } else {
                            null
                        },
                        isError = !validUrl,
                        singleLine = true,
                        shape = AsteriskShapeTokens.InnerContainer,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            item(key = "hwid") {
                AnimatedVisibility(
                    visible = hasSubscription,
                    enter = AsteriskMotion.contentEnter(),
                    exit = AsteriskMotion.contentExit(),
                ) {
                    Column {
                        Spacer(Modifier.height(GroupEditorSectionSpacing))
                        OutlinedTextField(
                            value = hwid,
                            onValueChange = { hwid = it },
                            label = { Text(stringResource(R.string.outbound_group_hwid)) },
                            singleLine = true,
                            shape = AsteriskShapeTokens.InnerContainer,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
            item(key = "http-warning") {
                AnimatedVisibility(
                    visible = url.startsWith("http://", ignoreCase = true),
                    enter = AsteriskMotion.contentEnter(),
                    exit = AsteriskMotion.contentExit(),
                ) {
                    Column {
                        Spacer(Modifier.height(GroupEditorSectionSpacing))
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                            ),
                            shape = AsteriskShapeTokens.InnerContainer,
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    Icons.Rounded.Warning,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onTertiaryContainer,
                                )
                                Spacer(Modifier.width(12.dp))
                                Text(
                                    stringResource(R.string.outbound_group_http_warning),
                                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        }
                    }
                }
            }
            item(key = "age-secret-key") {
                AnimatedVisibility(
                    visible = hasSubscription,
                    enter = AsteriskMotion.contentEnter(),
                    exit = AsteriskMotion.contentExit(),
                ) {
                    Column {
                        Spacer(Modifier.height(GroupEditorSectionSpacing))
                        OutlinedTextField(
                            value = ageSecretKey,
                            onValueChange = { ageSecretKey = it },
                            label = { Text(stringResource(R.string.outbound_group_age_secret_key)) },
                            singleLine = true,
                            shape = AsteriskShapeTokens.InnerContainer,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
            item(key = "detour") {
                Column {
                    Spacer(Modifier.height(GroupEditorSectionSpacing))
                    SettingsDropdownRow(
                        title = stringResource(R.string.outbound_group_detour),
                        horizontalPadding = 0.dp,
                        icon = Icons.AutoMirrored.Rounded.AltRoute,
                        items = detourValues.map { value ->
                            if (value.isBlank()) notSpecified else detourLabels[value] ?: unavailable
                        },
                        selectedIndex = detourValues.indexOf(detour).coerceAtLeast(0),
                        onSelectedIndexChange = { index -> detour = detourValues[index] },
                    )
                }
            }
            item(key = "subscription-options") {
                AnimatedVisibility(
                    visible = hasSubscription,
                    enter = AsteriskMotion.contentEnter(),
                    exit = AsteriskMotion.contentExit(),
                ) {
                    Column {
                        Spacer(Modifier.height(GroupEditorSectionSpacing))
                        Column(verticalArrangement = Arrangement.spacedBy(GroupEditorSectionSpacing)) {
                            SettingsDropdownRow(
                                title = stringResource(R.string.outbound_group_user_agent),
                                horizontalPadding = 0.dp,
                                summary = userAgent
                                    .takeUnless { it == selectedUserAgentLabel }
                                    .orEmpty(),
                                icon = Icons.Rounded.Language,
                                items = userAgentLabels,
                                selectedIndex = selectedUserAgentIndex,
                                onSelectedIndexChange = { index ->
                                    val nextOption = SubscriptionUserAgentOptions[index]
                                    if (nextOption == SubscriptionUserAgentOption.Custom) {
                                        customUserAgentDraft = customUserAgent.ifBlank { userAgent }
                                        showCustomUserAgentDialog = true
                                    } else {
                                        userAgentOption = nextOption
                                    }
                                },
                            )
                            SettingsSwitchRow(
                                title = stringResource(R.string.outbound_group_update_via_proxy),
                                horizontalPadding = 0.dp,
                                icon = Icons.Rounded.CloudSync,
                                checked = updateViaProxy,
                                onCheckedChange = { updateViaProxy = it },
                            )
                            SettingsSwitchRow(
                                title = stringResource(R.string.outbound_group_strict_import),
                                horizontalPadding = 0.dp,
                                summary = stringResource(
                                    R.string.outbound_group_strict_import_summary,
                                ),
                                icon = Icons.Rounded.Security,
                                checked = strictImport,
                                onCheckedChange = { strictImport = it },
                            )
                            OutlinedTextField(
                                value = updateInterval,
                                onValueChange = { value ->
                                    updateInterval = value.filter { it.isDigit() || it == '.' }
                                },
                                label = { Text(stringResource(R.string.outbound_group_update_interval)) },
                                isError = !validInterval,
                                supportingText = if (!validInterval) {
                                    { Text(stringResource(R.string.common_error_update_interval)) }
                                } else {
                                    null
                                },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                singleLine = true,
                                shape = AsteriskShapeTokens.InnerContainer,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }
        }
    }
    CustomSubscriptionUserAgentDialog(
        show = show && showCustomUserAgentDialog,
        value = customUserAgentDraft,
        onValueChange = { customUserAgentDraft = it },
        onDismissRequest = { showCustomUserAgentDialog = false },
        onSave = {
            customUserAgent = customUserAgentDraft.trim()
                .ifBlank { DefaultOutboundSubscriptionUserAgent }
            userAgentOption = SubscriptionUserAgentOption.Custom
            showCustomUserAgentDialog = false
        },
    )
}

@Composable
private fun SubscriptionUserAgentOption.localizedLabel(): String =
    stringResource(
        when (this) {
            SubscriptionUserAgentOption.SingBox -> R.string.outbound_group_user_agent_sing_box
            SubscriptionUserAgentOption.V2rayNg -> R.string.outbound_group_user_agent_v2rayng
            SubscriptionUserAgentOption.ClashMeta -> R.string.outbound_group_user_agent_clash_meta
            SubscriptionUserAgentOption.Custom -> R.string.outbound_group_user_agent_custom
        },
    )

@Composable
private fun CustomSubscriptionUserAgentDialog(
    show: Boolean,
    value: String,
    onValueChange: (String) -> Unit,
    onDismissRequest: () -> Unit,
    onSave: () -> Unit,
) {
    if (!show) return
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(stringResource(R.string.outbound_group_custom_user_agent)) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                label = { Text(stringResource(R.string.outbound_group_custom_user_agent)) },
                singleLine = true,
                shape = AsteriskShapeTokens.InnerContainer,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        dismissButton = {
            AsteriskActionButton(
                text = stringResource(R.string.common_cancel),
                icon = Icons.Rounded.Close,
                onClick = onDismissRequest,
            )
        },
        confirmButton = {
            AsteriskActionButton(
                text = stringResource(R.string.common_save),
                icon = Icons.Rounded.Save,
                onClick = onSave,
            )
        },
    )
}

private fun String.isHttpUrl(): Boolean =
    runCatching {
        val uri = URI(trim())
        (uri.scheme == "http" || uri.scheme == "https") && !uri.host.isNullOrBlank()
    }.getOrDefault(false)

private fun OutboundGroupState.clearingSubscriptionMetadataChangedFrom(
    previous: OutboundGroupState?,
): OutboundGroupState {
    if (previous == null) return this
    if (url != previous.url) {
        return copy(
            lastUpdateAttemptAtMillis = 0L,
            lastUpdatedAtMillis = 0L,
            lastUpdateStatus = OutboundGroupUpdateStatus.NEVER,
            lastUpdateImportedCount = 0,
            lastUpdateSkippedCount = 0,
            lastUpdateDuplicateCount = 0,
            consecutiveUpdateFailures = 0,
            lastUpdateErrorSummary = "",
            subscriptionEtag = "",
            subscriptionLastModified = "",
            subscriptionInfo = SubscriptionInfo(),
        )
    }
    if (
        userAgent != previous.userAgent ||
        hwid != previous.hwid ||
        updateViaProxy != previous.updateViaProxy ||
        ageSecretKey != previous.ageSecretKey
    ) {
        return copy(
            subscriptionEtag = "",
            subscriptionLastModified = "",
        )
    }
    return this
}

private val GroupEditorSectionSpacing = 12.dp

/** Metered traffic stays on the card; expiry and update details live in the info popup. */
@Composable
private fun OutboundGroupSubscriptionInfo(
    info: SubscriptionInfo,
    modifier: Modifier = Modifier,
) {
    if (!info.hasMeteredQuota) return
    Column(
        modifier = modifier.fillMaxWidth().padding(top = 12.dp, end = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        LinearProgressIndicator(
            progress = { info.usageProgress },
            modifier = Modifier.fillMaxWidth().height(6.dp),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.primaryContainer,
        )
        Text(
            text = stringResource(
                R.string.outbound_group_subscription_traffic,
                info.usedBytes.toReadableBytes(maxUnit = utils.ReadableByteUnit.GiB),
                info.totalBytes.toReadableBytes(maxUnit = utils.ReadableByteUnit.GiB),
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
