// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package features.outbound

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.DefaultOutboundSubscriptionUserAgent
import app.LocalAppServices
import app.LocalAppStateStore
import app.LocalIsWideScreen
import app.LocalNavigator
import app.LocalUpdateAppState
import app.OutboundGroupState
import app.collectAppState
import app.nextAvailableOutboundGroupId
import app.managedOutboundGroupSelectorTag
import app.withRemovedManagedOutboundTags
import engine.singbox.config.SingBoxConfigChecker
import engine.singbox.config.SingBoxJson
import engine.singbox.config.validateSingBoxRuntimeConfiguration
import features.importing.ImportOperation
import features.importing.ImportSource
import features.importing.ImportStage
import features.importing.importFailureContext
import features.importing.reportImportFailure
import features.settings.SettingsDropdownRow
import features.settings.SettingsSwitchRow
import features.subscription.SubscriptionUserAgentOption
import features.subscription.SubscriptionUserAgentOptions
import features.subscription.resolveUserAgent
import features.subscription.runtime.toSubscriptionFetchOptions
import features.subscription.subscriptionUserAgentOptionFor
import features.subscription.usecase.SubscriptionPreparation
import features.subscription.usecase.SubscriptionSyncStage
import features.subscription.usecase.prepareSubscription
import java.net.URI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import org.asterisk.zcc.abox.R
import ui.components.AsteriskActionButton
import ui.components.AsteriskModalBottomSheet
import ui.components.WarningConfirmDialog
import ui.icons.AsteriskIcons as Icons
import ui.layout.pageContentPaddingWithCutout
import ui.layout.pageListPadding
import ui.theme.AsteriskMotion
import ui.theme.AsteriskShapeTokens
import utils.toReadableDateTimeOrDash

@Composable
internal fun OutboundGroupListPage(
    padding: PaddingValues,
) {
    val stateStore = LocalAppStateStore.current
    val appState by stateStore.collectAppState()
    val updateAppState = LocalUpdateAppState.current
    val navigator = LocalNavigator.current
    val services = LocalAppServices.current
    val context = LocalContext.current
    val resources = LocalResources.current
    val isWideScreen = LocalIsWideScreen.current
    val scope = rememberCoroutineScope()
    var editorGroup by remember { mutableStateOf<OutboundGroupState?>(null) }
    var showGroupEditor by remember { mutableStateOf(false) }
    var groupEditorSession by remember { mutableIntStateOf(0) }
    var pendingDelete by remember { mutableStateOf<OutboundGroupState?>(null) }
    var syncingGroupIds by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var batchSyncJob by remember { mutableStateOf<Job?>(null) }
    var batchSyncProgress by remember { mutableStateOf<OutboundGroupBatchProgress?>(null) }
    var batchSyncCancelling by remember { mutableStateOf(false) }
    val importFailedMessage = stringResource(R.string.outbound_group_sync_failed)
    val subscriptionGroups = appState.outboundGroups.outboundSubscriptionGroups()

    fun saveGroup(group: OutboundGroupState) {
        updateAppState { state ->
            val previous = state.outboundGroups.firstOrNull { item -> item.id == group.id }
            val updated = if (previous != null) {
                state.copy(
                    outboundGroups = state.outboundGroups.map { item ->
                        if (item.id == group.id) group else item
                    },
                )
            } else {
                val id = state.nextAvailableOutboundGroupId()
                state.copy(
                    outboundGroups = state.outboundGroups + group.copy(id = id),
                    nextOutboundGroupId = id + 1,
                )
            }
            if (previous?.enabled == true && !group.enabled) {
                updated.withRemovedManagedOutboundTags(
                    state.outbounds
                        .filter { outbound -> outbound.groupId == group.id }
                        .mapTo(mutableSetOf()) { outbound -> outbound.tag }
                        .apply { add(managedOutboundGroupSelectorTag(group.id)) },
                )
            } else {
                updated
            }
        }
        showGroupEditor = false
    }

    fun deleteGroup(group: OutboundGroupState) {
        updateAppState { state ->
            val removedTags = state.outbounds
                .filter { outbound -> outbound.groupId == group.id }
                .mapTo(mutableSetOf()) { outbound -> outbound.tag }
                .apply { add(managedOutboundGroupSelectorTag(group.id)) }
            state.copy(
                outboundGroups = state.outboundGroups.filterNot { item -> item.id == group.id },
                outbounds = state.outbounds.filterNot { outbound -> outbound.groupId == group.id },
            ).withRemovedManagedOutboundTags(removedTags)
        }
    }

    suspend fun updateGroupSubscription(
        requestedGroup: OutboundGroupState,
        onStage: (ImportStage) -> Unit = {},
    ): OutboundGroupUpdateResult {
        var stage = ImportStage.DOWNLOAD
        fun advance(nextStage: ImportStage) {
            stage = nextStage
            onStage(nextStage)
        }

        return try {
            val snapshot = stateStore.state.value
            val group = snapshot.outboundGroups.firstOrNull { it.id == requestedGroup.id }
                ?: return OutboundGroupUpdateResult.Failure(
                    stage = ImportStage.COMMIT,
                    error = IllegalStateException("Outbound group no longer exists"),
                )
            advance(ImportStage.DOWNLOAD)
            when (
                val preparation = prepareSubscription(
                    sourceUrl = group.url,
                    userAgent = group.userAgent,
                    ageSecretKey = group.ageSecretKey,
                    localContent = null,
                    subscriptionPreparer = services.subscriptionPreparer,
                    fetchOptions = snapshot.toSubscriptionFetchOptions(
                        useRunningProxy = group.updateViaProxy,
                        hwid = group.hwid,
                    ),
                    verifyConfiguration = false,
                    onStage = { syncStage -> advance(syncStage.toImportStage()) },
                )
            ) {
                is SubscriptionPreparation.Success -> {
                    advance(ImportStage.PARSE)
                    val importResult = withContext(Dispatchers.Default) {
                        validateCompleteSingBoxJsonWhenPresent(preparation.content)
                        OutboundImportPipeline.parse(preparation.content)
                    }
                    val previousTags = snapshot.outbounds
                        .filter { outbound -> outbound.groupId == group.id }
                        .mapTo(mutableSetOf()) { outbound -> outbound.tag }
                    val importedState = snapshot.withImportedOutbounds(
                        groupId = group.id,
                        imported = importResult.outbounds,
                        replaceGroup = true,
                    )
                    val candidateState = importedState.copy(
                        outboundGroups = importedState.outboundGroups.map { item ->
                            if (item.id == group.id) {
                                item.copy(lastUpdatedAtMillis = System.currentTimeMillis())
                            } else {
                                item
                            }
                        },
                    ).withRemovedManagedOutboundTags(previousTags)
                    advance(ImportStage.VALIDATE)
                    withContext(Dispatchers.IO) {
                        validateSingBoxRuntimeConfiguration(context, candidateState)
                    }
                    advance(ImportStage.COMMIT)
                    var committed = false
                    updateAppState { state ->
                        if (state === snapshot) {
                            committed = true
                            candidateState
                        } else {
                            state
                        }
                    }
                    if (committed) {
                        OutboundGroupUpdateResult.Success(importResult.outbounds.size)
                    } else {
                        OutboundGroupUpdateResult.Failure(
                            stage = stage,
                            error = IllegalStateException("Application state changed during subscription update"),
                        )
                    }
                }
                is SubscriptionPreparation.Failure -> OutboundGroupUpdateResult.Failure(
                    stage = preparation.stage.toImportStage(),
                    error = preparation.error,
                )
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            OutboundGroupUpdateResult.Failure(stage = stage, error = error)
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
                        services.tipNotifier.show(
                            resources.getQuantityString(
                                R.plurals.outbound_import_success,
                                result.outboundCount,
                                result.outboundCount,
                            ),
                        )
                    }
                    is OutboundGroupUpdateResult.Failure -> {
                        services.tipNotifier.showError(
                            result.error,
                            importFailedMessage,
                            importFailureContext(
                                ImportOperation.OUTBOUND_SUBSCRIPTION,
                                ImportSource.SUBSCRIPTION,
                                result.stage,
                            ),
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
        var completedCount = 0
        var updatedCount = 0
        var failedCount = 0
        lateinit var syncJob: Job
        syncJob = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val result = updateOutboundGroupsSequentially(
                    groups = groups,
                    updateGroup = ::updateGroupSubscription,
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
                    onGroupCompleted = { _, updateResult ->
                        completedCount += 1
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
                services.tipNotifier.show(
                    if (result.failedCount == 0) {
                        resources.getString(
                            R.string.outbound_group_sync_all_success,
                            result.updatedCount,
                        )
                    } else {
                        resources.getString(
                            R.string.outbound_group_sync_all_partial,
                            result.updatedCount,
                            result.failedCount,
                        )
                    },
                )
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
        LazyColumn(
            contentPadding = pageListPadding(contentPadding),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (appState.outboundGroups.isEmpty()) {
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
                items = appState.outboundGroups,
                key = OutboundGroupState::id,
                contentType = { "outbound-group" },
            ) { group ->
                OutboundGroupCard(
                    group = group,
                    outboundCount = appState.outbounds.count { outbound -> outbound.groupId == group.id },
                    syncing = group.id in syncingGroupIds,
                    onEnabledChange = { enabled ->
                        updateAppState { state ->
                            val groupTags = state.outbounds
                                .filter { outbound -> outbound.groupId == group.id }
                                .mapTo(mutableSetOf()) { outbound -> outbound.tag }
                                .apply { add(managedOutboundGroupSelectorTag(group.id)) }
                            state.copy(
                                outboundGroups = state.outboundGroups.map { item ->
                                    if (item.id == group.id) item.copy(enabled = enabled) else item
                                },
                            ).let { updated ->
                                if (enabled) updated
                                else updated.withRemovedManagedOutboundTags(groupTags)
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
                    modifier = Modifier.animateItem(),
                )
            }
        }
    }

    OutboundGroupEditorSheet(
        show = showGroupEditor,
        group = editorGroup,
        editorSession = groupEditorSession,
        onDismissRequest = { showGroupEditor = false },
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
            pendingDelete?.let(::deleteGroup)
            pendingDelete = null
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
        enter = fadeIn(AsteriskMotion.effects()),
        exit = fadeOut(AsteriskMotion.effects()),
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
    onEnabledChange: (Boolean) -> Unit,
    onSync: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        onClick = onEdit,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 128.dp)
            .animateContentSize(animationSpec = AsteriskMotion.contentSpatial()),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
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
                        if (group.lastUpdatedAtMillis > 0L) {
                            add(
                                stringResource(
                                    R.string.outbound_group_last_updated,
                                    group.lastUpdatedAtMillis.toReadableDateTimeOrDash(),
                                ),
                            )
                        }
                    }
                    Text(
                        text = details.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                    )
                }
                Switch(
                    checked = group.enabled,
                    onCheckedChange = onEnabledChange,
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
                TextButton(onClick = onEdit) {
                    Icon(Icons.Rounded.Edit, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.common_edit))
                }
                TextButton(onClick = onDelete) {
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
    var ageSecretKey by remember(editorSession) { mutableStateOf(group?.ageSecretKey.orEmpty()) }
    val validUrl = url.isBlank() || url.isHttpUrl()
    val validInterval = updateInterval.isBlank() ||
        (updateInterval.toDoubleOrNull()?.let { it > 0 } == true)
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
        onDismissRequest = onDismissRequest,
        startAction = {
            AsteriskActionButton(
                text = stringResource(R.string.common_cancel),
                icon = Icons.Rounded.Close,
                onClick = onDismissRequest,
            )
        },
        endAction = {
            AsteriskActionButton(
                text = stringResource(R.string.common_save),
                icon = Icons.Rounded.Save,
                enabled = canSave,
                onClick = {
                    onSave(
                        group?.copy(
                            name = name.trim(),
                            url = url.trim(),
                            userAgent = userAgent,
                            updateInterval = updateInterval.trim(),
                            hwid = hwid.trim(),
                            updateViaProxy = updateViaProxy,
                            ageSecretKey = ageSecretKey.trim(),
                        ) ?: OutboundGroupState(
                            id = 0,
                            name = name.trim(),
                            url = url.trim(),
                            userAgent = userAgent,
                            updateInterval = updateInterval.trim(),
                            hwid = hwid.trim(),
                            updateViaProxy = updateViaProxy,
                            ageSecretKey = ageSecretKey.trim(),
                        ),
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

private fun SubscriptionSyncStage.toImportStage(): ImportStage = when (this) {
    SubscriptionSyncStage.Downloading -> ImportStage.DOWNLOAD
    SubscriptionSyncStage.Decrypting -> ImportStage.DECRYPT
    SubscriptionSyncStage.Verifying -> ImportStage.VERIFY
}

private fun validateCompleteSingBoxJsonWhenPresent(content: String) {
    val element = runCatching { SingBoxJson.parseToJsonElement(content) }.getOrNull()
    if (element is JsonObject && element["outbounds"] is JsonArray) {
        SingBoxConfigChecker.check(content)
    }
}

private fun String.isHttpUrl(): Boolean =
    runCatching {
        val uri = URI(trim())
        (uri.scheme == "http" || uri.scheme == "https") && !uri.host.isNullOrBlank()
    }.getOrDefault(false)

private val GroupEditorSectionSpacing = 12.dp
