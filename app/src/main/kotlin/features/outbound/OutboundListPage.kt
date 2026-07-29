// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package features.outbound

import android.content.Context
import android.net.Uri
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import app.LocalAppServices
import app.LocalAppStateStore
import app.LocalIsWideScreen
import app.LocalNavigator
import app.LocalUpdateAppState
import app.OutboundGroupState
import app.OutboundState
import app.collectAppState
import app.modes.OutboundListLayoutAuto
import app.modes.OutboundListLayoutDouble
import app.modes.OutboundListLayoutMultiple
import app.modes.OutboundListLayoutSingle
import app.modes.OutboundListSortDefault
import app.modes.OutboundListSortLatency
import app.modes.OutboundListSortName
import app.modes.OutboundListSortType
import app.navigation.Route
import app.withRemovedManagedOutbound
import engine.singbox.config.validateSingBoxRuntimeConfiguration
import features.importing.ImportOperation
import features.importing.ImportResultDialog
import features.importing.ImportResultPresentation
import features.importing.ImportSource
import features.importing.ImportStage
import features.importing.importFailureResultPresentation
import features.importing.importFailureContext
import features.importing.readImportUtf8WithinLimit
import features.importing.reportImportFailure
import features.importing.toImportResultPresentation
import features.singbox.displaySingBoxProtocolName
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.asterisk.zcc.abox.R
import sh.calvin.reorderable.ReorderableItem
import ui.clipboard.getPlainText
import ui.clipboard.setPlainText
import ui.components.AsteriskExpressiveCard
import ui.components.AsteriskFilterChip
import ui.components.AsteriskInfoChip
import ui.components.AsteriskPinnedSearchArea
import ui.components.WarningConfirmDialog
import ui.components.draggedCardShadow
import ui.components.longPressReorderDragHandle
import ui.components.rememberAsteriskReorderableLazyGridState
import ui.components.singBoxOptionLabel
import ui.layout.pageContentPaddingWithCutout
import ui.layout.pageListPadding
import ui.theme.AsteriskMotion
import androidx.compose.foundation.lazy.grid.items as gridItems
import ui.icons.AsteriskIcons as Icons

private enum class OutboundImportMenuLevel {
    MAIN,
    MANUAL,
}

private enum class OutboundOptionsMenuLevel {
    MAIN,
    LAYOUT,
    SORT,
}

@Composable
internal fun OutboundListPage(
    padding: PaddingValues,
) {
    val appState by LocalAppStateStore.current.collectAppState()
    val updateAppState = LocalUpdateAppState.current
    val navigator = LocalNavigator.current
    val services = LocalAppServices.current
    val isWideScreen = LocalIsWideScreen.current
    val context = LocalContext.current
    val resources = LocalResources.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val groups = appState.outboundGroups
    val pagerState = rememberPagerState(pageCount = { groups.size.coerceAtLeast(1) })
    var importMenuExpanded by remember { mutableStateOf(false) }
    var importMenuLevel by remember { mutableStateOf(OutboundImportMenuLevel.MAIN) }
    val manualImportMenuScrollState = rememberScrollState()
    val manualImportMenuHeight = with(LocalDensity.current) {
        outboundImportManualMenuHeightDp(
            windowHeightDp = LocalWindowInfo.current.containerSize.height.toDp().value.toInt(),
        ).dp
    }
    var query by rememberSaveable { mutableStateOf("") }
    var pendingDelete by remember { mutableStateOf<OutboundState?>(null) }
    var importResultPresentation by remember {
        mutableStateOf<ImportResultPresentation?>(null)
    }
    var pingingOutboundIds by remember { mutableStateOf<Set<Int>>(emptySet()) }
    val outboundPinger = remember { AndroidOutboundPinger() }
    val selectedGroup = groups.getOrNull(pagerState.currentPage) ?: groups.firstOrNull()
    val selectedOutbounds = appState.outbounds.filter { it.groupId == selectedGroup?.id }
    val visibleCount = selectedOutbounds.count { it.matchesQuery(query) }
    val columns = resolveOutboundListColumns(appState.outboundListLayout, isWideScreen)
    val importFailedMessage = stringResource(R.string.outbound_import_failed)
    val emptyClipboardMessage = stringResource(R.string.outbound_import_empty_clipboard)
    val copiedMessage = stringResource(R.string.outbound_editor_copied)
    val noPingTargetsMessage = stringResource(R.string.outbound_ping_no_targets)
    val pingFailedMessage = stringResource(R.string.outbound_ping_failed)

    LaunchedEffect(groups.map(OutboundGroupState::id)) {
        if (pagerState.currentPage > groups.lastIndex && groups.isNotEmpty()) {
            pagerState.scrollToPage(groups.lastIndex)
        }
    }

    suspend fun importContent(content: String, source: ImportSource) {
        val targetGroupId = selectedGroup?.id ?: return
        var stage = ImportStage.PARSE
        try {
            val result = withContext(Dispatchers.Default) {
                parseOutboundImportContent(content)
            }
            val plan = appState.planOutboundImport(
                groupId = targetGroupId,
                parsed = result,
                replaceGroup = false,
                strict = false,
            )
            if (!plan.committed) {
                importResultPresentation = plan.outcome.toImportResultPresentation(
                    committed = false,
                )
                return
            }
            val candidateState = plan.state
            stage = ImportStage.VALIDATE
            withContext(Dispatchers.IO) {
                validateSingBoxRuntimeConfiguration(context, candidateState)
            }
            stage = ImportStage.COMMIT
            var committed = false
            updateAppState { state ->
                if (state === appState) {
                    committed = true
                    candidateState
                } else {
                    state
                }
            }
            if (committed) {
                val presentation = plan.outcome.toImportResultPresentation(committed = true)
                if (presentation.showDialog) {
                    importResultPresentation = presentation
                } else {
                    services.tipNotifier.show(
                        resources.getQuantityString(
                            R.plurals.outbound_import_success,
                            plan.outcome.accepted.size,
                            plan.outcome.accepted.size,
                        ),
                    )
                }
            } else {
                reportImportFailure(
                    operation = ImportOperation.OUTBOUND,
                    source = source,
                    stage = stage,
                )
                importResultPresentation =
                    plan.outcome.toImportResultPresentation(committed = false)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            reportImportFailure(
                operation = ImportOperation.OUTBOUND,
                source = source,
                stage = stage,
            )
            importResultPresentation = importFailureResultPresentation(
                error.message ?: importFailedMessage,
            )
        }
    }

    fun importQrCode() {
        scope.launch {
            try {
                services.qrCodeScanner()
                    ?.trim()
                    ?.takeIf(String::isNotBlank)
                    ?.let { importContent(it, ImportSource.QR_CODE) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                services.tipNotifier.showError(
                    error,
                    importFailedMessage,
                    importFailureContext(
                        ImportOperation.OUTBOUND,
                        ImportSource.QR_CODE,
                        ImportStage.READ,
                    ),
                )
            }
        }
    }

    fun importClipboard() {
        scope.launch {
            try {
                val content = clipboard.getPlainText().orEmpty()
                require(content.isNotBlank()) { emptyClipboardMessage }
                importContent(content, ImportSource.CLIPBOARD)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                services.tipNotifier.showError(
                    error,
                    importFailedMessage,
                    importFailureContext(
                        ImportOperation.OUTBOUND,
                        ImportSource.CLIPBOARD,
                        ImportStage.READ,
                    ),
                )
            }
        }
    }

    fun importFile() {
        scope.launch {
            try {
                val uri = services.importFilePicker() ?: return@launch
                val content = withContext(Dispatchers.IO) { context.readOutboundImportFile(uri) }
                importContent(content, ImportSource.FILE)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                services.tipNotifier.showError(
                    error,
                    importFailedMessage,
                    importFailureContext(
                        ImportOperation.OUTBOUND,
                        ImportSource.FILE,
                        ImportStage.READ,
                    ),
                )
            }
        }
    }

    fun pingOutbounds(
        targets: List<OutboundState>,
        showSingleResult: Boolean,
    ) {
        val testable = targets.filter { outbound -> outbound.pingHostOrNull() != null }
        if (testable.isEmpty()) {
            scope.launch { services.tipNotifier.show(noPingTargetsMessage) }
            return
        }
        val targetIds = testable.mapTo(mutableSetOf(), OutboundState::id)
        if (targetIds.any { outboundId -> outboundId in pingingOutboundIds }) return
        scope.launch {
            pingingOutboundIds = pingingOutboundIds + targetIds
            updateAppState { state ->
                state.copy(
                    outbounds = state.outbounds.map { outbound ->
                        if (outbound.id in targetIds) outbound.copy(pingMillis = null) else outbound
                    },
                )
            }
            try {
                val semaphore = Semaphore(OutboundPingConcurrency)
                val results = supervisorScope {
                    testable.map { outbound ->
                        async {
                            val latency = semaphore.withPermit {
                                pingOrFailure { outboundPinger.ping(outbound) }
                            }
                            updateAppState { state ->
                                state.copy(
                                    outbounds = state.outbounds.map { current ->
                                        if (
                                            current.id == outbound.id &&
                                            current.json == outbound.json
                                        ) {
                                            current.copy(pingMillis = latency)
                                        } else {
                                            current
                                        }
                                    },
                                )
                            }
                            outbound to latency
                        }
                    }.awaitAll()
                }
                if (showSingleResult) {
                    results.singleOrNull()?.let { (outbound, latency) ->
                        val resultText = if (latency >= 0L) {
                            resources.getString(R.string.outbound_ping_latency, latency)
                        } else {
                            pingFailedMessage
                        }
                        services.tipNotifier.show(
                            resources.getString(
                                R.string.outbound_ping_result,
                                outbound.remarks,
                                resultText,
                            ),
                        )
                    }
                } else {
                    services.tipNotifier.show(
                        resources.getQuantityString(
                            R.plurals.outbound_ping_complete,
                            testable.size,
                            testable.size,
                        ),
                    )
                }
            } finally {
                pingingOutboundIds = pingingOutboundIds - targetIds
            }
        }
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Column {
                            Text(stringResource(R.string.outbound_management))
                            val countEffectsMotion = AsteriskMotion.fastEffects<Float>()
                            AnimatedContent(
                                targetState = visibleCount,
                                transitionSpec = {
                                    fadeIn(animationSpec = countEffectsMotion)
                                        .togetherWith(fadeOut(animationSpec = countEffectsMotion))
                                },
                                label = "outbound-count",
                            ) { count ->
                                Text(
                                    text = pluralStringResource(R.plurals.outbound_count, count, count),
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
                                onClick = {
                                    importMenuLevel = OutboundImportMenuLevel.MAIN
                                    importMenuExpanded = true
                                },
                                enabled = selectedGroup != null,
                            ) {
                                Icon(Icons.Rounded.Add, stringResource(R.string.outbound_import))
                            }
                            val menuSpatialMotion = AsteriskMotion.fastSpatial<IntOffset>()
                            val menuSizeMotion = AsteriskMotion.fastSpatial<IntSize>()
                            val menuEffectsMotion = AsteriskMotion.fastEffects<Float>()
                            DropdownMenu(
                                expanded = importMenuExpanded,
                                onDismissRequest = { importMenuExpanded = false },
                                modifier = Modifier.width(OutboundImportMenuWidth),
                            ) {
                                AnimatedContent(
                                    targetState = importMenuLevel,
                                    modifier = Modifier.fillMaxWidth(),
                                    transitionSpec = {
                                        val direction = if (
                                            targetState == OutboundImportMenuLevel.MAIN
                                        ) {
                                            -1
                                        } else {
                                            1
                                        }
                                        (
                                            slideInHorizontally(
                                                animationSpec = menuSpatialMotion,
                                                initialOffsetX = { width -> direction * width / 5 },
                                            ) + fadeIn(animationSpec = menuEffectsMotion)
                                            ).togetherWith(
                                            slideOutHorizontally(
                                                animationSpec = menuSpatialMotion,
                                                targetOffsetX = { width -> -direction * width / 5 },
                                            ) + fadeOut(animationSpec = menuEffectsMotion),
                                        ).using(
                                            SizeTransform(sizeAnimationSpec = { _, _ -> menuSizeMotion }),
                                        )
                                    },
                                    contentAlignment = Alignment.TopStart,
                                    label = "outbound-import-level",
                                ) { level ->
                                    Column(modifier = Modifier.fillMaxWidth()) {
                                        when (level) {
                                            OutboundImportMenuLevel.MAIN -> {
                                                OutboundMenuItem(
                                                    text = stringResource(R.string.outbound_import_qr),
                                                    icon = Icons.Rounded.QrCodeScanner,
                                                    onClick = {
                                                        importMenuExpanded = false
                                                        importQrCode()
                                                    },
                                                )
                                                OutboundMenuItem(
                                                    text = stringResource(R.string.outbound_import_clipboard),
                                                    icon = Icons.Rounded.ContentCopy,
                                                    onClick = {
                                                        importMenuExpanded = false
                                                        importClipboard()
                                                    },
                                                )
                                                OutboundMenuItem(
                                                    text = stringResource(R.string.outbound_import_file),
                                                    icon = Icons.Rounded.FileUpload,
                                                    onClick = {
                                                        importMenuExpanded = false
                                                        importFile()
                                                    },
                                                )
                                                HorizontalDivider()
                                                DropdownMenuItem(
                                                    text = {
                                                        Text(
                                                            stringResource(R.string.outbound_import_manual),
                                                        )
                                                    },
                                                    leadingIcon = {
                                                        Icon(Icons.Rounded.EditNote, contentDescription = null)
                                                    },
                                                    trailingIcon = {
                                                        Icon(
                                                            Icons.Rounded.ChevronRight,
                                                            contentDescription = null,
                                                        )
                                                    },
                                                    onClick = {
                                                        scope.launch {
                                                            manualImportMenuScrollState.scrollTo(0)
                                                            importMenuLevel =
                                                                OutboundImportMenuLevel.MANUAL
                                                        }
                                                    },
                                                )
                                            }
                                            OutboundImportMenuLevel.MANUAL -> {
                                                Column(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .height(manualImportMenuHeight),
                                                ) {
                                                    DropdownMenuItem(
                                                        text = {
                                                            Text(
                                                                stringResource(
                                                                    R.string.outbound_import_manual,
                                                                ),
                                                            )
                                                        },
                                                        leadingIcon = {
                                                            Icon(
                                                                Icons.Rounded.ChevronLeft,
                                                                contentDescription = null,
                                                            )
                                                        },
                                                        onClick = {
                                                            importMenuLevel =
                                                                OutboundImportMenuLevel.MAIN
                                                        },
                                                    )
                                                    HorizontalDivider()
                                                    Column(
                                                        modifier = Modifier
                                                            .fillMaxWidth()
                                                            .weight(1f)
                                                            .verticalScroll(
                                                                manualImportMenuScrollState,
                                                            ),
                                                    ) {
                                                        OutboundEditorRegistry.descriptors.forEach { descriptor ->
                                                            OutboundMenuItem(
                                                                text = singBoxOptionLabel(
                                                                    descriptor.title,
                                                                    descriptor.type,
                                                                ),
                                                                icon = outboundTypeIcon(
                                                                    descriptor.type,
                                                                ),
                                                                onClick = {
                                                                    importMenuExpanded = false
                                                                    selectedGroup?.let { group ->
                                                                        navigator.push(
                                                                            Route.OutboundEdit(
                                                                                groupId = group.id,
                                                                                type = descriptor.type,
                                                                            ),
                                                                        )
                                                                    }
                                                                },
                                                            )
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        OutboundOptionsMenu(
                            layout = appState.outboundListLayout,
                            sort = appState.outboundListSort,
                            pingRunning = selectedOutbounds.any { outbound ->
                                outbound.id in pingingOutboundIds
                            },
                            onPing = {
                                pingOutbounds(
                                    targets = selectedOutbounds,
                                    showSingleResult = false,
                                )
                            },
                            onLayoutChange = { layout ->
                                updateAppState { state -> state.copy(outboundListLayout = layout) }
                            },
                            onSortChange = { sort ->
                                updateAppState { state -> state.copy(outboundListSort = sort) }
                            },
                        )
                    },
                )
                AsteriskPinnedSearchArea(
                    query = query,
                    onQueryChange = { query = it },
                    placeholder = stringResource(R.string.outbound_search),
                    clearContentDescription = stringResource(R.string.common_clear),
                ) {
                    if (groups.isNotEmpty()) {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(groups, key = OutboundGroupState::id) { group ->
                                val index = groups.indexOfFirst { it.id == group.id }
                                val selected = pagerState.currentPage == index
                                AsteriskFilterChip(
                                    selected = selected,
                                    onClick = {
                                        scope.launch { pagerState.animateScrollToPage(index) }
                                    },
                                    label = buildString {
                                        append(group.displayName())
                                        append(" · ")
                                        append(appState.outbounds.count { it.groupId == group.id })
                                    },
                                )
                            }
                        }
                    }
                }
            }
        },
    ) { innerPadding ->
        val contentPadding = pageContentPaddingWithCutout(
            innerPadding = innerPadding,
            outerPadding = padding,
            isWideScreen = isWideScreen,
        )
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            verticalAlignment = Alignment.Top,
        ) { page ->
            val group = groups.getOrNull(page)
            val outbounds = appState.outbounds
                .filter { outbound -> outbound.groupId == group?.id && outbound.matchesQuery(query) }
                .sortedForOutboundList(appState.outboundListSort)
            val reorderEnabled =
                appState.outboundListSort == OutboundListSortDefault && query.isBlank()
            val dragScrollThresholdBottomPadding =
                pageListPadding(contentPadding).calculateBottomPadding()
            OutboundPage(
                outbounds = outbounds,
                contentPadding = pageListPadding(
                    contentPadding = contentPadding,
                    bottomExtra = outboundListBottomExtraDp().dp,
                ),
                dragScrollThresholdBottomPadding = dragScrollThresholdBottomPadding,
                hasQuery = query.isNotBlank(),
                columns = columns,
                reorderEnabled = reorderEnabled,
                pingingOutboundIds = pingingOutboundIds,
                onMove = { fromIndex, toIndex ->
                    updateAppState { state ->
                        state.copy(
                            outbounds = state.outbounds.reorderVisibleOutbounds(
                                visibleOutbounds = outbounds,
                                fromIndex = fromIndex,
                                toIndex = toIndex,
                            ),
                        )
                    }
                },
                onEdit = { outbound ->
                    navigator.push(
                        Route.OutboundEdit(
                            outboundId = outbound.id,
                            groupId = outbound.groupId,
                            type = outbound.type,
                        ),
                    )
                },
                onCopy = { outbound ->
                    scope.launch {
                        clipboard.setPlainText(outboundJsonWithoutManagedIdentity(outbound.json))
                        services.tipNotifier.show(copiedMessage)
                    }
                },
                onPing = { outbound ->
                    pingOutbounds(targets = listOf(outbound), showSingleResult = true)
                },
                onDelete = { pendingDelete = it },
            )
        }
    }

    WarningConfirmDialog(
        show = pendingDelete != null,
        title = stringResource(R.string.outbound_delete_title),
        summary = stringResource(R.string.outbound_delete_message, pendingDelete?.remarks.orEmpty()),
        dismissText = stringResource(R.string.common_cancel),
        confirmText = stringResource(R.string.common_delete),
        onDismissRequest = { pendingDelete = null },
        onConfirm = {
            val id = pendingDelete?.id
            if (id != null) {
                updateAppState { state ->
                    state.withRemovedManagedOutbound(id)
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
private fun OutboundPage(
    outbounds: List<OutboundState>,
    contentPadding: PaddingValues,
    dragScrollThresholdBottomPadding: androidx.compose.ui.unit.Dp,
    hasQuery: Boolean,
    columns: Int,
    reorderEnabled: Boolean,
    pingingOutboundIds: Set<Int>,
    onMove: (fromIndex: Int, toIndex: Int) -> Unit,
    onEdit: (OutboundState) -> Unit,
    onCopy: (OutboundState) -> Unit,
    onPing: (OutboundState) -> Unit,
    onDelete: (OutboundState) -> Unit,
) {
    val gridState = rememberLazyGridState()
    val reorderableState = rememberAsteriskReorderableLazyGridState(
        lazyGridState = gridState,
        itemCount = outbounds.size,
        scrollThresholdPadding = PaddingValues(
            bottom = dragScrollThresholdBottomPadding,
        ),
        onMove = onMove,
    )
    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        state = gridState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(OutboundGridSpacing),
        horizontalArrangement = Arrangement.spacedBy(OutboundGridSpacing),
    ) {
        if (outbounds.isEmpty()) {
            item(
                key = "empty",
                span = { GridItemSpan(maxLineSpan) },
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = OutboundEmptyStateMinHeight)
                        .padding(horizontal = 28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        imageVector = if (hasQuery) Icons.Rounded.SearchOff else Icons.Rounded.Router,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(48.dp),
                    )
                    Text(
                        text = stringResource(
                            if (hasQuery) R.string.outbound_search_empty else R.string.outbound_empty,
                        ),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 16.dp),
                    )
                    Text(
                        text = stringResource(
                            if (hasQuery) R.string.outbound_search_empty_summary
                            else R.string.outbound_empty_summary,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        } else {
            gridItems(
                items = outbounds,
                key = OutboundState::id,
                contentType = { "outbound" },
            ) { outbound ->
                ReorderableItem(
                    state = reorderableState.reorderableState,
                    key = outbound.id,
                    enabled = reorderEnabled,
                    modifier = Modifier.fillMaxWidth(),
                    animateItemModifier = Modifier.animateItem(),
                ) { isDragging ->
                    OutboundCard(
                        outbound = outbound,
                        compact = columns > 1,
                        pinging = outbound.id in pingingOutboundIds,
                        isDragging = isDragging && reorderEnabled,
                        onEdit = { onEdit(outbound) },
                        onCopy = { onCopy(outbound) },
                        onPing = { onPing(outbound) },
                        onDelete = { onDelete(outbound) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .longPressReorderDragHandle(
                                scope = this,
                                enabled = reorderEnabled && outbounds.size > 1,
                                state = reorderableState,
                            ),
                    )
                }
            }
        }
    }
}

@Composable
private fun OutboundCard(
    outbound: OutboundState,
    compact: Boolean,
    pinging: Boolean,
    isDragging: Boolean,
    onEdit: () -> Unit,
    onCopy: () -> Unit,
    onPing: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val containerColor by animateColorAsState(
        targetValue = if (isDragging) {
            MaterialTheme.colorScheme.surfaceContainerHigh
        } else {
            MaterialTheme.colorScheme.surfaceContainer
        },
        animationSpec = AsteriskMotion.effects(),
        label = "outbound-card-color",
    )
    val scale by animateFloatAsState(
        targetValue = if (isDragging) 1.025f else 1f,
        animationSpec = AsteriskMotion.fastSpatial(),
        label = "outbound-drag-scale",
    )
    val shadowAlpha by animateFloatAsState(
        targetValue = if (isDragging) 1f else 0f,
        animationSpec = AsteriskMotion.fastEffects(),
        label = "outbound-drag-shadow",
    )
    AsteriskExpressiveCard(
        onClick = onEdit,
        modifier = modifier
            .fillMaxWidth()
            .height(OutboundCardHeight)
            .zIndex(if (isDragging) 1f else 0f)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .draggedCardShadow(
                alpha = shadowAlpha,
                color = MaterialTheme.colorScheme.primary,
            )
            .animateContentSize(animationSpec = AsteriskMotion.contentSpatial()),
        containerColor = containerColor,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(OutboundCardPadding),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
            ) {
                Column(modifier = Modifier.weight(1f).padding(top = 2.dp)) {
                    Text(
                        text = outbound.remarks.ifBlank { outbound.type },
                        style = if (compact) {
                            MaterialTheme.typography.titleSmall.copy(
                                fontSize = 13.sp,
                                lineHeight = 17.sp,
                            )
                        } else {
                            MaterialTheme.typography.titleMedium
                        },
                        fontWeight = FontWeight.SemiBold,
                        maxLines = if (compact) 2 else 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    outbound.cardEndpointSummary(compact)?.let { summary ->
                        Text(
                            text = summary,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
                OutboundCardMenu(
                    pingEnabled = outbound.pingHostOrNull() != null && !pinging,
                    onEdit = onEdit,
                    onCopy = onCopy,
                    onPing = onPing,
                    onDelete = onDelete,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().height(28.dp).padding(end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                AsteriskInfoChip(
                    text = outbound.type.displaySingBoxProtocolName(compact = compact),
                    modifier = Modifier.weight(1f, fill = false),
                    textStyle = if (compact) {
                        MaterialTheme.typography.labelSmall.copy(
                            fontSize = 10.sp,
                            lineHeight = 14.sp,
                        )
                    } else {
                        MaterialTheme.typography.labelSmall
                    },
                )
                OutboundPingStatus(outbound.pingMillis, pinging)
            }
        }
    }
}

@Composable
private fun OutboundCardMenu(
    pingEnabled: Boolean,
    onEdit: () -> Unit,
    onCopy: () -> Unit,
    onPing: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier.offset(
            x = OutboundCardMenuIconOffset.x.dp,
            y = OutboundCardMenuIconOffset.y.dp,
        ),
    ) {
        IconButton(onClick = { menuExpanded = true }) {
            Icon(
                imageVector = Icons.Rounded.MoreVert,
                contentDescription = stringResource(R.string.common_more),
            )
        }
        DropdownMenu(
            expanded = menuExpanded,
            onDismissRequest = { menuExpanded = false },
        ) {
            OutboundMenuItem(
                text = stringResource(R.string.outbound_ping),
                icon = Icons.Rounded.Speed,
                enabled = pingEnabled,
                onClick = {
                    menuExpanded = false
                    onPing()
                },
            )
            HorizontalDivider()
            OutboundMenuItem(
                text = stringResource(R.string.common_edit),
                icon = Icons.Rounded.Edit,
                onClick = {
                    menuExpanded = false
                    onEdit()
                },
            )
            OutboundMenuItem(
                text = stringResource(R.string.common_copy),
                icon = Icons.Rounded.ContentCopy,
                onClick = {
                    menuExpanded = false
                    onCopy()
                },
            )
            OutboundMenuItem(
                text = stringResource(R.string.common_delete),
                icon = Icons.Rounded.Delete,
                onClick = {
                    menuExpanded = false
                    onDelete()
                },
            )
        }
    }
}

@Composable
private fun OutboundPingStatus(
    pingMillis: Long?,
    pinging: Boolean,
) {
    if (pinging) {
        val description = stringResource(R.string.outbound_ping_running)
        CircularProgressIndicator(
            modifier = Modifier
                .size(18.dp)
                .semantics { contentDescription = description },
            strokeWidth = 2.dp,
        )
        return
    }
    pingMillis ?: return
    Text(
        text = if (pingMillis >= 0L) {
            stringResource(R.string.outbound_ping_latency, pingMillis)
        } else {
            stringResource(R.string.outbound_ping_failed)
        },
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Medium,
        color = outboundPingColor(pingMillis),
        maxLines = 1,
    )
}

@Composable
private fun outboundPingColor(pingMillis: Long): Color {
    return when {
        pingMillis < 0L -> MaterialTheme.colorScheme.error
        pingMillis < 100L -> MaterialTheme.colorScheme.tertiary
        pingMillis < 300L -> MaterialTheme.colorScheme.primary
        pingMillis < 600L -> MaterialTheme.colorScheme.secondary
        else -> MaterialTheme.colorScheme.error
    }
}

@Composable
private fun OutboundOptionsMenu(
    layout: Int,
    sort: Int,
    pingRunning: Boolean,
    onPing: () -> Unit,
    onLayoutChange: (Int) -> Unit,
    onSortChange: (Int) -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    var level by rememberSaveable { mutableStateOf(OutboundOptionsMenuLevel.MAIN) }
    val dismissMenu = {
        expanded = false
        level = OutboundOptionsMenuLevel.MAIN
    }
    val layoutLabel = stringResource(
        when (layout) {
            OutboundListLayoutSingle -> R.string.outbound_option_layout_single
            OutboundListLayoutDouble -> R.string.outbound_option_layout_double
            OutboundListLayoutMultiple -> R.string.outbound_option_layout_multiple
            else -> R.string.outbound_option_layout_auto
        },
    )
    val sortLabel = stringResource(
        when (sort) {
            OutboundListSortName -> R.string.outbound_sort_remarks
            OutboundListSortLatency -> R.string.outbound_sort_latency
            OutboundListSortType -> R.string.outbound_sort_type
            else -> R.string.outbound_sort_original
        },
    )
    val menuSpatialMotion = AsteriskMotion.fastSpatial<IntOffset>()
    val menuSizeMotion = AsteriskMotion.fastSpatial<IntSize>()
    val menuEffectsMotion = AsteriskMotion.fastEffects<Float>()
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.Rounded.MoreVert, stringResource(R.string.outbound_options))
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = dismissMenu,
            modifier = Modifier.width(OutboundOptionsMenuWidth),
        ) {
            AnimatedContent(
                targetState = level,
                modifier = Modifier.fillMaxWidth(),
                transitionSpec = {
                    val direction = if (targetState == OutboundOptionsMenuLevel.MAIN) -1 else 1
                    (
                        slideInHorizontally(
                            animationSpec = menuSpatialMotion,
                            initialOffsetX = { width -> direction * width / 5 },
                        ) + fadeIn(animationSpec = menuEffectsMotion)
                        ).togetherWith(
                        slideOutHorizontally(
                            animationSpec = menuSpatialMotion,
                            targetOffsetX = { width -> -direction * width / 5 },
                        ) + fadeOut(animationSpec = menuEffectsMotion),
                    ).using(
                        SizeTransform(sizeAnimationSpec = { _, _ -> menuSizeMotion }),
                    )
                },
                contentAlignment = Alignment.TopStart,
                label = "outbound-options-level",
            ) { currentLevel ->
                Column(modifier = Modifier.fillMaxWidth()) {
                    when (currentLevel) {
                        OutboundOptionsMenuLevel.MAIN -> {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.outbound_ping_group)) },
                                leadingIcon = {
                                    if (pingRunning) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(20.dp),
                                            strokeWidth = 2.dp,
                                        )
                                    } else {
                                        Icon(Icons.Rounded.Speed, contentDescription = null)
                                    }
                                },
                                enabled = !pingRunning,
                                onClick = {
                                    dismissMenu()
                                    onPing()
                                },
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(stringResource(R.string.outbound_option_layout))
                                        Text(
                                            layoutLabel,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                },
                                leadingIcon = {
                                    Icon(Icons.Rounded.ViewModule, contentDescription = null)
                                },
                                trailingIcon = {
                                    Icon(Icons.Rounded.ChevronRight, contentDescription = null)
                                },
                                onClick = { level = OutboundOptionsMenuLevel.LAYOUT },
                            )
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(stringResource(R.string.outbound_sort))
                                        Text(
                                            sortLabel,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                },
                                leadingIcon = {
                                    Icon(Icons.AutoMirrored.Rounded.Sort, contentDescription = null)
                                },
                                trailingIcon = {
                                    Icon(Icons.Rounded.ChevronRight, contentDescription = null)
                                },
                                onClick = { level = OutboundOptionsMenuLevel.SORT },
                            )
                        }

                        OutboundOptionsMenuLevel.LAYOUT -> {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.outbound_option_layout)) },
                                leadingIcon = {
                                    Icon(Icons.Rounded.ChevronLeft, contentDescription = null)
                                },
                                onClick = { level = OutboundOptionsMenuLevel.MAIN },
                            )
                            HorizontalDivider()
                            listOf(
                                Triple(
                                    OutboundListLayoutAuto,
                                    R.string.outbound_option_layout_auto,
                                    Icons.Rounded.AutoAwesome,
                                ),
                                Triple(
                                    OutboundListLayoutSingle,
                                    R.string.outbound_option_layout_single,
                                    Icons.Rounded.ViewAgenda,
                                ),
                                Triple(
                                    OutboundListLayoutDouble,
                                    R.string.outbound_option_layout_double,
                                    Icons.Rounded.ViewColumn,
                                ),
                                Triple(
                                    OutboundListLayoutMultiple,
                                    R.string.outbound_option_layout_multiple,
                                    Icons.Rounded.GridView,
                                ),
                            ).forEach { (value, label, icon) ->
                                DropdownMenuItem(
                                    text = { Text(stringResource(label)) },
                                    leadingIcon = { Icon(icon, contentDescription = null) },
                                    trailingIcon = {
                                        RadioButton(selected = layout == value, onClick = null)
                                    },
                                    onClick = {
                                        dismissMenu()
                                        onLayoutChange(value)
                                    },
                                )
                            }
                        }

                        OutboundOptionsMenuLevel.SORT -> {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.outbound_sort)) },
                                leadingIcon = {
                                    Icon(Icons.Rounded.ChevronLeft, contentDescription = null)
                                },
                                onClick = { level = OutboundOptionsMenuLevel.MAIN },
                            )
                            HorizontalDivider()
                            listOf(
                                Triple(
                                    OutboundListSortDefault,
                                    R.string.outbound_sort_original,
                                    Icons.AutoMirrored.Rounded.Sort,
                                ),
                                Triple(
                                    OutboundListSortName,
                                    R.string.outbound_sort_remarks,
                                    Icons.Rounded.SortByAlpha,
                                ),
                                Triple(
                                    OutboundListSortLatency,
                                    R.string.outbound_sort_latency,
                                    Icons.Rounded.Speed,
                                ),
                                Triple(
                                    OutboundListSortType,
                                    R.string.outbound_sort_type,
                                    Icons.Rounded.Tune,
                                ),
                            ).forEach { (value, label, icon) ->
                                DropdownMenuItem(
                                    text = { Text(stringResource(label)) },
                                    leadingIcon = { Icon(icon, contentDescription = null) },
                                    trailingIcon = {
                                        RadioButton(selected = sort == value, onClick = null)
                                    },
                                    onClick = {
                                        dismissMenu()
                                        onSortChange(value)
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun outboundTypeIcon(type: String): ImageVector = when (type) {
    "socks" -> Icons.Rounded.Lan
    "http", "naive" -> Icons.Rounded.Http
    "shadowsocks" -> Icons.Rounded.Security
    "vmess" -> Icons.Rounded.Hub
    "trojan" -> Icons.Rounded.VpnLock
    "hysteria", "hysteria2", "tuic" -> Icons.Rounded.Speed
    "vless" -> Icons.Rounded.Route
    "shadowtls", "anytls" -> Icons.Rounded.Lock
    "snell" -> Icons.Rounded.Cable
    "ssh" -> Icons.Rounded.Terminal
    else -> Icons.Rounded.Router
}

@Composable
private fun OutboundMenuItem(
    text: String,
    icon: ImageVector,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        text = { Text(text) },
        leadingIcon = { Icon(icon, contentDescription = null) },
        enabled = enabled,
        onClick = onClick,
    )
}

private val OutboundImportMenuWidth = 224.dp
private val OutboundOptionsMenuWidth = 224.dp
private val OutboundGridSpacing = OutboundGridSpacingDp.dp
private val OutboundCardHeight = OutboundCardHeightDp.dp
private val OutboundCardPadding =
    PaddingValues(start = 10.dp, top = 14.dp, end = 10.dp, bottom = 10.dp)
private val OutboundCardMenuIconOffset = outboundCardMenuIconOffsetDp(
    touchTargetDp = 48,
    iconSizeDp = 24,
)
private val OutboundEmptyStateMinHeight = 320.dp
private const val OutboundPingConcurrency = 8
private const val OutboundImportManualMenuMaxHeightDp = 500
// Mirrors Material3's 48dp window margin and 8dp content padding on both vertical edges.
private const val OutboundImportMenuVerticalChromeDp = 2 * (48 + 8)

internal fun outboundImportManualMenuHeightDp(windowHeightDp: Int): Int =
    minOf(
        OutboundImportManualMenuMaxHeightDp,
        (windowHeightDp - OutboundImportMenuVerticalChromeDp).coerceAtLeast(0),
    )

@Composable
private fun OutboundGroupState.displayName(): String = name

private fun Context.readOutboundImportFile(uri: Uri): String {
    val content = contentResolver.openInputStream(uri)?.use { input ->
        input.readImportUtf8WithinLimit()
    } ?: error("Unable to open outbound file")
    require(content.isNotBlank()) { "Outbound file is empty" }
    return content
}

private fun OutboundState.matchesQuery(query: String): Boolean {
    if (query.isBlank()) return true
    return remarks.contains(query, ignoreCase = true) ||
        type.contains(query, ignoreCase = true) ||
        endpointSummary()?.contains(query, ignoreCase = true) == true
}
