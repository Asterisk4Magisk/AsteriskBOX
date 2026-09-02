// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package features.outbound

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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import app.DefaultOutboundSubscriptionUserAgent
import app.LocalAppServices
import app.LocalAppStateStore
import app.LocalIsWideScreen
import app.LocalNavigator
import app.OutboundGroupState
import app.OutboundGroupUpdateStatus
import app.collectAppState
import features.importing.ImportOperation
import features.importing.ImportResultDetail
import features.importing.ImportResultDialog
import features.importing.ImportResultPresentation
import features.importing.ImportSource
import features.importing.ImportStage
import features.importing.importFailureResultPresentation
import features.importing.reportImportFailure
import features.importing.sanitizeImportMessage
import features.importing.toImportResultPresentation
import features.logs.FailureLogContext
import features.settings.SettingsDropdownRow
import features.settings.SettingsSwitchRow
import features.subscription.SubscriptionSchedule
import features.subscription.SubscriptionUserAgentOption
import features.subscription.SubscriptionUserAgentOptions
import features.subscription.parseSubscriptionSchedule
import features.subscription.resolveUserAgent
import features.subscription.subscriptionUserAgentOptionFor
import features.subscription.usecase.OutboundSubscriptionUpdateResult
import features.subscription.usecase.SubscriptionUpdateTrigger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.asterisk.zcc.abox.R
import sh.calvin.reorderable.ReorderableItem
import ui.components.AsteriskActionButton
import ui.components.AsteriskModalBottomSheet
import ui.components.WarningConfirmDialog
import ui.components.draggedCardShadow
import ui.components.longPressReorderDragHandle
import ui.components.rememberAsteriskReorderableLazyListState
import ui.components.verticalReorderScrollThresholdPadding
import ui.layout.pageContentPaddingWithCutout
import ui.layout.pageListPadding
import ui.theme.AsteriskMotion
import ui.theme.AsteriskShapeTokens
import utils.toReadableDateTimeOrDash
import java.net.URI
import ui.icons.AsteriskIcons as Icons

@Composable
internal fun OutboundGroupListPage(
    padding: PaddingValues,
) {
    val stateStore = LocalAppStateStore.current
    val appState by stateStore.collectAppState()
    val navigator = LocalNavigator.current
    val services = LocalAppServices.current
    val resources = LocalResources.current
    val isWideScreen = LocalIsWideScreen.current
    val scope = rememberCoroutineScope()
    var editorGroup by remember { mutableStateOf<OutboundGroupState?>(null) }
    var showGroupEditor by remember { mutableStateOf(false) }
    var groupEditorSession by remember { mutableIntStateOf(0) }
    var savingGroupEditorSession by remember { mutableStateOf<Int?>(null) }
    var pendingDelete by remember { mutableStateOf<OutboundGroupState?>(null) }
    var deletingGroupId by remember { mutableStateOf<Int?>(null) }
    var enabledChangingGroupIds by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var groupOrderPreviewIds by remember { mutableStateOf<List<Int>?>(null) }
    var groupOrderPreviewGeneration by remember { mutableStateOf<Long?>(null) }
    var nextGroupOrderGeneration by remember { mutableLongStateOf(0L) }
    val groupReorderMutex = remember { Mutex() }
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
    val importFailedMessage = stringResource(R.string.outbound_group_sync_failed)
    val stateChangedMessage = stringResource(R.string.outbound_group_sync_failed)
    val subscriptionGroups = appState.outboundGroups.outboundSubscriptionGroups()

    fun saveGroup(group: OutboundGroupState) {
        val session = groupEditorSession
        if (savingGroupEditorSession != null) return
        val expected = editorGroup
        savingGroupEditorSession = session
        scope.launch {
            try {
                when (val result = services.outboundRepository.saveGroup(expected, group)) {
                    is OutboundCommandResult.GroupSaved -> {
                        if (groupEditorSession == session) showGroupEditor = false
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
        scope.launch {
            try {
                when (val result = updateGroupSubscription(group)) {
                    is OutboundGroupUpdateResult.Success -> {
                        if (result.presentation?.showDialog == true) {
                            importResultPresentation = result.presentation
                        } else if (result.notModified) {
                            services.tipNotifier.show(
                                resources.getString(
                                    R.string.outbound_group_sync_not_modified,
                                ),
                            )
                        } else {
                            services.tipNotifier.show(
                                resources.getQuantityString(
                                    R.plurals.outbound_import_success,
                                    result.outboundCount,
                                    result.outboundCount,
                                ),
                            )
                        }
                    }
                    is OutboundGroupUpdateResult.Failure -> {
                        reportImportFailure(
                            operation = ImportOperation.OUTBOUND_SUBSCRIPTION,
                            source = ImportSource.SUBSCRIPTION,
                            stage = result.stage,
                            error = result.error,
                        )
                        importResultPresentation = result.presentation
                            ?: importFailureResultPresentation(
                                result.error.message ?: importFailedMessage,
                            )
                    }
                }
            } finally {
                syncingGroupIds -= group.id
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

    Scaffold(
        topBar = {
            TopAppBar(
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
                    IconButton(onClick = navigator::pop) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowBack,
                            stringResource(R.string.common_back),
                        )
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
                        onClick = {
                            editorGroup = null
                            groupEditorSession += 1
                            showGroupEditor = true
                        },
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
        val listState = rememberLazyListState()
        val displayedGroups = groupOrderPreviewIds?.let { previewIds ->
            val groupsById = appState.outboundGroups.associateBy(OutboundGroupState::id)
            previewIds.mapNotNull(groupsById::get).takeIf { groups ->
                groups.size == appState.outboundGroups.size
            }
        } ?: appState.outboundGroups
        val reorderableState = rememberAsteriskReorderableLazyListState(
            lazyListState = listState,
            itemCount = displayedGroups.size,
            scrollThresholdPadding = verticalReorderScrollThresholdPadding(listContentPadding),
            onMove = { fromIndex, toIndex ->
                val currentIds = groupOrderPreviewIds
                    ?.takeIf { ids ->
                        ids.size == displayedGroups.size &&
                            ids.toSet() == displayedGroups.mapTo(mutableSetOf(), OutboundGroupState::id)
                    }
                    ?: displayedGroups.map(OutboundGroupState::id)
                val reorderedIds = currentIds.toMutableList().apply {
                    if (fromIndex in indices && toIndex in indices && fromIndex != toIndex) {
                        add(toIndex, removeAt(fromIndex))
                    }
                }
                if (reorderedIds == currentIds) return@rememberAsteriskReorderableLazyListState
                nextGroupOrderGeneration += 1L
                val generation = nextGroupOrderGeneration
                groupOrderPreviewIds = reorderedIds
                groupOrderPreviewGeneration = generation
                scope.launch {
                    groupReorderMutex.withLock {
                        when (val result = services.outboundRepository.reorderGroups(reorderedIds)) {
                            OutboundCommandResult.GroupsReordered -> {
                                if (groupOrderPreviewGeneration == generation) {
                                    groupOrderPreviewIds = null
                                    groupOrderPreviewGeneration = null
                                }
                            }
                            OutboundCommandResult.Conflict -> {
                                services.tipNotifier.show(stateChangedMessage)
                                if (groupOrderPreviewGeneration == generation) {
                                    groupOrderPreviewIds = null
                                    groupOrderPreviewGeneration = null
                                }
                            }
                            is OutboundCommandResult.PersistenceFailed -> {
                                services.tipNotifier.showError(
                                    result.error,
                                    importFailedMessage,
                                    FailureLogContext(
                                        operation = "outbound_group_reorder",
                                        stage = "persist",
                                    ),
                                )
                                if (groupOrderPreviewGeneration == generation) {
                                    groupOrderPreviewIds = null
                                    groupOrderPreviewGeneration = null
                                }
                            }
                            is OutboundCommandResult.Invalid ->
                                services.tipNotifier.show(importFailedMessage)
                            OutboundCommandResult.Deleted,
                            OutboundCommandResult.GroupDeleted,
                            OutboundCommandResult.GroupEnabledChanged,
                            OutboundCommandResult.ImportPersisted,
                            OutboundCommandResult.Reordered,
                            is OutboundCommandResult.Saved,
                            is OutboundCommandResult.GroupSaved,
                            -> error("Unexpected outbound group reorder result: $result")
                        }
                    }
                }
            },
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            contentPadding = listContentPadding,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (displayedGroups.isEmpty()) {
                item(key = "empty") {
                    OutboundGroupEmptyState(
                        onAdd = {
                            editorGroup = null
                            groupEditorSession += 1
                            showGroupEditor = true
                        },
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
                        outboundCount = appState.outbounds.count { outbound ->
                            outbound.groupId == group.id
                        },
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
                            ),
                    )
                }
            }
        }
    }

    OutboundGroupEditorSheet(
        show = showGroupEditor,
        group = editorGroup,
        editorSession = groupEditorSession,
        busy = savingGroupEditorSession == groupEditorSession,
        onDismissRequest = {
            if (savingGroupEditorSession != groupEditorSession) showGroupEditor = false
        },
        onSave = ::saveGroup,
    )

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
            onDismissRequest = { importResultPresentation = null },
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

@Composable
private fun OutboundGroupEmptyState(onAdd: () -> Unit) {
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
private fun OutboundGroupCard(
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
    Card(
        onClick = onEdit,
        enabled = !enabledChangeBusy,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 128.dp)
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
        Column(Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 16.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Folder,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(30.dp),
                )
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = group.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                    }
                    Text(
                        text = group.url.ifBlank { stringResource(R.string.outbound_group_local) },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val details = buildList {
                        add(pluralStringResource(R.plurals.outbound_count, outboundCount, outboundCount))
                        group.updateInterval.takeIf(String::isNotBlank)?.let {
                            add(stringResource(R.string.outbound_group_update_interval_value, it))
                        }
                    }
                    Text(
                        text = details.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (group.url.isNotBlank()) {
                        val status = group.subscriptionStatusPresentation()
                        val statusColor = when (status.status) {
                            OutboundGroupUpdateStatus.FAILED ->
                                MaterialTheme.colorScheme.error
                            OutboundGroupUpdateStatus.PARTIAL ->
                                MaterialTheme.colorScheme.tertiary
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                        status.line?.let { line ->
                            val formattedTime = line.timestampMillis.toReadableDateTimeOrDash()
                            val statusHeaderText = when (line.kind) {
                                OutboundGroupStatusLineKind.UPDATED -> stringResource(
                                    R.string.outbound_group_status_updated,
                                    formattedTime,
                                )
                                OutboundGroupStatusLineKind.PARTIALLY_UPDATED -> stringResource(
                                    R.string.outbound_group_status_labeled_updated,
                                    stringResource(R.string.outbound_group_status_partial),
                                    formattedTime,
                                )
                                OutboundGroupStatusLineKind.NOT_MODIFIED -> stringResource(
                                    R.string.outbound_group_status_checked,
                                    stringResource(R.string.outbound_group_status_not_modified),
                                    formattedTime,
                                )
                                OutboundGroupStatusLineKind.FAILED -> stringResource(
                                    R.string.outbound_group_status_checked,
                                    stringResource(R.string.outbound_group_status_failed),
                                    formattedTime,
                                )
                            }
                            val importCountsText = when (line.kind) {
                                OutboundGroupStatusLineKind.UPDATED,
                                OutboundGroupStatusLineKind.PARTIALLY_UPDATED,
                                -> stringResource(
                                    R.string.outbound_group_status_import_counts,
                                    line.importedCount,
                                    line.skippedCount,
                                    line.duplicateCount,
                                )
                                OutboundGroupStatusLineKind.NOT_MODIFIED,
                                OutboundGroupStatusLineKind.FAILED,
                                -> null
                            }
                            Text(
                                text = statusHeaderText,
                                style = MaterialTheme.typography.bodySmall,
                                color = statusColor,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            importCountsText?.let {
                                Text(
                                    text = it,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = statusColor,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        if (status.summary.isNotBlank()) {
                            Text(
                                text = status.summary,
                                style = MaterialTheme.typography.bodySmall,
                                color = statusColor,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                Switch(
                    checked = group.enabled,
                    onCheckedChange = onEnabledChange,
                    enabled = !enabledChangeBusy,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                if (group.url.isNotBlank()) {
                    TextButton(onClick = onSync, enabled = !syncing) {
                        if (syncing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Icon(Icons.Rounded.Refresh, contentDescription = null)
                        }
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.common_update))
                    }
                }
                TextButton(onClick = onEdit, enabled = !enabledChangeBusy) {
                    Icon(Icons.Rounded.Edit, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.common_edit))
                }
                TextButton(onClick = onDelete, enabled = !enabledChangeBusy) {
                    Icon(Icons.Rounded.Delete, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.common_delete))
                }
            }
        }
    }
}

@Composable
private fun OutboundGroupEditorSheet(
    show: Boolean,
    group: OutboundGroupState?,
    editorSession: Int,
    busy: Boolean,
    onDismissRequest: () -> Unit,
    onSave: (OutboundGroupState) -> Unit,
) {
    var name by remember(editorSession) { mutableStateOf(group?.name.orEmpty()) }
    var url by remember(editorSession) { mutableStateOf(group?.url.orEmpty()) }
    var hwid by remember(editorSession) { mutableStateOf(group?.hwid.orEmpty()) }
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
    val canSave = name.isNotBlank() && validUrl && validInterval
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
            item(key = "subscription-options") {
                AnimatedVisibility(
                    visible = hasSubscription,
                    enter = AsteriskMotion.contentEnter(),
                    exit = AsteriskMotion.contentExit(),
                ) {
                    Column {
                        Spacer(Modifier.height(GroupEditorSectionSpacing))
                        Column(verticalArrangement = Arrangement.spacedBy(GroupEditorSectionSpacing)) {
                            OutlinedTextField(
                                value = ageSecretKey,
                                onValueChange = { ageSecretKey = it },
                                label = { Text(stringResource(R.string.outbound_group_age_secret_key)) },
                                singleLine = true,
                                shape = AsteriskShapeTokens.InnerContainer,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            SettingsDropdownRow(
                                title = stringResource(R.string.outbound_group_user_agent),
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
                                icon = Icons.Rounded.CloudSync,
                                checked = updateViaProxy,
                                onCheckedChange = { updateViaProxy = it },
                            )
                            SettingsSwitchRow(
                                title = stringResource(R.string.outbound_group_strict_import),
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
                                    { Text(stringResource(R.string.outbound_group_update_interval_invalid)) }
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
            TextButton(onClick = onDismissRequest) {
                Text(stringResource(R.string.common_cancel))
            }
        },
        confirmButton = {
            TextButton(onClick = onSave) {
                Text(stringResource(R.string.common_save))
            }
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
