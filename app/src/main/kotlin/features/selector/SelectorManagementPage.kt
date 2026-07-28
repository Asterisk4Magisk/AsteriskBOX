// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

@file:OptIn(
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)

package features.selector

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.AppState
import app.DefaultSingBoxUrlTestIdleTimeout
import app.DefaultSingBoxUrlTestInterval
import app.DefaultSingBoxUrlTestTolerance
import app.DefaultSingBoxUrlTestUrl
import app.LocalAppServices
import app.LocalAppStateStore
import app.LocalIsWideScreen
import app.LocalNavigator
import app.LocalUpdateAppState
import app.ManagedOutboundChoice
import app.ManagedOutboundChoiceKind
import app.OutboundGroupState
import app.SingBoxSelectorState
import app.SingBoxSelectorTypeSelector
import app.SingBoxSelectorTypeUrlTest
import app.SupportedSingBoxSelectorTypes
import app.collectAppState
import app.nextAvailableSelectorId
import app.selectableManagedOutbounds
import app.withRemovedManagedOutboundTags
import engine.singbox.SingBoxUnsigned16Max
import engine.singbox.isSingBoxDurationNotGreaterThan
import engine.singbox.config.APP_DIRECT_OUTBOUND
import engine.singbox.config.validateSingBoxRuntimeConfiguration
import features.logs.FailureLogContext
import features.logs.reportFailure
import features.settings.SettingsDropdownRow
import features.settings.SettingsSwitchRow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.asterisk.zcc.abox.R
import ui.components.AsteriskActionButton
import ui.components.AsteriskInfoChip
import ui.components.AsteriskModalBottomSheet
import ui.components.WarningConfirmDialog
import ui.components.singBoxOptionLabel
import ui.icons.AsteriskIcons as Icons
import ui.layout.pageContentPaddingWithCutout
import ui.layout.pageListPadding
import ui.theme.AsteriskMotion
import ui.theme.AsteriskShapeTokens

@Composable
internal fun SelectorManagementPage(padding: PaddingValues) {
    val appState by LocalAppStateStore.current.collectAppState()
    val updateAppState = LocalUpdateAppState.current
    val navigator = LocalNavigator.current
    val services = LocalAppServices.current
    val context = LocalContext.current
    val isWideScreen = LocalIsWideScreen.current
    val scope = rememberCoroutineScope()
    var query by rememberSaveable { mutableStateOf("") }
    var editingSelector by remember { mutableStateOf<SingBoxSelectorState?>(null) }
    var editorSession by remember { mutableIntStateOf(0) }
    var showEditor by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<SingBoxSelectorState?>(null) }
    val normalizedQuery = query.trim()
    val directLabel = stringResource(R.string.routing_direct)
    val selectorTypeLabel = stringResource(R.string.selector_type_selector)
    val urlTestTypeLabel = stringResource(R.string.selector_type_urltest)
    val managedGroups = remember(appState.outboundGroups, appState.outbounds, normalizedQuery) {
        appState.outboundGroups.filter { group ->
            normalizedQuery.isEmpty() ||
                group.name.contains(normalizedQuery, ignoreCase = true) ||
                appState.outbounds.any { outbound ->
                    outbound.groupId == group.id &&
                        outbound.remarks.contains(normalizedQuery, ignoreCase = true)
                }
        }
    }
    val customSelectors = remember(
        appState.selectors,
        normalizedQuery,
        directLabel,
        selectorTypeLabel,
        urlTestTypeLabel,
    ) {
        appState.selectors.filter { selector ->
            val typeLabel = if (selector.type == SingBoxSelectorTypeUrlTest) {
                urlTestTypeLabel
            } else {
                selectorTypeLabel
            }
            normalizedQuery.isEmpty() ||
                selector.remarks.contains(normalizedQuery, ignoreCase = true) ||
                typeLabel.contains(normalizedQuery, ignoreCase = true) ||
                (
                    selector.type == SingBoxSelectorTypeUrlTest &&
                        selector.url.contains(normalizedQuery, ignoreCase = true)
                    ) ||
                selector.outbounds.any { outbound ->
                    outbound.contains(normalizedQuery, ignoreCase = true) ||
                        (
                            outbound == APP_DIRECT_OUTBOUND &&
                                directLabel.contains(normalizedQuery, ignoreCase = true)
                            )
                }
        }
    }
    val outboundChoices = remember(
        appState.outboundGroups,
        appState.outbounds,
        appState.endpoints,
        appState.selectors,
    ) {
        selectableManagedOutbounds(appState)
    }
    val targetTags = outboundChoices.map(ManagedOutboundChoice::tag)
    val count = managedGroups.size + customSelectors.size
    val savedMessage = stringResource(R.string.selector_saved)
    val saveFailedMessage = stringResource(R.string.selector_save_failed)
    val newSelectorRemarks = stringResource(R.string.selector_default_remarks)
    val countMotion = AsteriskMotion.fastEffects<Float>()

    fun openEditor(selector: SingBoxSelectorState?) {
        editingSelector = selector ?: SingBoxSelectorState(
            id = 0,
            remarks = newSelectorRemarks,
            outbounds = emptyList(),
        )
        editorSession += 1
        showEditor = true
    }

    fun saveSelector(draft: SingBoxSelectorState) {
        if (saving) return
        saving = true
        scope.launch {
            try {
                val normalized = validateSelectorDraft(appState, draft)
                val original = appState.selectors.firstOrNull { selector ->
                    selector.id == normalized.id
                }
                val saved = if (original == null) {
                    val id = appState.nextAvailableSelectorId()
                    normalized.copy(id = id)
                } else {
                    normalized
                }
                val candidateState = appState.copy(
                    selectors = if (original == null) {
                        appState.selectors + saved
                    } else {
                        appState.selectors.map { selector ->
                            if (selector.id == saved.id) saved else selector
                        }
                    },
                    nextSelectorId = if (original == null) {
                        saved.id + 1
                    } else {
                        appState.nextSelectorId
                    },
                )
                withContext(Dispatchers.IO) {
                    validateSingBoxRuntimeConfiguration(context, candidateState)
                }
                var committed = false
                updateAppState { state ->
                    if (state !== appState) {
                        state
                    } else {
                        committed = true
                        candidateState
                    }
                }
                if (committed) {
                    showEditor = false
                    services.tipNotifier.show(savedMessage)
                } else {
                    reportFailure(
                        FailureLogContext(
                            operation = "selector_save",
                            stage = "commit",
                        ),
                    )
                    services.tipNotifier.show(saveFailedMessage)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                services.tipNotifier.showError(
                    error,
                    saveFailedMessage,
                    FailureLogContext(operation = "selector_save"),
                )
            } finally {
                saving = false
            }
        }
    }

    fun deleteSelector(selector: SingBoxSelectorState) {
        if (saving) return
        saving = true
        scope.launch {
            try {
                val candidateState = appState.copy(
                    selectors = appState.selectors.filterNot { item -> item.id == selector.id },
                ).withRemovedManagedOutboundTags(setOf(selector.tag))
                withContext(Dispatchers.IO) {
                    validateSingBoxRuntimeConfiguration(context, candidateState)
                }
                var committed = false
                updateAppState { state ->
                    if (state !== appState) {
                        state
                    } else {
                        committed = true
                        candidateState
                    }
                }
                if (!committed) {
                    reportFailure(
                        FailureLogContext(
                            operation = "selector_delete",
                            stage = "commit",
                        ),
                    )
                    services.tipNotifier.show(saveFailedMessage)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                services.tipNotifier.showError(
                    error,
                    saveFailedMessage,
                    FailureLogContext(operation = "selector_delete"),
                )
            } finally {
                saving = false
            }
        }
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Column {
                            Text(stringResource(R.string.selector_management))
                            AnimatedContent(
                                targetState = count,
                                transitionSpec = {
                                    fadeIn(countMotion).togetherWith(fadeOut(countMotion))
                                },
                                label = "selector-count",
                            ) { visibleCount ->
                                Text(
                                    pluralStringResource(
                                        R.plurals.selector_count,
                                        visibleCount,
                                        visibleCount,
                                    ),
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
                        IconButton(
                            onClick = { openEditor(null) },
                            enabled = !saving && targetTags.isNotEmpty(),
                        ) {
                            Icon(Icons.Rounded.Add, stringResource(R.string.selector_add))
                        }
                    },
                )
                ui.components.AsteriskPinnedSearchArea(
                    query = query,
                    onQueryChange = { query = it },
                    placeholder = stringResource(R.string.selector_search),
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
            contentPadding = pageListPadding(contentPadding, bottomExtra = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (managedGroups.isNotEmpty()) {
                item(
                    key = "managed-title",
                    span = { GridItemSpan(maxLineSpan) },
                ) {
                    SelectorSectionTitle(stringResource(R.string.selector_managed_section))
                }
                managedGroups.forEach { group ->
                    item(key = "managed:${group.id}") {
                        ManagedSelectorCard(
                            group = group,
                            memberCount = selectorCardMemberCount(
                                appState.outbounds
                                    .filter { outbound -> outbound.groupId == group.id }
                                    .map { outbound -> outbound.tag },
                            ),
                            modifier = Modifier.animateItem(),
                        )
                    }
                }
            }
            if (customSelectors.isNotEmpty()) {
                item(
                    key = "custom-title",
                    span = { GridItemSpan(maxLineSpan) },
                ) {
                    SelectorSectionTitle(stringResource(R.string.selector_custom_section))
                }
                customSelectors.forEach { selector ->
                    item(key = "custom:${selector.id}") {
                        CustomSelectorCard(
                            selector = selector,
                            onEdit = { openEditor(selector) },
                            onDelete = { pendingDelete = selector },
                            modifier = Modifier.animateItem(),
                        )
                    }
                }
            }
            if (managedGroups.isEmpty() && customSelectors.isEmpty()) {
                item(
                    key = "empty",
                    span = { GridItemSpan(maxLineSpan) },
                ) {
                    SelectorEmptyState(hasQuery = normalizedQuery.isNotEmpty())
                }
            } else if (customSelectors.isEmpty() && normalizedQuery.isEmpty()) {
                item(
                    key = "custom-empty",
                    span = { GridItemSpan(maxLineSpan) },
                ) {
                    SelectorCustomEmptyState()
                }
            }
        }
    }

    SelectorEditorSheet(
        show = showEditor,
        selector = editingSelector,
        editorSession = editorSession,
        state = appState,
        saving = saving,
        onDismissRequest = { if (!saving) showEditor = false },
        onSave = ::saveSelector,
    )
    WarningConfirmDialog(
        show = pendingDelete != null,
        title = stringResource(R.string.selector_delete_title),
        summary = stringResource(
            R.string.selector_delete_message,
            pendingDelete?.remarks.orEmpty(),
        ),
        dismissText = stringResource(R.string.common_cancel),
        confirmText = stringResource(R.string.common_delete),
        onDismissRequest = { pendingDelete = null },
        onConfirm = {
            pendingDelete?.let(::deleteSelector)
            pendingDelete = null
        },
    )
}

@Composable
private fun SelectorSectionTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp, top = 8.dp),
    )
}

@Composable
private fun ManagedSelectorCard(
    group: OutboundGroupState,
    memberCount: Int,
    modifier: Modifier = Modifier,
) {
    SelectorCard(
        modifier = modifier,
        title = group.name,
        badges = listOf(
            stringResource(R.string.selector_managed_badge),
            stringResource(R.string.selector_type_selector),
        ),
        memberCount = memberCount,
        status = when {
            !group.enabled -> stringResource(R.string.selector_group_disabled)
            memberCount == 0 -> stringResource(R.string.selector_no_members)
            else -> stringResource(R.string.selector_managed_summary)
        },
        enabled = group.enabled && memberCount > 0,
        menu = null,
    )
}

@Composable
private fun CustomSelectorCard(
    selector: SingBoxSelectorState,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val isUrlTest = selector.type == SingBoxSelectorTypeUrlTest
    SelectorCard(
        modifier = modifier,
        title = selector.remarks,
        badges = listOf(
            stringResource(R.string.selector_custom_badge),
            stringResource(
                if (isUrlTest) R.string.selector_type_urltest
                else R.string.selector_type_selector,
            ),
        ),
        memberCount = selectorCardMemberCount(selector.outbounds),
        status = null,
        enabled = selector.outbounds.isNotEmpty(),
        onClick = onEdit,
        menu = {
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
                        text = { Text(stringResource(R.string.common_delete)) },
                        leadingIcon = { Icon(Icons.Rounded.Delete, contentDescription = null) },
                        onClick = {
                            menuExpanded = false
                            onDelete()
                        },
                    )
                }
            }
        },
    )
}

@Composable
private fun SelectorCard(
    title: String,
    badges: List<String>,
    memberCount: Int,
    status: String?,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    menu: (@Composable () -> Unit)?,
) {
    val content: @Composable () -> Unit = {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .animateContentSize(AsteriskMotion.contentSpatial()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    status?.let { description ->
                        Text(
                            description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                menu?.invoke()
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                badges.forEach { badge ->
                    AsteriskInfoChip(text = badge, emphasized = enabled)
                }
                AsteriskInfoChip(
                    text = pluralStringResource(
                        R.plurals.selector_member_count,
                        memberCount,
                        memberCount,
                    ),
                )
            }
        }
    }
    if (onClick == null) {
        Card(
            modifier = modifier,
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
            ),
            content = { content() },
        )
    } else {
        Card(
            onClick = onClick,
            modifier = modifier,
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
            ),
            content = { content() },
        )
    }
}

@Composable
private fun SelectorCustomEmptyState() {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 28.dp, horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            Icons.Rounded.Add,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            stringResource(R.string.selector_empty),
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            stringResource(R.string.selector_empty_summary),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SelectorEmptyState(hasQuery: Boolean) {
    AnimatedVisibility(
        visible = true,
        enter = fadeIn(AsteriskMotion.effects()) + scaleIn(AsteriskMotion.spatial()),
        exit = fadeOut(AsteriskMotion.effects()) + scaleOut(AsteriskMotion.spatial()),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = 72.dp, horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                if (hasQuery) Icons.Rounded.SearchOff else Icons.Rounded.Tune,
                contentDescription = null,
                modifier = Modifier.size(42.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(
                    if (hasQuery) R.string.selector_search_empty else R.string.selector_empty,
                ),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                stringResource(
                    if (hasQuery) {
                        R.string.selector_search_empty_summary
                    } else {
                        R.string.selector_empty_summary
                    },
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private data class SelectorTargetUi(
    val tag: String,
    val label: String,
    val groupName: String? = null,
    val kind: SelectorTargetKind,
)

private enum class SelectorTargetKind {
    Selector,
    UrlTest,
    Outbound,
    Endpoint,
    Direct,
    GlobalSelector,
    Unavailable,
}

@Composable
private fun SelectorEditorSheet(
    show: Boolean,
    selector: SingBoxSelectorState?,
    editorSession: Int,
    state: AppState,
    saving: Boolean,
    onDismissRequest: () -> Unit,
    onSave: (SingBoxSelectorState) -> Unit,
) {
    var type by remember(editorSession) {
        mutableStateOf(selector?.type ?: SingBoxSelectorTypeSelector)
    }
    var remarks by remember(editorSession) { mutableStateOf(selector?.remarks.orEmpty()) }
    var members by remember(editorSession) {
        mutableStateOf(selector?.outbounds.orEmpty())
    }
    var default by remember(editorSession) { mutableStateOf(selector?.default.orEmpty()) }
    var url by remember(editorSession) {
        mutableStateOf(selector?.url ?: DefaultSingBoxUrlTestUrl)
    }
    var interval by remember(editorSession) {
        mutableStateOf(selector?.interval ?: DefaultSingBoxUrlTestInterval)
    }
    var tolerance by remember(editorSession) {
        mutableStateOf(
            (selector?.tolerance ?: DefaultSingBoxUrlTestTolerance).toString(),
        )
    }
    var idleTimeout by remember(editorSession) {
        mutableStateOf(selector?.idleTimeout ?: DefaultSingBoxUrlTestIdleTimeout)
    }
    var interrupt by remember(editorSession) {
        mutableStateOf(selector?.interruptExistConnections ?: true)
    }
    var query by remember(editorSession) { mutableStateOf("") }
    val normalizedRemarks = remarks.trim()
    val targets = remember(
        state.outboundGroups,
        state.outbounds,
        state.endpoints,
        state.selectors,
        selector?.id,
        selector?.outbounds,
    ) {
        val available = buildSelectorTargets(
            state = state,
            selectorId = selector?.id ?: 0,
        )
        available + selector?.outbounds
            .orEmpty()
            .filterNot { member -> available.any { target -> target.tag == member } }
            .map { member ->
                SelectorTargetUi(
                    tag = member,
                    label = "",
                    kind = SelectorTargetKind.Unavailable,
                )
            }
    }
    val visibleTargets = remember(targets, query) {
        val normalized = query.trim()
        if (normalized.isEmpty()) {
            targets
        } else {
            targets.filter { target ->
                target.tag.contains(normalized, ignoreCase = true) ||
                    target.label.contains(normalized, ignoreCase = true) ||
                    target.groupName?.contains(normalized, ignoreCase = true) == true
            }
        }
    }
    val urlInvalid = url.isNotBlank() && !isValidUrlTestUrl(url)
    val intervalInvalid = interval.isNotBlank() && !isValidSingBoxDuration(interval)
    val toleranceValue = tolerance.toIntOrNull()
    val toleranceInvalid = tolerance.isNotBlank() &&
        (toleranceValue == null || toleranceValue !in 0..SingBoxUnsigned16Max)
    val idleTimeoutInvalid = idleTimeout.isNotBlank() &&
        !isValidSingBoxDuration(idleTimeout)
    val intervalExceedsIdleTimeout = !intervalInvalid &&
        !idleTimeoutInvalid &&
        isValidSingBoxDuration(interval.ifBlank { DefaultSingBoxUrlTestInterval }) &&
        isValidSingBoxDuration(idleTimeout.ifBlank { DefaultSingBoxUrlTestIdleTimeout }) &&
        !isSingBoxDurationNotGreaterThan(
            interval.ifBlank { DefaultSingBoxUrlTestInterval },
            idleTimeout.ifBlank { DefaultSingBoxUrlTestIdleTimeout },
        )
    val draft = SingBoxSelectorState(
        id = selector?.id ?: 0,
        remarks = normalizedRemarks,
        outbounds = members,
        default = default,
        type = type,
        url = url,
        interval = interval,
        tolerance = toleranceValue ?: if (tolerance.isBlank()) {
            DefaultSingBoxUrlTestTolerance
        } else {
            -1
        },
        idleTimeout = idleTimeout,
        interruptExistConnections = interrupt,
    )
    val canSave = !saving && runCatching {
        validateSelectorDraft(state, draft)
    }.isSuccess
    val typeLabels = listOf(
        singBoxOptionLabel(
            stringResource(R.string.selector_type_selector),
            SingBoxSelectorTypeSelector,
        ),
        singBoxOptionLabel(
            stringResource(R.string.selector_type_urltest),
            SingBoxSelectorTypeUrlTest,
        ),
    )
    val typeEffectsMotion = AsteriskMotion.fastEffects<Float>()

    fun toggleMember(target: String) {
        members = if (target in members) {
            members - target
        } else {
            members + target
        }
        if (default !in members) default = members.firstOrNull().orEmpty()
    }

    AsteriskModalBottomSheet(
        show = show,
        title = stringResource(
            if (selector?.id == 0) R.string.selector_editor_add else R.string.selector_editor_edit,
        ),
        onDismissRequest = onDismissRequest,
        startAction = {
            AsteriskActionButton(
                text = stringResource(R.string.common_cancel),
                icon = Icons.Rounded.Close,
                enabled = !saving,
                onClick = onDismissRequest,
            )
        },
        endAction = {
            AsteriskActionButton(
                text = stringResource(R.string.common_save),
                icon = Icons.Rounded.Save,
                enabled = canSave,
                onClick = { onSave(draft) },
            )
        },
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 720.dp),
            contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "remarks") {
                OutlinedTextField(
                    value = remarks,
                    onValueChange = { remarks = it },
                    label = { Text(stringResource(R.string.selector_editor_remarks)) },
                    isError = normalizedRemarks.isEmpty(),
                    supportingText = if (normalizedRemarks.isEmpty()) {
                        { Text(stringResource(R.string.selector_editor_remarks_required)) }
                    } else {
                        null
                    },
                    singleLine = true,
                    shape = AsteriskShapeTokens.InnerContainer,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item(key = "type") {
                SettingsDropdownRow(
                    title = stringResource(R.string.selector_editor_type),
                    summary = stringResource(R.string.selector_editor_type_summary),
                    icon = Icons.Rounded.Tune,
                    items = typeLabels,
                    selectedIndex = SupportedSingBoxSelectorTypes
                        .indexOf(type)
                        .coerceAtLeast(0),
                    onSelectedIndexChange = { index ->
                        val nextType = SupportedSingBoxSelectorTypes[index]
                        type = nextType
                    },
                )
            }
            item(key = "interrupt") {
                SettingsSwitchRow(
                    title = stringResource(R.string.selector_editor_interrupt),
                    summary = stringResource(R.string.selector_editor_interrupt_summary),
                    icon = Icons.Rounded.Sync,
                    checked = interrupt,
                    onCheckedChange = { interrupt = it },
                )
            }
            item(key = "members-title") {
                Text(
                    stringResource(R.string.selector_editor_members),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            item(key = "target-search") {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text(stringResource(R.string.selector_editor_search_targets)) },
                    leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                    singleLine = true,
                    shape = AsteriskShapeTokens.InnerContainer,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item(key = "members-required") {
                AnimatedVisibility(
                    visible = members.isEmpty(),
                    enter = AsteriskMotion.contentEnter(),
                    exit = AsteriskMotion.contentExit(),
                ) {
                    Text(
                        stringResource(R.string.selector_editor_members_required),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            items(visibleTargets, key = SelectorTargetUi::tag) { target ->
                SelectorTargetRow(
                    target = target,
                    selected = target.tag in members,
                    onClick = { toggleMember(target.tag) },
                )
            }
            item(key = "type-fields") {
                AnimatedContent(
                    targetState = type,
                    transitionSpec = {
                        fadeIn(typeEffectsMotion).togetherWith(fadeOut(typeEffectsMotion))
                    },
                    label = "selector-type-fields",
                ) { selectedType ->
                    if (selectedType == SingBoxSelectorTypeUrlTest) {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(
                                stringResource(R.string.selector_editor_urltest),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            OutlinedTextField(
                                value = url,
                                onValueChange = { url = it },
                                label = { Text(stringResource(R.string.selector_editor_urltest_url)) },
                                isError = urlInvalid,
                                supportingText = if (urlInvalid) {
                                    {
                                        Text(
                                            stringResource(
                                                R.string.selector_editor_urltest_url_invalid,
                                            ),
                                        )
                                    }
                                } else {
                                    null
                                },
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Uri,
                                ),
                                singleLine = true,
                                shape = AsteriskShapeTokens.InnerContainer,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            OutlinedTextField(
                                value = interval,
                                onValueChange = { interval = it },
                                label = {
                                    Text(stringResource(R.string.selector_editor_urltest_interval))
                                },
                                isError = intervalInvalid,
                                supportingText = if (intervalInvalid) {
                                    {
                                        Text(
                                            stringResource(
                                                R.string.selector_editor_urltest_duration_invalid,
                                            ),
                                        )
                                    }
                                } else {
                                    null
                                },
                                singleLine = true,
                                shape = AsteriskShapeTokens.InnerContainer,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            OutlinedTextField(
                                value = tolerance,
                                onValueChange = { value ->
                                    tolerance = value.filter(Char::isDigit)
                                },
                                label = {
                                    Text(stringResource(R.string.selector_editor_urltest_tolerance))
                                },
                                isError = toleranceInvalid,
                                supportingText = if (toleranceInvalid) {
                                    {
                                        Text(
                                            stringResource(
                                                R.string.selector_editor_urltest_tolerance_invalid,
                                            ),
                                        )
                                    }
                                } else {
                                    null
                                },
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Number,
                                ),
                                singleLine = true,
                                shape = AsteriskShapeTokens.InnerContainer,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            OutlinedTextField(
                                value = idleTimeout,
                                onValueChange = { idleTimeout = it },
                                label = {
                                    Text(
                                        stringResource(
                                            R.string.selector_editor_urltest_idle_timeout,
                                        ),
                                    )
                                },
                                isError = idleTimeoutInvalid || intervalExceedsIdleTimeout,
                                supportingText = if (idleTimeoutInvalid) {
                                    {
                                        Text(
                                            stringResource(
                                                R.string.selector_editor_urltest_duration_invalid,
                                            ),
                                        )
                                    }
                                } else if (intervalExceedsIdleTimeout) {
                                    {
                                        Text(
                                            stringResource(
                                                R.string.selector_editor_urltest_interval_exceeds_idle,
                                            ),
                                        )
                                    }
                                } else {
                                    null
                                },
                                singleLine = true,
                                shape = AsteriskShapeTokens.InnerContainer,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    } else {
                        AnimatedVisibility(
                            visible = members.isNotEmpty(),
                            enter = AsteriskMotion.contentEnter(),
                            exit = AsteriskMotion.contentExit(),
                        ) {
                            SettingsDropdownRow(
                                title = stringResource(R.string.selector_editor_default),
                                icon = Icons.AutoMirrored.Rounded.AltRoute,
                                items = members.map { member ->
                                    targets
                                        .firstOrNull { target -> target.tag == member }
                                        ?.displayLabel()
                                        ?: stringResource(R.string.selector_target_unavailable)
                                },
                                selectedIndex = selectorDefaultMemberIndex(members, default),
                                onSelectedIndexChange = { index -> default = members[index] },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SelectorTargetRow(
    target: SelectorTargetUi,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainer
            },
        ),
        shape = AsteriskShapeTokens.InnerContainer,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                when (target.kind) {
                    SelectorTargetKind.Selector -> Icons.Rounded.Tune
                    SelectorTargetKind.UrlTest -> Icons.Rounded.Speed
                    SelectorTargetKind.Outbound -> Icons.Rounded.Router
                    SelectorTargetKind.Endpoint -> Icons.Rounded.VpnLock
                    SelectorTargetKind.Direct -> Icons.Rounded.Public
                    SelectorTargetKind.GlobalSelector -> Icons.Rounded.Tune
                    SelectorTargetKind.Unavailable -> Icons.Rounded.ErrorOutline
                },
                contentDescription = null,
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    target.displayLabel(),
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    stringResource(
                        when (target.kind) {
                            SelectorTargetKind.Selector -> R.string.selector_type_selector
                            SelectorTargetKind.UrlTest -> R.string.selector_type_urltest
                            SelectorTargetKind.Outbound -> R.string.selector_target_outbound
                            SelectorTargetKind.Endpoint -> R.string.selector_target_endpoint
                            SelectorTargetKind.Direct -> R.string.selector_target_direct
                            SelectorTargetKind.GlobalSelector -> R.string.selector_type_selector
                            SelectorTargetKind.Unavailable -> R.string.selector_target_unavailable
                        },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Checkbox(checked = selected, onCheckedChange = null)
        }
    }
}

private fun buildSelectorTargets(
    state: AppState,
    selectorId: Int,
): List<SelectorTargetUi> = selectorTargetChoices(
    state = state,
    selectorId = selectorId,
).map { choice ->
    SelectorTargetUi(
        tag = choice.tag,
        label = choice.label,
        groupName = choice.groupName,
        kind = when (choice.kind) {
            ManagedOutboundChoiceKind.Selector -> SelectorTargetKind.Selector
            ManagedOutboundChoiceKind.UrlTest -> SelectorTargetKind.UrlTest
            ManagedOutboundChoiceKind.Outbound -> SelectorTargetKind.Outbound
            ManagedOutboundChoiceKind.Endpoint -> SelectorTargetKind.Endpoint
            ManagedOutboundChoiceKind.Direct -> SelectorTargetKind.Direct
            ManagedOutboundChoiceKind.GlobalSelector -> SelectorTargetKind.GlobalSelector
        },
    )
}

@Composable
private fun SelectorTargetUi.displayLabel(): String = when (kind) {
    SelectorTargetKind.Direct -> stringResource(R.string.routing_direct)
    SelectorTargetKind.GlobalSelector -> stringResource(R.string.routing_global)
    SelectorTargetKind.Unavailable -> stringResource(R.string.selector_target_unavailable)
    else -> groupName?.let { group ->
        stringResource(R.string.outbound_choice_with_group, label, group)
    } ?: label
}
