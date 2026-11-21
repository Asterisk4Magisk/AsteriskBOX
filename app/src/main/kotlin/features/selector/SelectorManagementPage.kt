// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.selector

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.state.ToggleableState
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
import app.R
import app.SingBoxSelectorState
import app.SingBoxSelectorTypeSelector
import app.SingBoxSelectorTypeUrlTest
import app.SupportedSingBoxSelectorTypes
import app.collectAppState
import app.selectableManagedOutbounds
import app.selectorRegexMemberTags
import app.withRemovedManagedOutboundTags
import engine.singbox.SingBoxUnsigned16Max
import engine.singbox.config.APP_DIRECT_OUTBOUND
import engine.singbox.config.validateSingBoxRuntimeConfiguration
import engine.singbox.isSingBoxDurationNotGreaterThan
import features.logs.FailureLogContext
import features.logs.reportFailure
import features.settings.SettingsActionRow
import features.settings.SettingsDropdownRow
import features.settings.SettingsSwitchRow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import sh.calvin.reorderable.ReorderableItem
import ui.components.AsteriskActionButton
import ui.components.AsteriskCancelButton
import ui.components.AsteriskCheckbox
import ui.components.AsteriskInfoChip
import ui.components.AsteriskScaffold
import ui.components.AsteriskSearchTopAppBar
import ui.components.EditDeleteDropdownMenu
import ui.components.EditorPageScaffold
import ui.components.WarningConfirmDialog
import ui.components.animateDraggedCardLift
import ui.components.draggedCardLift
import ui.components.longPressReorderDragHandle
import ui.components.rememberAsteriskReorderableLazyGridState
import ui.components.rememberReorderPreview
import ui.components.reorderByIds
import ui.components.singBoxOptionLabel
import ui.components.verticalReorderScrollThresholdPadding
import ui.layout.LocalAdaptiveWindow
import ui.layout.pageContentPaddingWithCutout
import ui.layout.pageListPadding
import ui.theme.AsteriskMotion
import ui.layout.pageReadableWidth
import ui.theme.AsteriskShapeTokens
import androidx.compose.foundation.lazy.grid.items as gridItems
import ui.icons.AsteriskIcons as Icons

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
    val saveFailedMessage = stringResource(R.string.selector_save_failed)
    val countMotion = AsteriskMotion.fastEffects<Float>()

    fun openEditor(selector: SingBoxSelectorState?) {
        navigator.push(app.navigation.Route.SelectorEdit(selector?.id ?: 0))
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

    AsteriskScaffold(
        topBar = {
            Column {
                AsteriskSearchTopAppBar(
                    query = query,
                    onQueryChange = { query = it },
                    placeholder = stringResource(R.string.selector_search),
                    title = {
                        Column {
                            Text(stringResource(R.string.selector_management))
                            AnimatedContent(
                                targetState = count,
                                transitionSpec = AsteriskMotion.fadeThrough(countMotion),
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
            }
        },
    ) { innerPadding ->
        val contentPadding = pageContentPaddingWithCutout(
            innerPadding = innerPadding,
            outerPadding = padding,
            isWideScreen = isWideScreen,
        )
        val listContentPadding = pageListPadding(
            contentPadding,
            bottomExtra = 24.dp,
            // Non-empty lists start with a section title that supplies its own spacing.
            topExtra = if (managedGroups.isNotEmpty() || customSelectors.isNotEmpty()) 0.dp else 8.dp,
        )
        val gridState = rememberLazyGridState()
        val reorderEnabled = isSelectorReorderEnabled(query, appState.selectors.size)
        val preview = rememberReorderPreview(customSelectors, SingBoxSelectorState::id, enabled = reorderEnabled) { ids ->
            updateAppState { state ->
                state.copy(selectors = state.selectors.reorderByIds(ids, SingBoxSelectorState::id))
            }
            true
        }
        val reorderableState = rememberAsteriskReorderableLazyGridState(
            lazyGridState = gridState,
            itemCount = customSelectors.size,
            indexOffset = selectorCustomSectionIndexOffset(managedGroups.size),
            scrollThresholdPadding = verticalReorderScrollThresholdPadding(listContentPadding),
            onMove = preview.onMove,
        )
        LazyVerticalGrid(
            columns = GridCells.Adaptive(300.dp),
            state = gridState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = listContentPadding,
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
                gridItems(
                    items = preview.items,
                    key = { selector -> "custom:${selector.id}" },
                    contentType = { "custom-selector" },
                ) { selector ->
                    val reorderKey = "custom:${selector.id}"
                    ReorderableItem(
                        state = reorderableState.reorderableState,
                        key = reorderKey,
                        enabled = reorderEnabled,
                        modifier = Modifier.fillMaxWidth(),
                        animateItemModifier = Modifier.animateItem(),
                    ) { isDragging ->
                        CustomSelectorCard(
                            state = appState,
                            selector = selector,
                            isDragging = isDragging && reorderEnabled,
                            onEdit = { openEditor(selector) },
                            onDelete = { pendingDelete = selector },
                            modifier = Modifier
                                .fillMaxWidth()
                                .longPressReorderDragHandle(
                                    scope = this,
                                    enabled = reorderEnabled,
                                    state = reorderableState,
                                    onDragStarted = preview.onDragStarted,
                                    onDragStopped = preview.onDragStopped,
                                ),
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
    state: AppState,
    selector: SingBoxSelectorState,
    isDragging: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val isUrlTest = selector.type == SingBoxSelectorTypeUrlTest
    val memberCount = selectorCardMemberCount(state, selector)
    SelectorCard(
        modifier = modifier,
        title = selector.remarks,
        badges = listOf(
            stringResource(
                if (isUrlTest) R.string.selector_type_urltest
                else R.string.selector_type_selector,
            ),
        ),
        memberCount = memberCount,
        status = if (memberCount == 0) {
            stringResource(R.string.selector_no_members)
        } else {
            null
        },
        enabled = memberCount > 0,
        isDragging = isDragging,
        onClick = onEdit,
        menu = {
            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(Icons.Rounded.MoreVert, stringResource(R.string.common_more))
                }
                EditDeleteDropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                    onEdit = onEdit,
                    onDelete = onDelete,
                    showDividerBeforeDelete = false,
                )
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
    isDragging: Boolean = false,
    onClick: (() -> Unit)? = null,
    menu: (@Composable () -> Unit)?,
) {
    val containerColor by animateColorAsState(
        targetValue = if (isDragging) {
            MaterialTheme.colorScheme.surfaceContainerHighest
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        animationSpec = AsteriskMotion.effects(),
        label = "selector-card-color",
    )
    val lift = animateDraggedCardLift(isDragging, label = "selector-card")
    val cardModifier = modifier
        .draggedCardLift(lift, cornerRadius = AsteriskShapeTokens.InnerContainerRadius)
    val cardColors = CardDefaults.cardColors(containerColor = containerColor)
    val content: @Composable () -> Unit = {
        Box(Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = 16.dp,
                        top = 16.dp,
                        end = if (menu != null) 56.dp else 16.dp,
                        bottom = 16.dp,
                    )
                    .animateContentSize(AsteriskMotion.contentSize()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column {
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
            // Preserve the touch target without letting the menu push the title down.
            if (menu != null) {
                Box(Modifier.align(Alignment.CenterEnd).padding(4.dp)) {
                    menu()
                }
            }
        }
    }
    if (onClick == null) {
        Card(
            modifier = cardModifier,
            colors = cardColors,
            content = { content() },
        )
    } else {
        Card(
            onClick = onClick,
            modifier = cardModifier,
            colors = cardColors,
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
            Icons.Rounded.Tune,
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
        enter = AsteriskMotion.scaleFadeEnter(
            effectsSpec = AsteriskMotion.effects(),
            spatialSpec = AsteriskMotion.spatial(),
        ),
        exit = AsteriskMotion.scaleFadeExit(
            effectsSpec = AsteriskMotion.effects(),
            spatialSpec = AsteriskMotion.spatial(),
        ),
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
    Group,
    Selector,
    UrlTest,
    Outbound,
    Endpoint,
    Direct,
    GlobalSelector,
    Unavailable,
}

@Composable
internal fun SelectorEditorScaffold(
    outerPadding: PaddingValues,
    isWideScreen: Boolean,
    selector: SingBoxSelectorState?,
    state: AppState,
    saving: Boolean,
    onDismissRequest: () -> Unit,
    onSave: (SingBoxSelectorState) -> Unit,
) {
    val focusManager = LocalFocusManager.current
    var type by remember(selector?.id) {
        mutableStateOf(selector?.type ?: SingBoxSelectorTypeSelector)
    }
    var remarks by remember(selector?.id) { mutableStateOf(selector?.remarks.orEmpty()) }
    var memberReferences by remember(selector?.id) {
        mutableStateOf(selector?.outbounds.orEmpty())
    }
    var default by remember(selector?.id) { mutableStateOf(selector?.default.orEmpty()) }
    var url by remember(selector?.id) {
        mutableStateOf(selector?.url ?: DefaultSingBoxUrlTestUrl)
    }
    var interval by remember(selector?.id) {
        mutableStateOf(selector?.interval ?: DefaultSingBoxUrlTestInterval)
    }
    var tolerance by remember(selector?.id) {
        mutableStateOf(
            (selector?.tolerance ?: DefaultSingBoxUrlTestTolerance).toString(),
        )
    }
    var idleTimeout by remember(selector?.id) {
        mutableStateOf(selector?.idleTimeout ?: DefaultSingBoxUrlTestIdleTimeout)
    }
    var fallbackEnabled by remember(selector?.id) {
        mutableStateOf(selector?.fallbackEnabled ?: false)
    }
    var fallbackMaxDelay by remember(selector?.id) {
        mutableStateOf(selector?.fallbackMaxDelay.orEmpty())
    }
    var interrupt by remember(selector?.id) {
        mutableStateOf(selector?.interruptExistConnections ?: true)
    }
    var query by remember(selector?.id) { mutableStateOf("") }
    var regexEnabled by remember(selector?.id) { mutableStateOf(false) }
    var nodesOnly by remember(selector?.id) { mutableStateOf(false) }
    var nodeNameMatchingEnabled by remember(selector?.id) {
        mutableStateOf(!selector?.nodeNameRegex.isNullOrBlank())
    }
    var nodeNameRegexInput by remember(selector?.id) {
        mutableStateOf(selector?.nodeNameRegex.orEmpty())
    }
    var matchOutboundGroupIds by remember(selector?.id) {
        mutableStateOf(selector?.matchOutboundGroupIds)
    }
    val nodeNameRegex = nodeNameRegexInput.trim()
    val nodeNameRegexInvalid = remember(nodeNameRegex) {
        nodeNameRegex.isEmpty() || selectorTargetRegexOrNull(nodeNameRegex) == null
    }
    val normalizedRemarks = remarks.trim()
    val targetSelectorId = if (nodeNameMatchingEnabled) 0 else selector?.id ?: 0
    val targetChoices = remember(
        state.outboundGroups,
        state.outbounds,
        state.endpoints,
        state.selectors,
        targetSelectorId,
    ) {
        selectorTargetChoices(
            state = state,
            selectorId = targetSelectorId,
        )
    }
    val targets = remember(targetChoices, selector?.outbounds) {
        val available = buildSelectorTargets(targetChoices)
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
    val targetsByTag = remember(targets) { targets.associateBy(SelectorTargetUi::tag) }
    val searchResult = remember(targets, query, regexEnabled, nodesOnly) {
        filterSelectorTargets(
            targets = targets.filter { !nodesOnly || it.kind == SelectorTargetKind.Outbound }.map { target ->
                SelectorTargetSearchCandidate(
                    tag = target.tag,
                    label = target.label,
                    groupName = target.groupName,
                )
            },
            query = query,
            regexEnabled = regexEnabled,
        )
    }
    val visibleTargetTags = searchResult.matchedTags
    val visibleTargets = remember(targets, visibleTargetTags) {
        val visibleTargetTagSet = visibleTargetTags.toSet()
        targets.filter { target -> target.tag in visibleTargetTagSet }
    }
    val regexMemberTags = remember(state, selector, nodeNameRegex, nodeNameRegexInvalid, matchOutboundGroupIds) {
        if (nodeNameRegexInvalid) {
            emptyList()
        } else {
            val current = selector ?: SingBoxSelectorState(id = 0, remarks = "")
            state.selectorRegexMemberTags(
                current.copy(
                    outbounds = emptyList(),
                    nodeNameRegex = nodeNameRegex,
                    matchOutboundGroupIds = matchOutboundGroupIds,
                ),
            )
        }
    }
    val matchedTargets = remember(targetsByTag, regexMemberTags) {
        regexMemberTags.mapNotNull(targetsByTag::get)
    }
    val effectiveMembers = if (nodeNameMatchingEnabled) {
        regexMemberTags
    } else {
        remember(state, memberReferences, targetChoices) {
            selectorEffectiveMemberTags(state, memberReferences, targetChoices)
        }
    }
    val lockedOutboundTags = remember(state, memberReferences) {
        state.selectorGroupLockedOutboundTags(memberReferences)
    }
    val interactiveVisibleTargetTags = remember(
        state,
        memberReferences,
        visibleTargetTags,
    ) {
        selectorInteractiveTargetTags(
            state = state,
            memberReferences = memberReferences,
            targetTags = visibleTargetTags,
        )
    }
    val searchInvalid = searchResult is SelectorTargetSearchResult.InvalidRegex
    val selectionState = selectorTargetSelectionState(
        memberReferences,
        interactiveVisibleTargetTags,
    )
    val editorSections = selectorEditorSections(type, fallbackEnabled)
    val connectionOptions = editorSections
        .filterIsInstance<SelectorEditorSection.ConnectionOptions>()
        .single()
    val memberHeader = editorSections
        .filterIsInstance<SelectorEditorSection.MemberHeader>()
        .single()
    val urlInvalid = connectionOptions.showUrlTestSettings &&
        url.isNotBlank() && !isValidUrlTestUrl(url)
    val intervalInvalid = connectionOptions.showUrlTestSettings &&
        interval.isNotBlank() && !isValidSingBoxDuration(interval)
    val toleranceValue = tolerance.trim().toIntOrNull()
    val toleranceInvalid = connectionOptions.showTolerance && tolerance.isNotBlank() &&
        (toleranceValue == null || toleranceValue !in 0..SingBoxUnsigned16Max)
    val idleTimeoutInvalid = connectionOptions.showUrlTestSettings && idleTimeout.isNotBlank() &&
        !isValidSingBoxDuration(idleTimeout)
    val fallbackMaxDelayInvalid = connectionOptions.showFallbackMaxDelay && fallbackMaxDelay.isNotBlank() &&
        !isValidSingBoxDuration(fallbackMaxDelay)
    val effectiveInterval = effectiveUrlTestDuration(interval, DefaultSingBoxUrlTestInterval)
    val effectiveIdleTimeout = effectiveUrlTestDuration(idleTimeout, DefaultSingBoxUrlTestIdleTimeout)
    val intervalExceedsIdleTimeout = connectionOptions.showUrlTestSettings &&
        !intervalInvalid && !idleTimeoutInvalid &&
        !isSingBoxDurationNotGreaterThan(
            effectiveInterval,
            effectiveIdleTimeout,
        )
    val draft = SingBoxSelectorState(
        id = selector?.id ?: 0,
        remarks = normalizedRemarks,
        outbounds = if (nodeNameMatchingEnabled) emptyList() else memberReferences,
        default = default.takeIf(effectiveMembers::contains)
            ?: effectiveMembers.firstOrNull().orEmpty(),
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
        fallbackEnabled = fallbackEnabled,
        fallbackMaxDelay = fallbackMaxDelay,
        nodeNameRegex = if (nodeNameMatchingEnabled) nodeNameRegex else "",
        matchOutboundGroupIds = matchOutboundGroupIds.takeIf { nodeNameMatchingEnabled },
    )
    val canSave = !saving && (!nodeNameMatchingEnabled || !nodeNameRegexInvalid) && runCatching {
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
    val selectionOptionsMotion = AsteriskMotion.fastEffects<Float>()
    val memberFadeIn = AsteriskMotion.contentFade()
    val memberFadeOut = AsteriskMotion.fastEffects<Float>()
    val memberPlacement = AsteriskMotion.contentPlacement()
    val resolvedDefault = draft.default
    val defaultOptionTags = selectorDefaultOptionTags(effectiveMembers)
    val toggleableState = when (selectionState) {
        SelectorTargetSelectionState.None -> ToggleableState.Off
        SelectorTargetSelectionState.Partial -> ToggleableState.Indeterminate
        SelectorTargetSelectionState.All -> ToggleableState.On
    }
    val regexToggleDescription = stringResource(
        if (regexEnabled) {
            R.string.selector_editor_regex_disable
        } else {
            R.string.selector_editor_regex_enable
        },
    )
    val bulkSelectionDescription = stringResource(
        if (selectionState == SelectorTargetSelectionState.All) {
            R.string.selector_editor_clear_all_matches
        } else {
            R.string.selector_editor_select_all_matches
        },
    )

    fun updateMemberReferences(updated: List<String>) {
        memberReferences = updated
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()
        val nextEffectiveMembers = selectorEffectiveMemberTags(
            state = state,
            memberReferences = memberReferences,
            targets = targetChoices,
        )
        if (default !in nextEffectiveMembers) {
            default = nextEffectiveMembers.firstOrNull().orEmpty()
        }
    }

    fun toggleMember(target: String) {
        if (target in lockedOutboundTags) return
        updateMemberReferences(
            if (target in memberReferences) {
                memberReferences - target
            } else {
                memberReferences + target
            },
        )
    }

    fun toggleVisibleMembers() {
        updateMemberReferences(
            updateSelectorMemberReferencesForMatches(
                state = state,
                memberReferences = memberReferences,
                matchedTags = interactiveVisibleTargetTags,
                select = selectionState != SelectorTargetSelectionState.All,
            ),
        )
    }

    EditorPageScaffold(
        outerPadding = outerPadding,
        isWideScreen = isWideScreen,
        title = {
            Text(
                stringResource(
                    if (selector?.id == 0) {
                        R.string.selector_editor_add
                    } else {
                        R.string.selector_editor_edit
                    },
                ),
            )
        },
        saving = saving,
        saveEnabled = canSave,
        onBack = onDismissRequest,
        onSave = { onSave(draft) },
    ) { contentPadding ->
        val settings: LazyListScope.() -> Unit = {
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
                    horizontalPadding = 0.dp,
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
            item(key = "connection-options") {
                Column {
                    SettingsSwitchRow(
                        title = stringResource(R.string.selector_editor_interrupt),
                        horizontalPadding = 0.dp,
                        summary = stringResource(R.string.selector_editor_interrupt_summary),
                        icon = Icons.Rounded.Sync,
                        checked = interrupt,
                        onCheckedChange = { interrupt = it },
                    )
                    AnimatedVisibility(
                        visible = connectionOptions.showUrlTestSettings,
                        enter = AsteriskMotion.contentEnter(),
                        exit = AsteriskMotion.contentExit(),
                    ) {
                        Column(
                            modifier = Modifier.padding(top = 14.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text(
                                stringResource(R.string.selector_editor_urltest),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            SettingsDropdownRow(
                                title = stringResource(R.string.selector_editor_urltest_selection),
                                horizontalPadding = 0.dp,
                                summary = stringResource(
                                    if (fallbackEnabled) {
                                        R.string.selector_editor_urltest_fallback_summary
                                    } else {
                                        R.string.selector_editor_urltest_latency_summary
                                    },
                                ),
                                icon = Icons.AutoMirrored.Rounded.AltRoute,
                                items = listOf(
                                    stringResource(R.string.selector_editor_urltest_latency),
                                    stringResource(R.string.selector_editor_urltest_fallback),
                                ),
                                selectedIndex = if (fallbackEnabled) 1 else 0,
                                onSelectedIndexChange = { fallbackEnabled = it == 1 },
                            )
                            AnimatedContent(
                                targetState = fallbackEnabled,
                                transitionSpec = AsteriskMotion.fadeThrough(selectionOptionsMotion),
                                label = "urltest-selection-options",
                            ) { showFallbackMaxDelay ->
                                if (showFallbackMaxDelay) {
                                    OutlinedTextField(
                                        value = fallbackMaxDelay,
                                        onValueChange = { fallbackMaxDelay = it },
                                        label = {
                                            Text(stringResource(R.string.selector_editor_urltest_fallback_max_delay))
                                        },
                                        placeholder = {
                                            Text(stringResource(R.string.selector_editor_urltest_fallback_max_delay_placeholder))
                                        },
                                        isError = fallbackMaxDelayInvalid,
                                        supportingText = {
                                            Text(
                                                stringResource(
                                                    if (fallbackMaxDelayInvalid) {
                                                        R.string.selector_editor_urltest_fallback_max_delay_invalid
                                                    } else {
                                                        R.string.selector_editor_urltest_fallback_max_delay_summary
                                                    },
                                                ),
                                            )
                                        },
                                        singleLine = true,
                                        shape = AsteriskShapeTokens.InnerContainer,
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                } else {
                                    OutlinedTextField(
                                        value = tolerance,
                                        onValueChange = { tolerance = it },
                                        label = {
                                            Text(stringResource(R.string.selector_editor_urltest_tolerance))
                                        },
                                        placeholder = { Text(DefaultSingBoxUrlTestTolerance.toString()) },
                                        isError = toleranceInvalid,
                                        supportingText = {
                                            Text(
                                                stringResource(
                                                    if (toleranceInvalid) {
                                                        R.string.selector_editor_urltest_tolerance_invalid
                                                    } else {
                                                        R.string.selector_editor_urltest_tolerance_summary
                                                    },
                                                ),
                                            )
                                        },
                                        keyboardOptions = KeyboardOptions(
                                            keyboardType = KeyboardType.Number,
                                        ),
                                        singleLine = true,
                                        shape = AsteriskShapeTokens.InnerContainer,
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                }
                            }
                            OutlinedTextField(
                                value = url,
                                onValueChange = { url = it },
                                label = {
                                    Text(stringResource(R.string.selector_editor_urltest_url))
                                },
                                placeholder = { Text(DefaultSingBoxUrlTestUrl) },
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
                                    Text(
                                        stringResource(
                                            R.string.selector_editor_urltest_interval,
                                        ),
                                    )
                                },
                                placeholder = { Text(DefaultSingBoxUrlTestInterval) },
                                isError = intervalInvalid || intervalExceedsIdleTimeout,
                                supportingText = {
                                    Text(
                                        stringResource(
                                            if (intervalInvalid) {
                                                R.string.selector_editor_urltest_duration_invalid
                                            } else if (intervalExceedsIdleTimeout) {
                                                R.string.selector_editor_urltest_interval_exceeds_idle
                                            } else {
                                                R.string.selector_editor_urltest_interval_summary
                                            },
                                        ),
                                    )
                                },
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
                                placeholder = { Text(DefaultSingBoxUrlTestIdleTimeout) },
                                isError = idleTimeoutInvalid || intervalExceedsIdleTimeout,
                                supportingText = {
                                    Text(
                                        stringResource(
                                            if (idleTimeoutInvalid) {
                                                R.string.selector_editor_urltest_duration_invalid
                                            } else if (intervalExceedsIdleTimeout) {
                                                R.string.selector_editor_urltest_interval_exceeds_idle
                                            } else {
                                                R.string.selector_editor_urltest_idle_timeout_summary
                                            },
                                        ),
                                    )
                                },
                                singleLine = true,
                                shape = AsteriskShapeTokens.InnerContainer,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }
        }
        val defaultOutboundSetting: @Composable () -> Unit = {
            AnimatedVisibility(
                visible = memberHeader.showDefaultOutbound,
                enter = AsteriskMotion.contentEnter(),
                exit = AsteriskMotion.contentExit(),
            ) {
                SettingsDropdownRow(
                    title = stringResource(R.string.selector_editor_default),
                    horizontalPadding = 0.dp,
                    icon = Icons.AutoMirrored.Rounded.AltRoute,
                    items = defaultOptionTags.map { member ->
                        member?.let { tag ->
                            targetsByTag[tag]?.displayLabel()
                        } ?: stringResource(R.string.selector_target_unavailable)
                    },
                    selectedIndex = if (effectiveMembers.isEmpty()) {
                        0
                    } else {
                        selectorDefaultMemberIndex(effectiveMembers, resolvedDefault)
                    },
                    onSelectedIndexChange = { index ->
                        defaultOptionTags.getOrNull(index)?.let { member ->
                            default = member
                        }
                    },
                    enabled = effectiveMembers.isNotEmpty(),
                )
            }
        }
        val membershipSwitch: @Composable () -> Unit = {
            SettingsSwitchRow(
                title = stringResource(R.string.selector_editor_dynamic_members),
                summary = stringResource(R.string.selector_editor_dynamic_members_summary),
                icon = Icons.Rounded.FilterAlt,
                checked = nodeNameMatchingEnabled,
                onCheckedChange = { enabled ->
                    focusManager.clearFocus()
                    if (enabled && nodeNameRegexInput.isBlank() && regexEnabled) {
                        nodeNameRegexInput = query
                    }
                    nodeNameMatchingEnabled = enabled
                },
                horizontalPadding = 0.dp,
            )
        }
        val matchingFields: @Composable () -> Unit = {
            Column {
                SelectorMatchGroupPicker(
                    groups = state.outboundGroups,
                    selectedGroupIds = matchOutboundGroupIds,
                    onSelectedGroupIdsChange = { matchOutboundGroupIds = it },
                )
                OutlinedTextField(
                    value = nodeNameRegexInput,
                    onValueChange = { nodeNameRegexInput = it },
                    label = { Text(stringResource(R.string.selector_editor_node_name_regex)) },
                    supportingText = {
                        Text(
                            when {
                                nodeNameRegex.isEmpty() ->
                                    stringResource(R.string.selector_editor_node_name_regex_required)
                                nodeNameRegexInvalid ->
                                    stringResource(R.string.selector_editor_regex_invalid)
                                else -> pluralStringResource(
                                    R.plurals.selector_editor_node_name_matches,
                                    regexMemberTags.size,
                                    regexMemberTags.size,
                                )
                            },
                        )
                    },
                    isError = nodeNameRegexInvalid,
                    singleLine = true,
                    shape = AsteriskShapeTokens.InnerContainer,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                )
            }
        }
        val manualMemberSearch: @Composable (Boolean) -> Unit = { split ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = if (split) 0.dp else 14.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Top,
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = {
                        Text(
                            text = stringResource(R.string.selector_editor_search_targets),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    leadingIcon = {
                        Icon(Icons.Rounded.Search, contentDescription = null)
                    },
                    trailingIcon = {
                        val nodesOnlyDescription = stringResource(R.string.selector_editor_nodes_only)
                        val toggleColors = IconButtonDefaults.iconToggleButtonColors(
                            checkedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            checkedContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 44.dp) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconToggleButton(
                                    checked = regexEnabled,
                                    onCheckedChange = { regexEnabled = it },
                                    colors = toggleColors,
                                    modifier = Modifier.semantics {
                                        contentDescription = regexToggleDescription
                                    },
                                ) {
                                    Text(
                                        text = ".*",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                }
                                IconToggleButton(
                                    checked = nodesOnly,
                                    onCheckedChange = { nodesOnly = it },
                                    colors = toggleColors,
                                    modifier = Modifier.semantics {
                                        contentDescription = nodesOnlyDescription
                                    },
                                ) {
                                    Icon(Icons.Rounded.Router, contentDescription = null)
                                }
                            }
                        }
                    },
                    isError = searchInvalid,
                    supportingText = if (searchInvalid) {
                        {
                            Text(stringResource(R.string.selector_editor_regex_invalid))
                        }
                    } else {
                        null
                    },
                    singleLine = true,
                    shape = AsteriskShapeTokens.InnerContainer,
                    modifier = Modifier.weight(1f),
                )
                Box(
                    modifier = Modifier.width(48.dp).height(56.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    TriStateCheckbox(
                        state = toggleableState,
                        onClick = ::toggleVisibleMembers,
                        enabled = !searchInvalid &&
                            interactiveVisibleTargetTags.isNotEmpty(),
                        modifier = Modifier.semantics {
                            contentDescription = bulkSelectionDescription
                        },
                    )
                }
            }
        }
        val members: LazyListScope.(Boolean) -> Unit = { split ->
            item(key = "members-header") {
                Column {
                    if (!split) {
                        Box(Modifier.padding(bottom = if (memberHeader.showDefaultOutbound) 14.dp else 0.dp)) {
                            defaultOutboundSetting()
                        }
                    }
                    Text(
                        stringResource(R.string.selector_editor_members),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            item(key = "dynamic-members") {
                Column {
                    if (split) {
                        AnimatedVisibility(
                            visible = !nodeNameMatchingEnabled,
                            enter = AsteriskMotion.contentEnter(),
                            exit = AsteriskMotion.contentExit(),
                        ) { manualMemberSearch(true) }
                    } else {
                        membershipSwitch()
                        AnimatedContent(
                            targetState = nodeNameMatchingEnabled,
                            transitionSpec = AsteriskMotion.fadeThrough(
                                effectsSpec = memberFadeIn,
                                sizeSpec = AsteriskMotion.contentSize(),
                            ),
                            label = "selector-member-source",
                        ) { dynamicMembers ->
                            if (dynamicMembers) matchingFields() else manualMemberSearch(false)
                        }
                    }
                }
            }
            if (nodeNameMatchingEnabled) {
                items(matchedTargets, key = { target -> "matched:${target.tag}" }) { target ->
                    SelectorMatchedMemberRow(
                        target = target,
                        modifier = Modifier.animateItem(
                            fadeInSpec = memberFadeIn,
                            fadeOutSpec = memberFadeOut,
                            placementSpec = memberPlacement,
                        ),
                    )
                }
            } else {
                if (effectiveMembers.isEmpty()) {
                    item(key = "members-required") {
                        Text(
                            stringResource(R.string.selector_editor_members_required),
                            modifier = Modifier.animateItem(
                                fadeInSpec = memberFadeIn,
                                fadeOutSpec = memberFadeOut,
                                placementSpec = memberPlacement,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                items(visibleTargets, key = SelectorTargetUi::tag) { target ->
                    SelectorTargetRow(
                        target = target,
                        selected = target.tag in memberReferences || target.tag in effectiveMembers,
                        enabled = target.tag !in lockedOutboundTags,
                        lockedByGroup = target.tag in lockedOutboundTags,
                        modifier = Modifier.animateItem(
                            fadeInSpec = memberFadeIn,
                            fadeOutSpec = memberFadeOut,
                            placementSpec = memberPlacement,
                        ),
                        onClick = { toggleMember(target.tag) },
                    )
                }
            }
        }
        val settingsListState = rememberLazyListState()
        val membersListState = rememberLazyListState()
        val compactListState = rememberLazyListState()
        BoxWithConstraints(Modifier.pageReadableWidth(maxWidth = 1160.dp).fillMaxSize()) {
            if (
                LocalAdaptiveWindow.current.supportsWideContent &&
                maxWidth >= 760.dp && LocalDensity.current.fontScale <= 1.3f
            ) {
                Row(Modifier.fillMaxSize()) {
                    LazyColumn(
                        modifier = Modifier.weight(0.9f).fillMaxHeight(),
                        state = settingsListState,
                        contentPadding = contentPadding,
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        settings()
                        if (memberHeader.showDefaultOutbound) {
                            item("default-outbound") { defaultOutboundSetting() }
                        }
                        item("membership-settings") {
                            Column {
                                membershipSwitch()
                                AnimatedVisibility(
                                    visible = nodeNameMatchingEnabled,
                                    enter = AsteriskMotion.contentEnter(),
                                    exit = AsteriskMotion.contentExit(),
                                ) { matchingFields() }
                            }
                        }
                    }
                    LazyColumn(
                        modifier = Modifier.weight(1.1f).fillMaxHeight(),
                        state = membersListState,
                        contentPadding = contentPadding,
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) { members(true) }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    state = compactListState,
                    contentPadding = contentPadding,
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    settings()
                    members(false)
                }
            }
        }
    }
}

@Composable
private fun SelectorMatchGroupPicker(
    groups: List<OutboundGroupState>,
    selectedGroupIds: List<Int>?,
    onSelectedGroupIdsChange: (List<Int>?) -> Unit,
) {
    var showPicker by remember { mutableStateOf(false) }
    val title = stringResource(R.string.selector_editor_match_groups)
    val allGroups = stringResource(R.string.selector_editor_match_all_groups)
    val focusManager = LocalFocusManager.current
    SettingsActionRow(
        title = title,
        icon = Icons.Rounded.Folder,
        value = selectedGroupIds?.size?.let { count ->
            pluralStringResource(R.plurals.outbound_group_count, count, count)
        } ?: allGroups,
        horizontalPadding = 0.dp,
        onClick = {
            focusManager.clearFocus()
            showPicker = true
        },
    )
    if (showPicker) {
        var selection by remember { mutableStateOf(selectedGroupIds) }
        AlertDialog(
            onDismissRequest = { showPicker = false },
            title = { Text(title) },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    (listOf(null) + groups).forEach { group ->
                        val groupId = group?.id
                        val checked = if (groupId == null) selection == null else groupId in selection.orEmpty()
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .toggleable(
                                    value = checked,
                                    role = Role.Checkbox,
                                    onValueChange = { selected ->
                                        selection = when {
                                            groupId == null -> if (selected) null else emptyList()
                                            selected -> selection.orEmpty() + groupId
                                            else -> selection.orEmpty() - groupId
                                        }
                                    },
                                ),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            AsteriskCheckbox(checked = checked, onCheckedChange = null)
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = group?.name ?: allGroups,
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                                if (group?.enabled == false) {
                                    Text(
                                        text = stringResource(R.string.selector_group_disabled),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            },
            dismissButton = { AsteriskCancelButton(onClick = { showPicker = false }) },
            confirmButton = {
                AsteriskActionButton(
                    text = stringResource(R.string.common_ok),
                    icon = Icons.Rounded.Check,
                    enabled = selection?.isNotEmpty() != false,
                    onClick = {
                        onSelectedGroupIdsChange(selection)
                        showPicker = false
                    },
                )
            },
        )
    }
}

@Composable
private fun SelectorMatchedMemberRow(
    target: SelectorTargetUi,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        shape = AsteriskShapeTokens.InnerContainer,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .padding(horizontal = 14.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.Router, contentDescription = null)
            Text(
                text = target.displayLabel().ifBlank { target.tag },
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun SelectorTargetRow(
    target: SelectorTargetUi,
    selected: Boolean,
    enabled: Boolean,
    lockedByGroup: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val containerColor by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        animationSpec = AsteriskMotion.effects(),
        label = "selector-target-color",
    )
    val lockedStateDescription = stringResource(R.string.selector_target_locked_by_group)
    Card(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.semantics {
            if (lockedByGroup) stateDescription = lockedStateDescription
        },
        colors = CardDefaults.cardColors(
            containerColor = containerColor,
            disabledContainerColor = containerColor,
        ),
        shape = AsteriskShapeTokens.InnerContainer,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                when (target.kind) {
                    SelectorTargetKind.Group -> Icons.Rounded.Folder
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
            Column(
                modifier = Modifier
                    .weight(1f),
            ) {
                Text(
                    target.displayLabel(),
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    stringResource(
                        when (target.kind) {
                            SelectorTargetKind.Group -> R.string.selector_target_group
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
            Checkbox(
                checked = selected,
                onCheckedChange = null,
                enabled = enabled,
            )
        }
    }
}

private fun buildSelectorTargets(
    choices: List<ManagedOutboundChoice>,
): List<SelectorTargetUi> = choices.map { choice ->
    SelectorTargetUi(
        tag = choice.tag,
        label = choice.label,
        groupName = choice.groupName,
        kind = when (choice.kind) {
            ManagedOutboundChoiceKind.Group -> SelectorTargetKind.Group
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
