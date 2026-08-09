// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

@file:OptIn(
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)

package features.routing

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import app.AppState
import app.LocalAppServices
import app.LocalAppStateStore
import app.LocalIsWideScreen
import app.LocalNavigator
import app.LocalUpdateAppState
import app.SingBoxRouteRuleActionReject
import app.SingBoxRouteRuleActionRoute
import app.SingBoxRouteRuleClashModes
import app.SingBoxRouteRuleLogicalModeAnd
import app.SingBoxRouteRuleLogicalModeOr
import app.SingBoxRouteRuleState
import app.SingBoxRouteRuleTypeDefault
import app.SingBoxRouteRuleTypeLogical
import app.collectAppState
import app.managedInboundTags
import app.managedRuleSetChoices
import app.selectableManagedOutbounds
import engine.network.isCidrAddress
import engine.singbox.config.APP_GLOBAL_SELECTOR
import engine.singbox.config.validateSingBoxRuntimeConfiguration
import features.logs.FailureLogContext
import features.logs.reportFailure
import features.resources.runtime.singBoxRuleSetFiles
import features.settings.SettingsDropdownRow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.asterisk.zcc.abox.R
import sh.calvin.reorderable.ReorderableItem
import ui.components.AsteriskExpressiveCard
import ui.components.EditorPageScaffold
import ui.components.localizedLabel
import ui.components.AsteriskFilterChip
import ui.components.AsteriskInfoChip
import ui.components.ReferenceSelectionCard
import ui.components.StringListEditor
import ui.components.WarningConfirmDialog
import ui.components.draggedCardShadow
import ui.components.longPressReorderDragHandle
import ui.components.rememberAsteriskReorderableLazyGridState
import ui.components.singBoxOptionLabel
import ui.components.managedInboundChoices
import ui.components.singBoxProtocolChoices
import ui.components.verticalReorderScrollThresholdPadding
import ui.icons.AsteriskIcons as Icons
import ui.layout.pageContentPaddingWithCutout
import ui.layout.pageListPadding
import ui.theme.AsteriskMotion
import ui.theme.AsteriskShapeTokens

internal data class RouteChoice(
    val value: String,
    val label: String,
)

@Composable
internal fun RoutingManagementPage(
    padding: PaddingValues,
) {
    val appState by LocalAppStateStore.current.collectAppState()
    val updateAppState = LocalUpdateAppState.current
    val navigator = LocalNavigator.current
    val context = LocalContext.current
    val tipNotifier = LocalAppServices.current.tipNotifier
    val scope = rememberCoroutineScope()
    val isWideScreen = LocalIsWideScreen.current
    var editingRouteSettings by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<SingBoxRouteRuleState?>(null) }
    var savingRouteSettings by remember { mutableStateOf(false) }
    val settingsSaveFailedMessage = stringResource(R.string.routing_settings_save_failed)
    val outboundChoices = managedOutboundChoices(appState)
    val outboundLabels = outboundChoices.associate { choice -> choice.value to choice.label }
    val unavailableLabel = stringResource(R.string.common_unavailable)
    val globalLabel = stringResource(R.string.routing_global)
    val inboundChoices = managedInboundChoices(managedInboundTags(appState))
    val ruleSetChoices = remember(appState.customResourceFiles) {
        appState.managedRuleSetChoices(
            context.singBoxRuleSetFiles(appState.customResourceFiles).map { file -> file.name },
        ).map { choice -> choice.tag to choice.remarks }
    }
    val ruleReferenceLabels = buildMap {
        putAll(outboundLabels)
        putAll(inboundChoices)
        putAll(ruleSetChoices)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.routing_title))
                        val countMotion = AsteriskMotion.fastEffects<Float>()
                        AnimatedContent(
                            targetState = appState.routeRules.size,
                            transitionSpec = AsteriskMotion.fadeThrough(countMotion),
                            label = "routing-rule-count",
                        ) { count ->
                            Text(
                                text = pluralStringResource(R.plurals.routing_rule_count, count, count),
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
                        onClick = { navigator.push(app.navigation.Route.RouteRuleEdit()) },
                    ) {
                        Icon(Icons.Rounded.Add, stringResource(R.string.routing_add_rule))
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
        RoutingRuleGrid(
            rules = appState.routeRules,
            columns = if (isWideScreen) 2 else 1,
            contentPadding = pageListPadding(contentPadding, bottomExtra = 24.dp),
            finalOutbound = appState.routeFinal.ifBlank { APP_GLOBAL_SELECTOR },
            outboundChoices = outboundChoices,
            referenceLabels = ruleReferenceLabels,
            unavailableLabel = unavailableLabel,
            globalLabel = globalLabel,
            onOpenRouteSettings = { editingRouteSettings = true },
            onFinalOutboundChange = { outbound ->
                updateAppState { state -> state.copy(routeFinal = outbound) }
            },
            onMove = { fromIndex, toIndex ->
                updateAppState { state ->
                    state.copy(routeRules = state.routeRules.moveRouteRule(fromIndex, toIndex))
                }
            },
            onEnabledChange = { rule, enabled ->
                updateAppState { state ->
                    state.copy(
                        routeRules = state.routeRules.map { current ->
                            if (current.id == rule.id) current.copy(enabled = enabled) else current
                        },
                    )
                }
            },
            onEdit = { rule ->
                navigator.push(app.navigation.Route.RouteRuleEdit(ruleId = rule.id))
            },
            onDelete = { pendingDelete = it },
        )
    }

    RoutingSettingsSheet(
        show = editingRouteSettings,
        initialDraft = appState.toRoutingSettingsDraft(),
        saving = savingRouteSettings,
        onDismiss = { editingRouteSettings = false },
        onSave = { saved ->
            if (!savingRouteSettings) {
                val baseState = appState
                val candidateState = baseState.withRoutingSettings(saved)
                savingRouteSettings = true
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            validateSingBoxRuntimeConfiguration(context, candidateState)
                        }
                        var committed = false
                        updateAppState { current ->
                            if (current === baseState) {
                                committed = true
                                candidateState
                            } else {
                                current
                            }
                        }
                        if (committed) {
                            editingRouteSettings = false
                        } else {
                            tipNotifier.show(settingsSaveFailedMessage)
                        }
                    } catch (error: Throwable) {
                        if (error is CancellationException) throw error
                        reportFailure(
                            context = FailureLogContext(
                                operation = "save_route_settings",
                                stage = "validate",
                            ),
                            error = error,
                        )
                        tipNotifier.show(settingsSaveFailedMessage)
                    } finally {
                        savingRouteSettings = false
                    }
                }
            }
        },
    )

    WarningConfirmDialog(
        show = pendingDelete != null,
        title = stringResource(R.string.routing_delete_title),
        summary = stringResource(
            R.string.routing_delete_message,
            pendingDelete?.remarks?.takeIf(String::isNotBlank)
                ?: stringResource(R.string.routing_unnamed_rule),
        ),
        dismissText = stringResource(R.string.common_cancel),
        confirmText = stringResource(R.string.common_delete),
        onDismissRequest = { pendingDelete = null },
        onConfirm = {
            val ruleId = pendingDelete?.id
            if (ruleId != null) {
                updateAppState { state ->
                    state.copy(routeRules = state.routeRules.filterNot { rule -> rule.id == ruleId })
                }
            }
            pendingDelete = null
        },
    )
}

@Composable
internal fun managedOutboundChoices(appState: AppState): List<RouteChoice> {
    return selectableManagedOutbounds(appState)
        .map { choice ->
            RouteChoice(
                value = choice.tag,
                label = choice.localizedLabel(),
            )
        }
        .distinctBy(RouteChoice::value)
}

@Composable
private fun RoutingRuleGrid(
    rules: List<SingBoxRouteRuleState>,
    columns: Int,
    contentPadding: PaddingValues,
    finalOutbound: String,
    outboundChoices: List<RouteChoice>,
    referenceLabels: Map<String, String>,
    unavailableLabel: String,
    globalLabel: String,
    onOpenRouteSettings: () -> Unit,
    onFinalOutboundChange: (String) -> Unit,
    onMove: (Int, Int) -> Unit,
    onEnabledChange: (SingBoxRouteRuleState, Boolean) -> Unit,
    onEdit: (SingBoxRouteRuleState) -> Unit,
    onDelete: (SingBoxRouteRuleState) -> Unit,
) {
    val gridState = rememberLazyGridState()
    val reorderableState = rememberAsteriskReorderableLazyGridState(
        lazyGridState = gridState,
        itemCount = rules.size,
        indexOffset = 2,
        scrollThresholdPadding = verticalReorderScrollThresholdPadding(contentPadding),
        onMove = onMove,
    )
    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        state = gridState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "route-settings", span = { GridItemSpan(maxLineSpan) }) {
            RoutingSettingsEntryCard(onClick = onOpenRouteSettings)
        }
        item(key = "final-outbound", span = { GridItemSpan(maxLineSpan) }) {
            RouteChoiceCard(
                title = stringResource(R.string.routing_default_outbound),
                summary = stringResource(R.string.routing_default_outbound_summary),
                choices = outboundChoices,
                selectedValue = finalOutbound,
                unavailableLabel = unavailableLabel,
                onSelected = onFinalOutboundChange,
            )
        }
        if (rules.isEmpty()) {
            item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                RoutingEmptyState()
            }
        } else {
            items(
                items = rules,
                key = SingBoxRouteRuleState::id,
                contentType = { "route-rule" },
            ) { rule ->
                ReorderableItem(
                    state = reorderableState.reorderableState,
                    key = rule.id,
                    modifier = Modifier.fillMaxWidth(),
                    animateItemModifier = Modifier.animateItem(),
                ) { isDragging ->
                    RouteRuleCard(
                        rule = rule,
                        referenceLabels = referenceLabels,
                        unavailableLabel = unavailableLabel,
                        globalLabel = globalLabel,
                        isDragging = isDragging,
                        onEnabledChange = { enabled -> onEnabledChange(rule, enabled) },
                        onEdit = { onEdit(rule) },
                        onDelete = { onDelete(rule) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .longPressReorderDragHandle(
                                scope = this,
                                enabled = rules.size > 1,
                                state = reorderableState,
                            ),
                    )
                }
            }
        }
    }
}

@Composable
private fun RoutingEmptyState() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 240.dp)
            .padding(horizontal = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Rounded.AltRoute,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(48.dp),
        )
        Text(
            text = stringResource(R.string.routing_empty),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 16.dp),
        )
        Text(
            text = stringResource(R.string.routing_empty_summary),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

@Composable
private fun RouteRuleCard(
    rule: SingBoxRouteRuleState,
    referenceLabels: Map<String, String>,
    unavailableLabel: String,
    globalLabel: String,
    isDragging: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val containerColor by animateColorAsState(
        targetValue = if (isDragging) {
            MaterialTheme.colorScheme.surfaceContainerHigh
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        },
        animationSpec = AsteriskMotion.effects(),
        label = "routing-card-color",
    )
    val scale by animateFloatAsState(
        targetValue = if (isDragging) 1.025f else 1f,
        animationSpec = AsteriskMotion.fastSpatial(),
        label = "routing-card-scale",
    )
    val shadowAlpha by animateFloatAsState(
        targetValue = if (isDragging) 1f else 0f,
        animationSpec = AsteriskMotion.fastEffects(),
        label = "routing-card-shadow",
    )
    Card(
        onClick = onEdit,
        modifier = modifier
            .heightIn(min = 120.dp)
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
            .alpha(if (rule.enabled) 1f else 0.68f),
        shape = AsteriskShapeTokens.ListCard,
        colors = CardDefaults.cardColors(containerColor = containerColor),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 120.dp)
                .padding(start = 18.dp, top = 14.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f).padding(end = 14.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Text(
                    text = rule.remarks.takeIf(String::isNotBlank)
                        ?: stringResource(R.string.routing_unnamed_rule),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = if (rule.action == SingBoxRouteRuleActionReject) {
                        stringResource(R.string.routing_action_reject)
                    } else {
                        routeRuleOutboundLabel(
                            rule = rule,
                            labels = referenceLabels,
                            unavailableLabel = unavailableLabel,
                            globalLabel = globalLabel,
                        )
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    rule.routeRuleCardMatches().forEach { match ->
                        AsteriskInfoChip(
                            text = routeRuleMatchChipLabel(
                                match = match,
                                referenceLabels = referenceLabels,
                                unavailableLabel = unavailableLabel,
                            ),
                            emphasized = rule.invert,
                        )
                    }
                }
            }
            Switch(
                checked = rule.enabled,
                onCheckedChange = onEnabledChange,
            )
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
                    HorizontalDivider()
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
private fun RouteChoiceCard(
    title: String,
    summary: String,
    choices: List<RouteChoice>,
    selectedValue: String,
    onSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
    unavailableLabel: String = stringResource(R.string.common_unavailable),
) {
    var expanded by remember { mutableStateOf(false) }
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = AsteriskMotion.fastEffects(),
        label = "routing-choice-arrow",
    )
    val selected = choices.firstOrNull { choice -> choice.value == selectedValue }
        ?: RouteChoice(
            selectedValue,
            app.visibleManagedReference(
                value = selectedValue,
                labels = choices.associate { choice -> choice.value to choice.label },
                unavailableLabel = unavailableLabel,
            ),
        )
    val labelMotion = AsteriskMotion.fastEffects<Float>()
    Box {
        Card(
            onClick = { expanded = true },
            modifier = modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
            ),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 14.dp)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    if (summary.isNotBlank()) {
                        Text(
                            summary,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                AnimatedContent(
                    targetState = selected.label,
                    modifier = Modifier.widthIn(max = 160.dp),
                    transitionSpec = AsteriskMotion.fadeThrough(labelMotion),
                    label = "routing-choice-label",
                ) { label ->
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Icon(
                    Icons.Rounded.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier.rotate(rotation),
                )
            }
        }
        Box(
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 28.dp).size(1.dp),
        ) {
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier.widthIn(min = 220.dp, max = 320.dp),
            ) {
                choices.forEach { choice ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = choice.label,
                                color = if (choice.value == selectedValue) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                            )
                        },
                        leadingIcon = {
                            if (choice.value == selectedValue) {
                                Icon(
                                    Icons.Rounded.Check,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            } else {
                                Spacer(Modifier.size(24.dp))
                            }
                        },
                        onClick = {
                            expanded = false
                            onSelected(choice.value)
                        },
                    )
                }
            }
        }
    }
}

@Composable
internal fun RouteRuleEditorScaffold(
    outerPadding: PaddingValues,
    isWideScreen: Boolean,
    rule: SingBoxRouteRuleState,
    outboundChoices: List<RouteChoice>,
    inboundChoices: List<Pair<String, String>>,
    ruleSetChoices: List<Pair<String, String>>,
    saving: Boolean = false,
    onDismiss: () -> Unit,
    onSave: (SingBoxRouteRuleState) -> Unit,
    onEditChild: (SingBoxRouteRuleState, SingBoxRouteRuleState) -> Unit,
    nested: Boolean = false,
) {
    var draft by remember(rule.id) { mutableStateOf<SingBoxRouteRuleState?>(rule) }
    var pendingChildDelete by remember { mutableStateOf<SingBoxRouteRuleState?>(null) }
    LaunchedEffect(rule) {
        draft = rule
        pendingChildDelete = null
    }
    val fieldEffectsMotion = AsteriskMotion.effects<Float>()
    val fieldSizeMotion = AsteriskMotion.contentSpatial<IntSize>()
    EditorPageScaffold(
        outerPadding = outerPadding,
        isWideScreen = isWideScreen,
        title = {
            Text(
                stringResource(
                    when {
                        nested && draft?.remarks?.isNotBlank() == true -> {
                            R.string.routing_edit_condition
                        }
                        nested -> R.string.routing_new_condition
                        draft?.remarks?.isNotBlank() == true -> R.string.routing_edit_rule
                        else -> R.string.routing_new_rule
                    },
                ),
            )
        },
        saving = saving,
        saveEnabled = draft != null,
        onBack = onDismiss,
        onSave = { draft?.let(onSave) },
    ) { contentPadding ->
        draft?.let { current ->
            AnimatedContent(
                targetState = current.type,
                modifier = Modifier.fillMaxWidth(),
                transitionSpec = AsteriskMotion.fadeThrough(
                    effectsSpec = fieldEffectsMotion,
                    sizeSpec = fieldSizeMotion,
                ),
                contentAlignment = Alignment.TopStart,
                label = "routing-rule-type-fields",
            ) { visibleType ->
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = contentPadding,
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                item(key = "basic-title") {
                    RouteEditorSectionTitle(stringResource(R.string.routing_section_basic))
                }
                item(key = "name") {
                    OutlinedTextField(
                        value = current.remarks,
                        onValueChange = { draft = current.copy(remarks = it) },
                        modifier = Modifier.fillMaxWidth(),
                        label = {
                            Text(
                                stringResource(
                                    if (nested) {
                                        R.string.routing_condition_name
                                    } else {
                                        R.string.routing_rule_remarks
                                    },
                                ),
                            )
                        },
                        singleLine = true,
                    )
                }
                item(key = "type") {
                    RouteChoiceCard(
                        title = stringResource(R.string.routing_rule_type),
                        summary = "",
                        choices = listOf(
                            RouteChoice(
                                SingBoxRouteRuleTypeDefault,
                                singBoxOptionLabel(
                                    stringResource(R.string.routing_rule_type_default),
                                    SingBoxRouteRuleTypeDefault,
                                ),
                            ),
                            RouteChoice(
                                SingBoxRouteRuleTypeLogical,
                                singBoxOptionLabel(
                                    stringResource(R.string.routing_rule_type_logical),
                                    SingBoxRouteRuleTypeLogical,
                                ),
                            ),
                        ),
                        selectedValue = current.type,
                        onSelected = { value -> draft = current.copy(type = value) },
                    )
                }
                if (!nested) {
                    item(key = "action") {
                        RouteChoiceCard(
                            title = stringResource(R.string.routing_action),
                            summary = "",
                            choices = listOf(
                                RouteChoice(
                                    SingBoxRouteRuleActionRoute,
                                    singBoxOptionLabel(
                                        stringResource(R.string.routing_action_route),
                                        SingBoxRouteRuleActionRoute,
                                    ),
                                ),
                                RouteChoice(
                                    SingBoxRouteRuleActionReject,
                                    singBoxOptionLabel(
                                        stringResource(R.string.routing_action_reject),
                                        SingBoxRouteRuleActionReject,
                                    ),
                                ),
                            ),
                            selectedValue = current.action,
                            onSelected = { value -> draft = current.copy(action = value) },
                        )
                    }
                    item(key = "action-fields") {
                        AnimatedContent(
                            targetState = current.action,
                            modifier = Modifier.fillMaxWidth(),
                            transitionSpec = AsteriskMotion.fadeThrough(
                                effectsSpec = fieldEffectsMotion,
                                sizeSpec = fieldSizeMotion,
                            ),
                            contentAlignment = Alignment.TopStart,
                            label = "routing-action-fields",
                        ) { visibleAction ->
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                if (visibleAction == SingBoxRouteRuleActionRoute) {
                                    RouteChoiceCard(
                                        title = stringResource(R.string.routing_outbound),
                                        summary = "",
                                        choices = outboundChoices,
                                        selectedValue = current.outbound.ifBlank {
                                            APP_GLOBAL_SELECTOR
                                        },
                                        onSelected = { value ->
                                            draft = current.copy(outbound = value)
                                        },
                                    )
                                } else {
                                    RouteChoiceCard(
                                        title = stringResource(R.string.routing_reject_method),
                                        summary = "",
                                        choices = listOf(
                                            RouteChoice(
                                                "default",
                                                singBoxOptionLabel(
                                                    stringResource(R.string.routing_reject_default),
                                                    "default",
                                                ),
                                            ),
                                            RouteChoice(
                                                "drop",
                                                singBoxOptionLabel(
                                                    stringResource(R.string.routing_reject_drop),
                                                    "drop",
                                                ),
                                            ),
                                            RouteChoice(
                                                "reply",
                                                singBoxOptionLabel(
                                                    stringResource(R.string.routing_reject_reply),
                                                    "reply",
                                                ),
                                            ),
                                        ),
                                        selectedValue = current.rejectMethod,
                                        onSelected = { value ->
                                            draft = current.copy(
                                                rejectMethod = value,
                                                rejectNoDrop =
                                                    current.rejectNoDrop && value != "drop",
                                            )
                                        },
                                    )
                                    AnimatedVisibility(
                                        visible = current.rejectMethod != "drop",
                                        enter = AsteriskMotion.contentEnter(),
                                        exit = AsteriskMotion.contentExit(),
                                    ) {
                                        RouteSwitchCard(
                                            title = stringResource(
                                                R.string.routing_reject_no_drop,
                                            ),
                                            checked = current.rejectNoDrop,
                                            onCheckedChange = { checked ->
                                                draft = current.copy(rejectNoDrop = checked)
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                item(key = "invert") {
                    RouteSwitchCard(
                        title = stringResource(R.string.routing_invert),
                        checked = current.invert,
                        onCheckedChange = { checked -> draft = current.copy(invert = checked) },
                    )
                }
                if (visibleType == SingBoxRouteRuleTypeLogical) {
                    item(key = "logic-title") {
                        RouteEditorSectionTitle(stringResource(R.string.routing_section_logic))
                    }
                    item(key = "logic-mode") {
                        val modes = listOf(
                            SingBoxRouteRuleLogicalModeAnd,
                            SingBoxRouteRuleLogicalModeOr,
                        )
                        SettingsDropdownRow(
                            title = stringResource(R.string.routing_logical_mode),
                            icon = Icons.Rounded.AccountTree,
                            items = listOf(
                                singBoxOptionLabel(
                                    stringResource(R.string.routing_logical_mode_and),
                                    SingBoxRouteRuleLogicalModeAnd,
                                ),
                                singBoxOptionLabel(
                                    stringResource(R.string.routing_logical_mode_or),
                                    SingBoxRouteRuleLogicalModeOr,
                                ),
                            ),
                            selectedIndex = modes.indexOf(current.logicalMode).coerceAtLeast(0),
                            onSelectedIndexChange = { index ->
                                draft = current.copy(logicalMode = modes[index])
                            },
                        )
                    }
                    item(key = "logic-conditions") {
                        RouteLogicalChildrenCard(
                            rules = current.logicalRules,
                            onAdd = {
                                onEditChild(
                                    current,
                                    SingBoxRouteRuleState(
                                        id = current.nextLogicalRouteRuleId(),
                                    ),
                                )
                            },
                            onEdit = { child ->
                                onEditChild(current, child)
                            },
                            onEnabledChange = { child, enabled ->
                                draft = current.copy(
                                    logicalRules = current.logicalRules.map { candidate ->
                                        if (candidate.id == child.id) {
                                            candidate.copy(enabled = enabled)
                                        } else {
                                            candidate
                                        }
                                    },
                                )
                            },
                            onDelete = { child -> pendingChildDelete = child },
                        )
                    }
                } else {
                    item(key = "network-title") {
                    RouteEditorSectionTitle(stringResource(R.string.routing_section_network))
                }
                item(key = "clash-mode") {
                    val modes = listOf("") + SingBoxRouteRuleClashModes
                    SettingsDropdownRow(
                        title = routeRuleMatcherLabel("clash_mode"),
                        icon = Icons.Rounded.Tune,
                        items = listOf(
                            stringResource(R.string.common_not_specified),
                            singBoxOptionLabel(
                                stringResource(R.string.sing_box_mode_rule),
                                "Rule",
                            ),
                            singBoxOptionLabel(
                                stringResource(R.string.sing_box_mode_global),
                                "Global",
                            ),
                            singBoxOptionLabel(
                                stringResource(R.string.sing_box_mode_direct),
                                "Direct",
                            ),
                        ),
                        selectedIndex = modes.indexOf(current.clashMode).coerceAtLeast(0),
                        onSelectedIndexChange = { index ->
                            draft = current.copy(clashMode = modes[index])
                        },
                    )
                }
                item(key = "ip-version") {
                    val versions = listOf(0, 4, 6)
                    SettingsDropdownRow(
                        title = routeRuleMatcherLabel("ip_version"),
                        icon = Icons.Rounded.Language,
                        items = listOf(
                            stringResource(R.string.routing_ip_version_any),
                            singBoxOptionLabel(
                                stringResource(R.string.routing_ipv4),
                                "4",
                            ),
                            singBoxOptionLabel(
                                stringResource(R.string.routing_ipv6),
                                "6",
                            ),
                        ),
                        selectedIndex = versions.indexOf(current.ipVersion).coerceAtLeast(0),
                        onSelectedIndexChange = { index ->
                            draft = current.copy(ipVersion = versions[index])
                        },
                    )
                }
                item(key = "network") {
                    RouteChipGroupCard(
                        title = routeRuleMatcherLabel("network"),
                        choices = listOf(
                            "tcp" to singBoxOptionLabel(
                                stringResource(R.string.routing_network_tcp),
                                "tcp",
                            ),
                            "udp" to singBoxOptionLabel(
                                stringResource(R.string.routing_network_udp),
                                "udp",
                            ),
                            "icmp" to singBoxOptionLabel(
                                stringResource(R.string.routing_network_icmp),
                                "icmp",
                            ),
                        ),
                        selected = current.network.toSet(),
                        onToggle = { value ->
                            draft = current.copy(network = current.network.toggle(value))
                        },
                    )
                }
                item(key = "inbound") {
                    ReferenceSelectionCard(
                        title = routeRuleMatcherLabel("inbound"),
                        emptyText = stringResource(R.string.managed_inbound_empty),
                        choices = inboundChoices,
                        selected = current.inbound.toSet(),
                        onToggle = { value ->
                            draft = current.copy(inbound = current.inbound.toggle(value))
                        },
                    )
                }
                item(key = "protocol") {
                    ReferenceSelectionCard(
                        title = routeRuleMatcherLabel("protocol"),
                        emptyText = stringResource(R.string.common_not_specified),
                        choices = singBoxProtocolChoices(),
                        selected = current.protocol.toSet(),
                        onToggle = { value ->
                            draft = current.copy(protocol = current.protocol.toggle(value))
                        },
                    )
                }
                item(key = "destination-title") {
                    RouteEditorSectionTitle(stringResource(R.string.routing_section_destination))
                }
                item(key = "domain") {
                    RouteStringList(
                        key = current.id,
                        title = routeRuleMatcherLabel("domain"),
                        values = current.domain,
                        onChange = { draft = current.copy(domain = it) },
                    )
                }
                item(key = "domain-suffix") {
                    RouteStringList(
                        key = current.id,
                        title = routeRuleMatcherLabel("domain_suffix"),
                        values = current.domainSuffix,
                        onChange = { draft = current.copy(domainSuffix = it) },
                    )
                }
                item(key = "domain-keyword") {
                    RouteStringList(
                        key = current.id,
                        title = routeRuleMatcherLabel("domain_keyword"),
                        values = current.domainKeyword,
                        onChange = { draft = current.copy(domainKeyword = it) },
                    )
                }
                item(key = "domain-regex") {
                    RouteStringList(
                        key = current.id,
                        title = routeRuleMatcherLabel("domain_regex"),
                        values = current.domainRegex,
                        onChange = { draft = current.copy(domainRegex = it) },
                    )
                }
                item(key = "ip-cidr") {
                    val invalidMessage = stringResource(R.string.routing_cidr_invalid)
                    RouteStringList(
                        key = current.id,
                        title = routeRuleMatcherLabel("ip_cidr"),
                        values = current.ipCidr,
                        onChange = { draft = current.copy(ipCidr = it) },
                        validate = { value -> if (isCidrAddress(value)) null else invalidMessage },
                    )
                }
                item(key = "ip-private") {
                    RouteSwitchCard(
                        title = routeRuleMatcherLabel("ip_is_private"),
                        checked = current.ipIsPrivate,
                        onCheckedChange = { checked -> draft = current.copy(ipIsPrivate = checked) },
                    )
                }
                item(key = "port") {
                    val invalidMessage = stringResource(R.string.routing_port_invalid)
                    RouteStringList(
                        key = current.id,
                        title = routeRuleMatcherLabel("port"),
                        values = current.port,
                        onChange = { draft = current.copy(port = it) },
                        validate = { value -> if (isRoutePort(value)) null else invalidMessage },
                    )
                }
                item(key = "port-range") {
                    val invalidMessage = stringResource(R.string.routing_port_range_invalid)
                    RouteStringList(
                        key = current.id,
                        title = routeRuleMatcherLabel("port_range"),
                        values = current.portRange,
                        onChange = { draft = current.copy(portRange = it) },
                        validate = { value -> if (isRoutePortRange(value)) null else invalidMessage },
                    )
                }
                item(key = "rule-sets") {
                    RouteRuleSetCard(
                        choices = ruleSetChoices,
                        selected = current.ruleSet.toSet(),
                        onToggle = { value ->
                            draft = current.copy(ruleSet = current.ruleSet.toggle(value))
                        },
                    )
                }
                item(key = "source-title") {
                    RouteEditorSectionTitle(stringResource(R.string.routing_section_source))
                }
                item(key = "source-ip-cidr") {
                    val invalidMessage = stringResource(R.string.routing_cidr_invalid)
                    RouteStringList(
                        key = current.id,
                        title = routeRuleMatcherLabel("source_ip_cidr"),
                        values = current.sourceIpCidr,
                        onChange = { draft = current.copy(sourceIpCidr = it) },
                        validate = { value -> if (isCidrAddress(value)) null else invalidMessage },
                    )
                }
                item(key = "source-ip-private") {
                    RouteSwitchCard(
                        title = routeRuleMatcherLabel("source_ip_is_private"),
                        checked = current.sourceIpIsPrivate,
                        onCheckedChange = { checked ->
                            draft = current.copy(sourceIpIsPrivate = checked)
                        },
                    )
                }
                item(key = "source-port") {
                    val invalidMessage = stringResource(R.string.routing_port_invalid)
                    RouteStringList(
                        key = current.id,
                        title = routeRuleMatcherLabel("source_port"),
                        values = current.sourcePort,
                        onChange = { draft = current.copy(sourcePort = it) },
                        validate = { value -> if (isRoutePort(value)) null else invalidMessage },
                    )
                }
                item(key = "source-port-range") {
                    val invalidMessage = stringResource(R.string.routing_port_range_invalid)
                    RouteStringList(
                        key = current.id,
                        title = routeRuleMatcherLabel("source_port_range"),
                        values = current.sourcePortRange,
                        onChange = { draft = current.copy(sourcePortRange = it) },
                        validate = { value -> if (isRoutePortRange(value)) null else invalidMessage },
                    )
                }
                item(key = "android-title") {
                    RouteEditorSectionTitle(stringResource(R.string.routing_section_android))
                }
                item(key = "package-name") {
                    RouteStringList(
                        key = current.id,
                        title = routeRuleMatcherLabel("package_name"),
                        values = current.packageName,
                        onChange = { draft = current.copy(packageName = it) },
                    )
                }
                item(key = "network-type") {
                    RouteChipGroupCard(
                        title = routeRuleMatcherLabel("network_type"),
                        choices = listOf(
                            "wifi" to singBoxOptionLabel(
                                stringResource(R.string.routing_network_type_wifi),
                                "wifi",
                            ),
                            "cellular" to singBoxOptionLabel(
                                stringResource(R.string.routing_network_type_cellular),
                                "cellular",
                            ),
                            "ethernet" to singBoxOptionLabel(
                                stringResource(R.string.routing_network_type_ethernet),
                                "ethernet",
                            ),
                            "other" to singBoxOptionLabel(
                                stringResource(R.string.routing_network_type_other),
                                "other",
                            ),
                        ),
                        selected = current.networkType.toSet(),
                        onToggle = { value ->
                            draft = current.copy(networkType = current.networkType.toggle(value))
                        },
                    )
                }
                item(key = "wifi-ssid") {
                    RouteStringList(
                        key = current.id,
                        title = routeRuleMatcherLabel("wifi_ssid"),
                        values = current.wifiSsid,
                        onChange = { draft = current.copy(wifiSsid = it) },
                    )
                }
                item(key = "wifi-bssid") {
                    RouteStringList(
                        key = current.id,
                        title = routeRuleMatcherLabel("wifi_bssid"),
                        values = current.wifiBssid,
                        onChange = { draft = current.copy(wifiBssid = it) },
                    )
                }
                }
            }
        }
    }
    }

    WarningConfirmDialog(
        show = pendingChildDelete != null,
        title = stringResource(R.string.routing_delete_condition_title),
        summary = stringResource(
            R.string.routing_delete_condition_message,
            pendingChildDelete?.remarks?.takeIf(String::isNotBlank)
                ?: stringResource(R.string.routing_unnamed_condition),
        ),
        dismissText = stringResource(R.string.common_cancel),
        confirmText = stringResource(R.string.common_delete),
        onDismissRequest = { pendingChildDelete = null },
        onConfirm = {
            val childId = pendingChildDelete?.id
            if (childId != null) {
                draft = draft?.copy(
                    logicalRules = draft?.logicalRules.orEmpty().filterNot { child ->
                        child.id == childId
                    },
                )
            }
            pendingChildDelete = null
        },
    )
}

@Composable
private fun RouteLogicalChildrenCard(
    rules: List<SingBoxRouteRuleState>,
    onAdd: () -> Unit,
    onEdit: (SingBoxRouteRuleState) -> Unit,
    onEnabledChange: (SingBoxRouteRuleState, Boolean) -> Unit,
    onDelete: (SingBoxRouteRuleState) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f).padding(horizontal = 4.dp)) {
                    Text(
                        text = stringResource(R.string.routing_conditions),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        text = pluralStringResource(
                            R.plurals.routing_condition_count,
                            rules.size,
                            rules.size,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onAdd) {
                    Icon(
                        imageVector = Icons.Rounded.Add,
                        contentDescription = stringResource(R.string.routing_add_condition),
                    )
                }
            }

            val listMotion = AsteriskMotion.effects<Float>()
            AnimatedContent(
                targetState = rules,
                transitionSpec = AsteriskMotion.fadeThrough(listMotion),
                label = "routing-logical-conditions",
            ) { visibleRules ->
                if (visibleRules.isEmpty()) {
                    Text(
                        text = stringResource(R.string.routing_conditions_empty),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 12.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        visibleRules.forEachIndexed { index, child ->
                            RouteLogicalChildCard(
                                rule = child,
                                fallbackName = stringResource(
                                    R.string.routing_condition_number,
                                    index + 1,
                                ),
                                onEdit = { onEdit(child) },
                                onEnabledChange = { enabled ->
                                    onEnabledChange(child, enabled)
                                },
                                onDelete = { onDelete(child) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RouteLogicalChildCard(
    rule: SingBoxRouteRuleState,
    fallbackName: String,
    onEdit: () -> Unit,
    onEnabledChange: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    AsteriskExpressiveCard(
        onClick = onEdit,
        modifier = Modifier.fillMaxWidth().alpha(if (rule.enabled) 1f else 0.68f),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (rule.type == SingBoxRouteRuleTypeLogical) {
                    Icons.Rounded.AccountTree
                } else {
                    Icons.Rounded.FilterAlt
                },
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Column(
                modifier = Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    text = rule.remarks.takeIf(String::isNotBlank) ?: fallbackName,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = routeRuleMatchSummary(rule),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Switch(
                checked = rule.enabled,
                onCheckedChange = onEnabledChange,
            )
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
                    HorizontalDivider()
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
private fun RouteEditorSectionTitle(text: String) {
    Text(
        text = text,
        modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 10.dp),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
    )
}

@Composable
private fun RouteSwitchCard(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    AsteriskExpressiveCard(
        onClick = { onCheckedChange(!checked) },
        modifier = Modifier.fillMaxWidth(),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleSmall,
            )
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
    }
}

@Composable
private fun <T> RouteChipGroupCard(
    title: String,
    choices: List<Pair<T, String>>,
    selected: Set<T>,
    onToggle: (T) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                choices.forEach { (value, label) ->
                    AsteriskFilterChip(
                        selected = value in selected,
                        onClick = { onToggle(value) },
                        label = label,
                    )
                }
            }
        }
    }
}

@Composable
private fun RouteRuleSetCard(
    choices: List<Pair<String, String>>,
    selected: Set<String>,
    onToggle: (String) -> Unit,
) {
    ReferenceSelectionCard(
        title = routeRuleMatcherLabel("rule_set"),
        emptyText = stringResource(R.string.routing_rule_sets_empty),
        choices = choices,
        selected = selected,
        onToggle = onToggle,
    )
}

@Composable
private fun RouteStringList(
    key: Int,
    title: String,
    values: List<String>,
    onChange: (List<String>) -> Unit,
    validate: (String) -> String? = { null },
) {
    StringListEditor(
        editorKey = key,
        title = title,
        values = values,
        onValuesChange = onChange,
        emptyText = stringResource(R.string.routing_list_empty),
        validateInput = validate,
        horizontalPadding = 0.dp,
    )
}

@Composable
private fun routeRuleMatcherLabel(field: String): String {
    val labelResource = RouteRuleMatcherLabelResources[field] ?: R.string.common_unknown
    return singBoxOptionLabel(stringResource(labelResource), field)
}

@Composable
private fun routeRuleMatchChipLabel(
    match: RouteRuleCardMatch,
    referenceLabels: Map<String, String>,
    unavailableLabel: String,
): String {
    if (match.field == "all") {
        return stringResource(R.string.routing_match_all)
    }
    if (match.field == "logical") {
        val modeLabel = stringResource(
            if (match.values.firstOrNull() == SingBoxRouteRuleLogicalModeOr) {
                R.string.routing_logical_mode_or_short
            } else {
                R.string.routing_logical_mode_and_short
            },
        )
        val count = match.values.getOrNull(1)?.toIntOrNull() ?: 0
        val conditionCount = pluralStringResource(
            R.plurals.routing_condition_count,
            count,
            count,
        )
        return stringResource(R.string.routing_logical_summary, modeLabel, conditionCount)
    }

    val label = match.officialFieldName?.let { field ->
        routeRuleMatcherLabel(field)
    } ?: stringResource(R.string.common_unknown)
    val values = match.values.take(2).map { value ->
        routeRuleMatchValueLabel(
            field = match.field,
            value = value,
            referenceLabels = referenceLabels,
            unavailableLabel = unavailableLabel,
        )
    }
    return if (values.isEmpty()) {
        label
    } else {
        stringResource(R.string.routing_match_summary, label, values.joinToString(", "))
    }
}

@Composable
private fun routeRuleMatchValueLabel(
    field: String,
    value: String,
    referenceLabels: Map<String, String>,
    unavailableLabel: String,
): String = when (field) {
    "inbound",
    "rule_set",
    -> app.visibleManagedReference(value, referenceLabels, unavailableLabel)
    "protocol" -> singBoxProtocolChoices().toMap()[value] ?: value
    "clash_mode" -> singBoxOptionLabel(
        stringResource(
            when (value) {
                "Rule" -> R.string.sing_box_mode_rule
                "Global" -> R.string.sing_box_mode_global
                else -> R.string.sing_box_mode_direct
            },
        ),
        value,
    )
    "ip_version" -> singBoxOptionLabel(
        stringResource(
            if (value == "4") R.string.routing_ipv4 else R.string.routing_ipv6,
        ),
        value,
    )
    "network" -> singBoxOptionLabel(
        stringResource(
            when (value) {
                "tcp" -> R.string.routing_network_tcp
                "udp" -> R.string.routing_network_udp
                else -> R.string.routing_network_icmp
            },
        ),
        value,
    )
    "network_type" -> singBoxOptionLabel(
        stringResource(
            when (value) {
                "wifi" -> R.string.routing_network_type_wifi
                "cellular" -> R.string.routing_network_type_cellular
                "ethernet" -> R.string.routing_network_type_ethernet
                else -> R.string.routing_network_type_other
            },
        ),
        value,
    )
    else -> value
}

@Composable
private fun routeRuleMatchSummary(rule: SingBoxRouteRuleState): String {
    if (rule.type == SingBoxRouteRuleTypeLogical) {
        val modeLabel = stringResource(
            if (rule.logicalMode == SingBoxRouteRuleLogicalModeOr) {
                R.string.routing_logical_mode_or_short
            } else {
                R.string.routing_logical_mode_and_short
            },
        )
        val conditionCount = pluralStringResource(
            R.plurals.routing_condition_count,
            rule.logicalRules.size,
            rule.logicalRules.size,
        )
        return stringResource(R.string.routing_logical_summary, modeLabel, conditionCount)
    }
    val count = rule.matcherCount()
    return if (count == 0) {
        stringResource(R.string.routing_match_all)
    } else {
        pluralStringResource(R.plurals.routing_match_count, count, count)
    }
}

private fun SingBoxRouteRuleState.matcherCount(): Int =
    listOf(
        inbound,
        network,
        protocol,
        domain,
        domainSuffix,
        domainKeyword,
        domainRegex,
        sourceIpCidr,
        ipCidr,
        sourcePort,
        sourcePortRange,
        port,
        portRange,
        packageName,
        networkType,
        wifiSsid,
        wifiBssid,
        ruleSet,
    ).count(List<String>::isNotEmpty) +
        (if (clashMode.isNotEmpty()) 1 else 0) +
        (if (ipVersion != 0) 1 else 0) +
        (if (sourceIpIsPrivate) 1 else 0) +
        (if (ipIsPrivate) 1 else 0)

private fun <T> List<T>.toggle(value: T): List<T> =
    if (value in this) filterNot { item -> item == value } else this + value
