// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package features.endpoint

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.LocalAppServices
import app.LocalAppStateStore
import app.LocalIsWideScreen
import app.LocalNavigator
import app.LocalUpdateAppState
import app.SingBoxEndpointState
import app.SupportedSingBoxEndpointTypes
import app.collectAppState
import app.navigation.Route
import app.withRemovedManagedOutboundTags
import engine.singbox.config.validateSingBoxRuntimeConfiguration
import features.importing.ImportOperation
import features.importing.ImportResultDialog
import features.importing.ImportResultPresentation
import features.importing.ImportSource
import features.importing.ImportStage
import features.importing.importFailureResultPresentation
import features.importing.readImportUtf8WithinLimit
import features.importing.reportImportFailure
import features.importing.toImportResultPresentation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.asterisk.zcc.abox.R
import ui.clipboard.getPlainText
import ui.clipboard.setPlainText
import ui.components.AsteriskPinnedSearchArea
import ui.components.WarningConfirmDialog
import ui.components.singBoxOptionLabel
import ui.layout.pageContentPaddingWithCutout
import ui.layout.pageListPadding
import ui.theme.AsteriskMotion
import ui.icons.AsteriskIcons as Icons

@Composable
internal fun EndpointListPage(padding: PaddingValues) {
    val stateStore = LocalAppStateStore.current
    val appState by stateStore.collectAppState()
    val updateAppState = LocalUpdateAppState.current
    val navigator = LocalNavigator.current
    val services = LocalAppServices.current
    val isWideScreen = LocalIsWideScreen.current
    val context = LocalContext.current
    val resources = LocalResources.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var query by rememberSaveable { mutableStateOf("") }
    var addMenuExpanded by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    var importResultPresentation by remember {
        mutableStateOf<ImportResultPresentation?>(null)
    }
    var pendingDelete by remember { mutableStateOf<SingBoxEndpointState?>(null) }
    val typeSearchLabels = SupportedSingBoxEndpointTypes.associateWith { type ->
        endpointTypeTitle(type)
    }
    val visibleEndpoints = remember(appState.endpoints, query, typeSearchLabels) {
        appState.endpoints.filter { endpoint ->
            endpoint.matchesQuery(query, typeSearchLabels[endpoint.type].orEmpty())
        }
    }
    val importFailedMessage = stringResource(R.string.endpoint_import_failed)
    val stateChangedMessage = stringResource(R.string.endpoint_import_state_changed)
    val emptyClipboardMessage = stringResource(R.string.endpoint_import_empty_clipboard)
    val copiedMessage = stringResource(R.string.common_copied)
    val countEffectsMotion = AsteriskMotion.fastEffects<Float>()

    suspend fun importContent(
        content: String,
        source: ImportSource,
        fileName: String? = null,
    ) {
        if (importing) return
        importing = true
        var stage = ImportStage.PARSE
        try {
            val snapshot = stateStore.state.value
            val parsed = withContext(Dispatchers.Default) {
                if (fileName == null) {
                    EndpointImportPipeline.parseOutcome(content)
                } else {
                    EndpointImportPipeline.parseFileOutcome(content, fileName)
                }
            }
            val plan = snapshot.planEndpointImport(parsed)
            if (!plan.committed) {
                importResultPresentation =
                    plan.outcome.toImportResultPresentation(committed = false)
                return
            }
            stage = ImportStage.VALIDATE
            withContext(Dispatchers.IO) {
                validateSingBoxRuntimeConfiguration(
                    context = context,
                    state = plan.state,
                )
            }
            stage = ImportStage.COMMIT
            val committed = stateStore.compareAndSet(snapshot, plan.state)
            if (committed) {
                val presentation =
                    plan.outcome.toImportResultPresentation(committed = true)
                if (presentation.showDialog) {
                    importResultPresentation = presentation
                } else {
                    services.tipNotifier.show(
                        resources.getQuantityString(
                            R.plurals.endpoint_import_success,
                            plan.outcome.accepted.size,
                            plan.outcome.accepted.size,
                        ),
                    )
                }
            } else {
                reportImportFailure(
                    operation = ImportOperation.ENDPOINT,
                    source = source,
                    stage = ImportStage.COMMIT,
                )
                importResultPresentation =
                    importFailureResultPresentation(stateChangedMessage)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            reportImportFailure(
                operation = ImportOperation.ENDPOINT,
                source = source,
                stage = stage,
                error = error,
            )
            importResultPresentation =
                importFailureResultPresentation(importFailedMessage)
        } finally {
            importing = false
        }
    }

    fun importClipboard() {
        scope.launch {
            val content = clipboard.getPlainText().orEmpty()
            if (content.isBlank()) {
                services.tipNotifier.show(emptyClipboardMessage)
            } else {
                importContent(content, ImportSource.CLIPBOARD)
            }
        }
    }

    fun importFile() {
        scope.launch {
            try {
                val uri = services.importFilePicker() ?: return@launch
                val file = withContext(Dispatchers.IO) { context.readEndpointImportFile(uri) }
                importContent(file.content, ImportSource.FILE, file.displayName)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                reportImportFailure(
                    operation = ImportOperation.ENDPOINT,
                    source = ImportSource.FILE,
                    stage = ImportStage.READ,
                    error = error,
                )
                importResultPresentation =
                    importFailureResultPresentation(importFailedMessage)
            }
        }
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Column {
                            Text(stringResource(R.string.endpoint_management))
                            AnimatedContent(
                                targetState = visibleEndpoints.size,
                                transitionSpec = AsteriskMotion.fadeThrough(countEffectsMotion),
                                label = "endpoint-count",
                            ) { count ->
                                Text(
                                    pluralStringResource(R.plurals.endpoint_count, count, count),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
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
                        Box {
                            IconButton(
                                onClick = { addMenuExpanded = true },
                                enabled = !importing,
                            ) {
                                AnimatedContent(
                                    targetState = importing,
                                    transitionSpec = AsteriskMotion.fadeThrough(countEffectsMotion),
                                    label = "endpoint-import-progress",
                                ) { inProgress ->
                                    if (inProgress) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(20.dp),
                                            strokeWidth = 2.dp,
                                        )
                                    } else {
                                        Icon(
                                            Icons.Rounded.Add,
                                            stringResource(R.string.endpoint_add),
                                        )
                                    }
                                }
                            }
                            DropdownMenu(
                                expanded = addMenuExpanded,
                                onDismissRequest = { addMenuExpanded = false },
                            ) {
                                SupportedSingBoxEndpointTypes.forEach { type ->
                                    DropdownMenuItem(
                                        text = {
                                            Column {
                                                Text(
                                                    singBoxOptionLabel(
                                                        endpointTypeTitle(type),
                                                        type,
                                                    ),
                                                )
                                                Text(
                                                    endpointTypeSummary(type),
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            }
                                        },
                                        leadingIcon = {
                                            Icon(endpointTypeIcon(type), contentDescription = null)
                                        },
                                        onClick = {
                                            addMenuExpanded = false
                                            navigator.push(
                                                Route.EndpointEdit(
                                                    type = type,
                                                ),
                                            )
                                        },
                                    )
                                }
                                HorizontalDivider()
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text(stringResource(R.string.endpoint_import_clipboard))
                                            Text(
                                                stringResource(
                                                    R.string.endpoint_import_formats_summary,
                                                ),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    },
                                    leadingIcon = {
                                        Icon(Icons.Rounded.ContentPaste, contentDescription = null)
                                    },
                                    onClick = {
                                        addMenuExpanded = false
                                        importClipboard()
                                    },
                                )
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text(stringResource(R.string.endpoint_import_file))
                                            Text(
                                                stringResource(
                                                    R.string.endpoint_import_safety_summary,
                                                ),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    },
                                    leadingIcon = {
                                        Icon(Icons.Rounded.FileDownload, contentDescription = null)
                                    },
                                    onClick = {
                                        addMenuExpanded = false
                                        importFile()
                                    },
                                )
                            }
                        }
                    },
                )
                AsteriskPinnedSearchArea(
                    query = query,
                    onQueryChange = { query = it },
                    placeholder = stringResource(R.string.endpoint_search),
                    clearContentDescription = stringResource(R.string.common_clear),
                )
            }
        },
    ) { innerPadding ->
        val contentPadding = pageContentPaddingWithCutout(
            innerPadding = innerPadding,
            outerPadding = padding,
            isWideScreen = isWideScreen,
        )
        LazyVerticalGrid(
            columns = GridCells.Adaptive(300.dp),
            modifier = Modifier.fillMaxSize(),
            contentPadding = pageListPadding(contentPadding),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (visibleEndpoints.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    EndpointEmptyState(hasQuery = query.isNotBlank())
                }
            } else {
                items(visibleEndpoints, key = SingBoxEndpointState::id) { endpoint ->
                    EndpointCard(
                        endpoint = endpoint,
                        onEdit = {
                            navigator.push(Route.EndpointEdit(endpointId = endpoint.id))
                        },
                        onCopy = {
                            scope.launch {
                                clipboard.setPlainText(endpointJsonForEditing(endpoint.json))
                                services.tipNotifier.show(copiedMessage)
                            }
                        },
                        onDelete = { pendingDelete = endpoint },
                        modifier = Modifier.animateItem(),
                    )
                }
            }
        }
    }

    WarningConfirmDialog(
        show = pendingDelete != null,
        title = stringResource(R.string.endpoint_delete_title),
        summary = stringResource(
            R.string.endpoint_delete_message,
            pendingDelete?.remarks?.ifBlank {
                pendingDelete?.type?.let(typeSearchLabels::get).orEmpty()
            }.orEmpty(),
        ),
        dismissText = stringResource(R.string.common_cancel),
        confirmText = stringResource(R.string.common_delete),
        onDismissRequest = { pendingDelete = null },
        onConfirm = {
            val endpointId = pendingDelete?.id
            if (endpointId != null) {
                updateAppState { state ->
                    val removed = state.endpoints.firstOrNull { endpoint -> endpoint.id == endpointId }
                    state.copy(
                        endpoints = state.endpoints.filterNot { endpoint -> endpoint.id == endpointId },
                    ).withRemovedManagedOutboundTags(setOfNotNull(removed?.tag))
                }
            }
            pendingDelete = null
        },
    )

    importResultPresentation?.let { presentation ->
        ImportResultDialog(
            presentation = presentation,
            onDismissRequest = { importResultPresentation = null },
        )
    }
}

@Composable
private fun EndpointCard(
    endpoint: SingBoxEndpointState,
    onEdit: () -> Unit,
    onCopy: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val cardSpatialMotion = AsteriskMotion.contentSpatial<androidx.compose.ui.unit.IntSize>()
    Card(
        onClick = onEdit,
        modifier = modifier.animateContentSize(cardSpatialMotion),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 14.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    endpoint.remarks.ifBlank { endpointTypeTitle(endpoint.type) },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    endpointTypeTitle(endpoint.type),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(Icons.Rounded.MoreVert, stringResource(R.string.common_more))
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.common_edit)) },
                        leadingIcon = { Icon(Icons.Rounded.Edit, contentDescription = null) },
                        onClick = {
                            menuExpanded = false
                            onEdit()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.common_copy)) },
                        leadingIcon = { Icon(Icons.Rounded.ContentCopy, contentDescription = null) },
                        onClick = {
                            menuExpanded = false
                            onCopy()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.common_delete)) },
                        leadingIcon = { Icon(Icons.Rounded.Delete, contentDescription = null) },
                        onClick = {
                            menuExpanded = false
                            onDelete()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun EndpointEmptyState(hasQuery: Boolean) {
    val effectsMotion = AsteriskMotion.effects<Float>()
    val spatialMotion = AsteriskMotion.spatial<Float>()
    AnimatedVisibility(
        visible = true,
        enter = AsteriskMotion.scaleFadeEnter(effectsMotion, spatialMotion),
        exit = AsteriskMotion.scaleFadeExit(effectsMotion, spatialMotion),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = 72.dp, horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                if (hasQuery) Icons.Rounded.SearchOff else Icons.Rounded.VpnLock,
                contentDescription = null,
                modifier = Modifier.size(42.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(
                    if (hasQuery) R.string.endpoint_search_empty else R.string.endpoint_empty,
                ),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                stringResource(
                    if (hasQuery) {
                        R.string.endpoint_search_empty_summary
                    } else {
                        R.string.endpoint_empty_summary
                    },
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun endpointTypeTitle(type: String): String = stringResource(
    when (type) {
        "wireguard" -> R.string.endpoint_type_wireguard
        "tailscale" -> R.string.endpoint_type_tailscale
        "openconnect" -> R.string.endpoint_type_openconnect
        "openvpn-client" -> R.string.endpoint_type_openvpn_client
        else -> R.string.endpoint_editor_invalid
    },
)

@Composable
internal fun endpointTypeSummary(type: String): String = stringResource(
    when (type) {
        "wireguard" -> R.string.endpoint_type_wireguard_summary
        "tailscale" -> R.string.endpoint_type_tailscale_summary
        "openconnect" -> R.string.endpoint_type_openconnect_summary
        "openvpn-client" -> R.string.endpoint_type_openvpn_client_summary
        else -> R.string.endpoint_editor_invalid
    },
)

internal fun endpointTypeIcon(type: String): ImageVector = when (type) {
    "wireguard" -> Icons.Rounded.Lan
    "tailscale" -> Icons.Rounded.Hub
    "openconnect" -> Icons.Rounded.Security
    "openvpn-client" -> Icons.Rounded.VpnLock
    else -> Icons.Rounded.Router
}

private fun SingBoxEndpointState.matchesQuery(query: String, localizedType: String): Boolean {
    val normalized = query.trim()
    return normalized.isEmpty() ||
        remarks.contains(normalized, ignoreCase = true) ||
        type.contains(normalized, ignoreCase = true) ||
        localizedType.contains(normalized, ignoreCase = true)
}

private data class EndpointImportFile(
    val content: String,
    val displayName: String?,
)

private fun Context.readEndpointImportFile(uri: Uri): EndpointImportFile {
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
    } ?: throw IllegalArgumentException()
    return EndpointImportFile(content, displayName)
}
