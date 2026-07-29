// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package features.dns

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import app.AppState
import app.LocalAppServices
import app.LocalAppStateStore
import app.LocalIsWideScreen
import app.LocalNavigator
import app.LocalUpdateAppState
import app.SingBoxDnsRuleState
import app.collectAppState
import app.managedInboundTags
import app.managedReferenceRemarks
import app.managedRuleSetChoices
import app.nextAvailableDnsRuleId
import app.selectableDetourOutbounds
import app.selectableDnsEndpoints
import app.selectablePreferredByDnsServers
import app.withPrunedDnsEvaluationReferences
import engine.singbox.config.sanitized
import engine.singbox.config.validateSingBoxRuntimeConfiguration
import features.logs.FailureLogContext
import features.logs.reportFailure
import features.resources.runtime.singBoxRuleSetFiles
import features.settings.SettingsActionRow
import features.settings.SettingsSectionCard
import features.settings.sheets.DnsRuleEditorSheet
import features.settings.sheets.DnsRuleEditorState
import features.settings.sheets.DnsSettingsBottomSheet
import features.settings.sheets.dnsRuleActionLabel
import features.settings.sheets.dnsRuleSummary
import features.settings.sheets.dnsServerTypeLabel
import features.settings.toDnsSettingsDraft
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.asterisk.zcc.abox.R
import sh.calvin.reorderable.ReorderableItem
import ui.components.AsteriskInfoChip
import ui.components.WarningConfirmDialog
import ui.components.draggedCardShadow
import ui.components.longPressReorderDragHandle
import ui.components.managedInboundChoices
import ui.components.rememberAsteriskReorderableLazyGridState
import ui.layout.pageContentPaddingWithCutout
import ui.layout.pageListPadding
import ui.theme.AsteriskMotion
import ui.icons.AsteriskIcons as Icons

@Composable
internal fun DnsManagementPage(
    padding: PaddingValues,
    initiallyOpenDnsSettings: Boolean = false,
) {
    val appState by LocalAppStateStore.current.collectAppState()
    val updateAppState = LocalUpdateAppState.current
    val navigator = LocalNavigator.current
    val context = LocalContext.current
    val tipNotifier = LocalAppServices.current.tipNotifier
    val scope = rememberCoroutineScope()
    val isWideScreen = LocalIsWideScreen.current
    var editor by remember {
        mutableStateOf(
            DnsRuleEditorState(
                index = null,
                rule = SingBoxDnsRuleState(),
            ),
        )
    }
    var showEditor by remember { mutableStateOf(false) }
    var showDnsSettings by remember { mutableStateOf(initiallyOpenDnsSettings) }
    var dnsSettingsDraft by remember { mutableStateOf(appState.toDnsSettingsDraft()) }
    var pendingDelete by remember { mutableStateOf<SingBoxDnsRuleState?>(null) }
    var savingRule by remember { mutableStateOf(false) }
    var savingDnsSettings by remember { mutableStateOf(false) }
    val saveFailedMessage = stringResource(R.string.dns_rule_save_failed)
    val enableFailedMessage = stringResource(R.string.dns_rule_enable_failed)
    val validationFailedMessage = stringResource(R.string.settings_sing_box_validation_failed)
    val serverChoices = appState.dnsServers.map { server ->
        server.tag to server.remarks.ifBlank { dnsServerTypeLabel(server.type) }
    }
    val serverTags = serverChoices.map { choice -> choice.first }
    val ruleSetChoices = remember(appState.customResourceFiles) {
        appState.managedRuleSetChoices(
            context.singBoxRuleSetFiles(appState.customResourceFiles).map { file -> file.name },
        ).map { choice -> choice.tag to choice.remarks }
    }
    val inboundChoices = managedInboundChoices(managedInboundTags(appState))
    val preferredByChoices = selectablePreferredByDnsServers(appState)
        .map { choice ->
            choice.tag to choice.remarks.ifBlank {
                appState.dnsServers
                    .firstOrNull { server -> server.tag == choice.tag }
                    ?.let { server -> dnsServerTypeLabel(server.type) }
                    .orEmpty()
            }
        }
    val matchResponseChoices = selectableDnsMatchResponseValues(
        rules = appState.dnsRules,
        currentIndex = editor.index,
    ).mapIndexed { index, choice ->
        choice to choice.remarks.ifBlank {
            stringResource(R.string.dns_rule_evaluation_fallback, index + 1)
        }
    }
    val unavailableLabel = stringResource(R.string.common_unavailable)
    val visibleReferenceLabels = buildMap {
        putAll(appState.managedReferenceRemarks())
        putAll(serverChoices)
        putAll(ruleSetChoices)
        putAll(inboundChoices)
        putAll(preferredByChoices)
        matchResponseChoices.forEach { (choice, label) ->
            put(choice.value, label)
        }
    }

    fun validateAndCommitChange(
        baseState: AppState,
        candidateState: AppState,
        operation: String,
        failureMessage: String,
        onCommitted: () -> Unit = {},
    ) {
        if (savingRule) return
        savingRule = true
        scope.launch {
            try {
                val committed = validateAndCommitDnsRuleState(
                    baseState = baseState,
                    candidateState = candidateState,
                    validate = { candidate ->
                        withContext(Dispatchers.IO) {
                            validateSingBoxRuntimeConfiguration(context, candidate)
                        }
                    },
                    commit = { expected, next ->
                        var didCommit = false
                        updateAppState { current ->
                            if (current === expected) {
                                didCommit = true
                                next
                            } else {
                                current
                            }
                        }
                        didCommit
                    },
                )
                if (committed) {
                    onCommitted()
                } else {
                    tipNotifier.show(failureMessage)
                }
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                reportFailure(
                    context = FailureLogContext(
                        operation = operation,
                        stage = "validate",
                    ),
                    error = error,
                )
                tipNotifier.show(failureMessage)
            } finally {
                savingRule = false
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.dns_management_title))
                        val countMotion = AsteriskMotion.fastEffects<Float>()
                        AnimatedContent(
                            targetState = appState.dnsRules.size,
                            transitionSpec = {
                                fadeIn(animationSpec = countMotion)
                                    .togetherWith(fadeOut(animationSpec = countMotion))
                            },
                            label = "dns-rule-count",
                        ) { count ->
                            Text(
                                text = pluralStringResource(R.plurals.dns_rule_count, count, count),
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
                        enabled = !savingRule,
                        onClick = {
                            editor = DnsRuleEditorState(
                                index = null,
                                rule = SingBoxDnsRuleState(
                                    id = appState.nextAvailableDnsRuleId(),
                                    server = appState.dnsFinal
                                        .takeIf { final -> final in serverTags }
                                        ?: serverTags.firstOrNull().orEmpty(),
                                ),
                            )
                            showEditor = true
                        },
                    ) {
                        Icon(Icons.Rounded.Add, stringResource(R.string.settings_dns_add_rule))
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
        DnsRuleGrid(
            rules = appState.dnsRules,
            referenceLabels = visibleReferenceLabels,
            unavailableLabel = unavailableLabel,
            columns = if (isWideScreen) 2 else 1,
            contentPadding = pageListPadding(contentPadding, bottomExtra = 24.dp),
            interactionsEnabled = !savingRule,
            onOpenDnsSettings = {
                dnsSettingsDraft = appState.toDnsSettingsDraft()
                showDnsSettings = true
            },
            onMove = { fromIndex, toIndex ->
                updateAppState { state ->
                    state.copy(dnsRules = state.dnsRules.moveDnsRule(fromIndex, toIndex))
                        .withPrunedDnsEvaluationReferences()
                }
            },
            onEnabledChange = { rule, enabled ->
                if (enabled) {
                    val baseState = appState
                    validateAndCommitChange(
                        baseState = baseState,
                        candidateState = baseState
                            .withDnsRuleEnabled(rule.id, enabled = true)
                            .withPrunedDnsEvaluationReferences(),
                        operation = "enable_dns_rule",
                        failureMessage = enableFailedMessage,
                    )
                } else {
                    updateAppState { state ->
                        state.withDnsRuleEnabled(rule.id, enabled = false)
                            .withPrunedDnsEvaluationReferences()
                    }
                }
            },
            onEdit = { rule ->
                editor = DnsRuleEditorState(
                    index = appState.dnsRules.indexOfFirst { current -> current.id == rule.id },
                    rule = rule,
                )
                showEditor = true
            },
            onDelete = { pendingDelete = it },
        )
    }

    DnsSettingsBottomSheet(
        show = showDnsSettings,
        saving = savingDnsSettings,
        draft = dnsSettingsDraft,
        outboundProxyChoices = selectableDetourOutbounds(
            state = appState,
            excludedTag = "",
            includeGlobalSelector = true,
        ),
        endpointChoicesByServerType = mapOf(
            "tailscale" to selectableDnsEndpoints(appState, "tailscale"),
            "openconnect" to selectableDnsEndpoints(appState, "openconnect"),
            "openvpn" to selectableDnsEndpoints(appState, "openvpn"),
        ),
        onDraftChange = { dnsSettingsDraft = it },
        onDismissRequest = {
            if (!savingDnsSettings) {
                showDnsSettings = false
            }
        },
        onSave = { savedDraft ->
            if (!savingDnsSettings) {
                val baseState = appState
                val candidateState = baseState.withDnsSettings(savedDraft)
                savingDnsSettings = true
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
                            showDnsSettings = false
                        } else {
                            tipNotifier.show(validationFailedMessage)
                        }
                    } catch (error: Throwable) {
                        if (error is CancellationException) throw error
                        reportFailure(
                            context = FailureLogContext(
                                operation = "save_dns_settings",
                                stage = "validate",
                            ),
                            error = error,
                        )
                        tipNotifier.show(validationFailedMessage)
                    } finally {
                        savingDnsSettings = false
                    }
                }
            }
        },
    )

    DnsRuleEditorSheet(
        show = showEditor,
        editor = editor,
        serverChoices = serverChoices,
        inboundChoices = inboundChoices,
        preferredByChoices = preferredByChoices,
        matchResponseChoices = matchResponseChoices,
        ruleSetChoices = ruleSetChoices,
        saving = savingRule,
        onEditorChange = { rule -> editor = editor.copy(rule = rule) },
        onDismissRequest = { showEditor = false },
        onSave = { saved ->
            if (!savingRule) {
                val baseState = appState
                val normalized = saved.sanitized()
                val exists = baseState.dnsRules.any { rule -> rule.id == normalized.id }
                val candidateState = baseState.copy(
                    dnsRules = if (exists) {
                        baseState.dnsRules.map { current ->
                            if (current.id == normalized.id) normalized else current
                        }
                    } else {
                        baseState.dnsRules + normalized
                    },
                    nextDnsRuleId = maxOf(baseState.nextDnsRuleId, normalized.id + 1),
                ).withPrunedDnsEvaluationReferences()
                validateAndCommitChange(
                    baseState = baseState,
                    candidateState = candidateState,
                    operation = "save_dns_rule",
                    failureMessage = saveFailedMessage,
                    onCommitted = { showEditor = false },
                )
            }
        },
    )

    WarningConfirmDialog(
        show = pendingDelete != null,
        title = stringResource(R.string.dns_rule_delete_title),
        summary = stringResource(
            R.string.dns_rule_delete_message,
            pendingDelete?.let { rule ->
                rule.remarks.ifBlank { dnsRuleActionLabel(rule.action) }
            }.orEmpty(),
        ),
        dismissText = stringResource(R.string.common_cancel),
        confirmText = stringResource(R.string.common_delete),
        onDismissRequest = { pendingDelete = null },
        onConfirm = {
            val ruleId = pendingDelete?.id
            if (ruleId != null) {
                updateAppState { state ->
                    state.copy(dnsRules = state.dnsRules.filterNot { rule -> rule.id == ruleId })
                        .withPrunedDnsEvaluationReferences()
                }
            }
            pendingDelete = null
        },
    )
}

@Composable
private fun DnsRuleGrid(
    rules: List<SingBoxDnsRuleState>,
    referenceLabels: Map<String, String>,
    unavailableLabel: String,
    columns: Int,
    contentPadding: PaddingValues,
    interactionsEnabled: Boolean,
    onOpenDnsSettings: () -> Unit,
    onMove: (Int, Int) -> Unit,
    onEnabledChange: (SingBoxDnsRuleState, Boolean) -> Unit,
    onEdit: (SingBoxDnsRuleState) -> Unit,
    onDelete: (SingBoxDnsRuleState) -> Unit,
) {
    val gridState = rememberLazyGridState()
    val layout = dnsManagementGridLayout(rules.size)
    val reorderableState = rememberAsteriskReorderableLazyGridState(
        lazyGridState = gridState,
        itemCount = rules.size,
        indexOffset = layout.ruleIndexOffset,
        scrollThresholdPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding()),
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
        layout.sections.forEach { section ->
            when (section) {
                DnsManagementGridSection.Settings -> {
                    item(key = "dns-settings", span = { GridItemSpan(maxLineSpan) }) {
                        SettingsSectionCard(bottomPadding = 0.dp) {
                            SettingsActionRow(
                                title = stringResource(R.string.settings_dns),
                                summary = stringResource(R.string.dns_settings_entry_summary),
                                icon = Icons.Rounded.Tune,
                                onClick = onOpenDnsSettings,
                            )
                        }
                    }
                }
                DnsManagementGridSection.EmptyState -> {
                    item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                        DnsRuleEmptyState()
                    }
                }
                DnsManagementGridSection.Rules -> {
                    items(
                        items = rules,
                        key = SingBoxDnsRuleState::id,
                        contentType = { "dns-rule" },
                    ) { rule ->
                        ReorderableItem(
                            state = reorderableState.reorderableState,
                            key = rule.id,
                            modifier = Modifier.fillMaxWidth(),
                            animateItemModifier = Modifier.animateItem(),
                        ) { isDragging ->
                            DnsRuleCard(
                                rule = rule,
                                referenceLabels = referenceLabels,
                                unavailableLabel = unavailableLabel,
                                isDragging = isDragging,
                                interactionsEnabled = interactionsEnabled,
                                onEnabledChange = { enabled -> onEnabledChange(rule, enabled) },
                                onEdit = { onEdit(rule) },
                                onDelete = { onDelete(rule) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .longPressReorderDragHandle(
                                        scope = this,
                                        enabled = interactionsEnabled && rules.size > 1,
                                        state = reorderableState,
                                    ),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DnsRuleEmptyState() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 240.dp)
            .padding(horizontal = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Rounded.Policy,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(48.dp),
        )
        Text(
            text = stringResource(R.string.dns_rules_empty),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 16.dp),
        )
        Text(
            text = stringResource(R.string.dns_rules_empty_summary),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

@Composable
private fun DnsRuleCard(
    rule: SingBoxDnsRuleState,
    referenceLabels: Map<String, String>,
    unavailableLabel: String,
    isDragging: Boolean,
    interactionsEnabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val presentation = rule
        .withVisibleManagedReferences(
            labels = referenceLabels,
            unavailableLabel = unavailableLabel,
        )
        .toDnsRuleCardPresentation()
    val actionLabel = dnsRuleActionLabel(presentation.action)
    val actionSummary = presentation.target?.let { target ->
        stringResource(R.string.settings_dns_summary_pair, actionLabel, target)
    } ?: actionLabel
    val containerColor by animateColorAsState(
        targetValue = if (isDragging) {
            MaterialTheme.colorScheme.surfaceContainerHigh
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        },
        animationSpec = AsteriskMotion.effects(),
        label = "dns-rule-card-color",
    )
    val scale by animateFloatAsState(
        targetValue = if (isDragging) 1.025f else 1f,
        animationSpec = AsteriskMotion.fastSpatial(),
        label = "dns-rule-card-scale",
    )
    val shadowAlpha by animateFloatAsState(
        targetValue = if (isDragging) 1f else 0f,
        animationSpec = AsteriskMotion.fastEffects(),
        label = "dns-rule-card-shadow",
    )
    Card(
        onClick = onEdit,
        enabled = interactionsEnabled,
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
            )
            .alpha(if (rule.enabled) 1f else 0.68f),
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
                    text = rule.remarks.ifBlank {
                        stringResource(R.string.dns_rule_unnamed)
                    },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = actionSummary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    presentation.matchRules.forEach { matchRule ->
                        AsteriskInfoChip(
                            text = dnsRuleSummary(matchRule),
                            emphasized = rule.invert,
                        )
                    }
                }
            }
            Switch(
                checked = rule.enabled,
                onCheckedChange = onEnabledChange,
                enabled = interactionsEnabled,
            )
            Box {
                IconButton(
                    enabled = interactionsEnabled,
                    onClick = { menuExpanded = true },
                ) {
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
