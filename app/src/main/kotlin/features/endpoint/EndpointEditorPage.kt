// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.endpoint

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.LocalAppServices
import app.LocalAppStateStore
import app.LocalIsWideScreen
import app.LocalNavigator
import app.LocalUpdateAppState
import app.SupportedSingBoxEndpointTypes
import app.collectAppState
import app.selectableDetourOutbounds
import engine.singbox.config.validateSingBoxRuntimeConfiguration
import features.logs.FailureLogContext
import features.logs.reportFailure
import features.outbound.EditorSectionCard
import features.settings.SettingsDropdownRow
import features.dns.dnsServerTypeLabel
import features.singbox.JsonCodeEditor
import features.singbox.SingBoxCodeEditorState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import app.R
import ui.clipboard.setPlainText
import ui.components.AsteriskCodeActionButton
import ui.components.AsteriskCodeSearchHost
import ui.components.EditorPagePane
import ui.components.EditorPageScaffold
import ui.components.localizedLabel
import ui.components.rememberAsteriskCodeSearchState
import ui.layout.CodeEditorFocusLayout
import ui.layout.EditorListItem
import ui.layout.codeEditorFocusFade
import ui.layout.rememberCodeEditorFocusState
import ui.theme.AsteriskShapeTokens
import ui.icons.AsteriskIcons as Icons

@Composable
internal fun EndpointEditorPage(
    padding: PaddingValues,
    endpointId: Int,
    initialType: String,
    draftRemarks: String,
) {
    val appState by LocalAppStateStore.current.collectAppState()
    val updateAppState = LocalUpdateAppState.current
    val navigator = LocalNavigator.current
    val services = LocalAppServices.current
    val clipboard = LocalClipboard.current
    val context = LocalContext.current
    val isWideScreen = LocalIsWideScreen.current
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    var activeEndpointId by rememberSaveable(endpointId) { mutableIntStateOf(endpointId) }
    val editing = appState.endpoints.firstOrNull { endpoint -> endpoint.id == activeEndpointId }
    val type = editing?.type ?: initialType.takeIf { it in SupportedSingBoxEndpointTypes }
        ?: SupportedSingBoxEndpointTypes.first()
    val initialRemarks = editing?.remarks ?: draftRemarks
    var remarks by remember(activeEndpointId, editing?.remarks, initialRemarks) {
        mutableStateOf(initialRemarks)
    }
    val initialDetour = editing?.json?.let { json -> endpointManagedReference(json, "detour") }.orEmpty()
    val initialResolver = editing?.json?.let { json -> endpointManagedReference(json, "domain_resolver") }.orEmpty()
    var detour by remember(activeEndpointId, editing?.json) { mutableStateOf(initialDetour) }
    var domainResolver by remember(activeEndpointId, editing?.json) { mutableStateOf(initialResolver) }
    val detourChoices = selectableDetourOutbounds(
        state = appState,
        excludedTag = editing?.tag.orEmpty(),
        includeGlobalSelector = true,
    )
    val detourValues = listOf("") + detourChoices.map { choice -> choice.tag }
    val detourLabels = listOf(stringResource(R.string.common_not_specified)) +
        detourChoices.map { choice -> choice.localizedLabel() }
    val dnsResolverValues = listOf("") + appState.dnsServers.map { server -> server.tag }
    val dnsResolverLabels = listOf(stringResource(R.string.common_not_specified)) +
        appState.dnsServers.map { server ->
            server.remarks.ifBlank { dnsServerTypeLabel(server.type) }
        }
    val initialJson = remember(editing?.json, type) {
        editing?.json?.let(::endpointJsonForEditing) ?: newEndpointJson(type)
    }
    val editorState = remember(activeEndpointId, initialJson) { SingBoxCodeEditorState(initialJson) }
    val currentJson = remember(editorState, editorState.documentVersion) { editorState.snapshotText() }
    var saving by remember(activeEndpointId) { mutableStateOf(false) }
    val missing = activeEndpointId != 0 && editing == null
    val invalidMessage = stringResource(R.string.endpoint_editor_invalid)
    val invalidJsonMessage = stringResource(R.string.endpoint_editor_json_invalid)
    val formatJsonContentDescription = stringResource(R.string.endpoint_editor_format_json)
    val copiedMessage = stringResource(R.string.common_copied)
    val codeSearch = rememberAsteriskCodeSearchState()
    val editorFocus = rememberCodeEditorFocusState(
        editorFocused = editorState.isFocused,
        clearEditorFocus = editorState::clearFocus,
        searchActive = codeSearch.active,
        backEnabled = !saving,
    )
    val showProperties = !editorFocus.expanded

    fun formatCurrentJson() {
        runCatching {
            formatEndpointJson(editorState.snapshotText())
        }.onSuccess { formatted ->
            editorState.replaceText(formatted)
        }.onFailure {
            scope.launch { services.tipNotifier.show(invalidJsonMessage) }
        }
    }

    fun save() {
        if (saving) return
        val imported = runCatching {
            validateEndpointDraft(
                type = type,
                remarks = remarks,
                json = endpointJsonWithManagedReferences(
                    json = editorState.snapshotText(),
                    detour = detour,
                    domainResolver = domainResolver,
                ),
            )
        }.getOrElse {
            scope.launch { services.tipNotifier.show(invalidMessage) }
            return
        }
        saving = true
        scope.launch {
            try {
                val candidateState = if (editing == null) {
                    appState.withImportedEndpoints(listOf(imported))
                } else {
                    appState.copy(
                        endpoints = appState.endpoints.map { endpoint ->
                            if (endpoint.id == editing.id) {
                                endpoint.copy(
                                    remarks = imported.remarks,
                                    type = imported.type,
                                    json = endpointJsonForStorage(
                                        endpointId = endpoint.id,
                                        type = imported.type,
                                        remarks = imported.remarks,
                                        json = imported.json,
                                        detour = detour,
                                        domainResolver = domainResolver,
                                    ),
                                )
                            } else {
                                endpoint
                            }
                        },
                    )
                }
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
                    navigator.pop()
                } else {
                    reportFailure(
                        FailureLogContext(
                            operation = "endpoint_save",
                            stage = "commit",
                        ),
                    )
                    services.tipNotifier.show(invalidMessage)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                services.tipNotifier.showError(
                    error,
                    invalidMessage,
                    FailureLogContext(operation = "endpoint_save"),
                )
            } finally {
                saving = false
            }
        }
    }

    AsteriskCodeSearchHost(
        state = codeSearch,
        modifier = Modifier.fillMaxSize(),
        enabled = !missing && !saving,
        editorTools = { searchButton ->
            searchButton()
            AsteriskCodeActionButton(
                icon = Icons.Rounded.AutoFixHigh,
                label = formatJsonContentDescription,
                onClick = ::formatCurrentJson,
            )
        },
    ) { _, editorModifier, backdrop ->
        EditorPageScaffold(
            outerPadding = padding,
            isWideScreen = isWideScreen,
            title = {
                Column {
                    Text(
                        stringResource(
                            if (editing == null) {
                                R.string.endpoint_editor_add
                            } else {
                                R.string.endpoint_editor_edit
                            },
                        ),
                    )
                    Text(
                        endpointTypeTitle(type),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            saving = saving,
            saveEnabled = !missing,
            onBack = {
                when {
                    codeSearch.active -> codeSearch.close()
                    editorFocus.expanded -> editorFocus.exit()
                    else -> navigator.pop()
                }
            },
            onSave = ::save,
            backdrop = backdrop,
            editorPane = EditorPagePane(
                title = stringResource(R.string.endpoint_management),
                entries = appState.endpoints.map { endpoint ->
                    EditorListItem(endpoint.id, endpoint.remarks.ifBlank { endpointTypeTitle(endpoint.type) })
                },
                selectedId = activeEndpointId,
                selectionEnabled = !missing && remarks == initialRemarks && detour == initialDetour &&
                    domainResolver == initialResolver && currentJson == initialJson,
                onSelect = { selectedId ->
                    if (selectedId != activeEndpointId) {
                        focusManager.clearFocus(force = true)
                        activeEndpointId = selectedId
                    }
                },
                show = showProperties,
                animateFocusLayout = true,
            ),
            actions = {
                AsteriskCodeActionButton(
                    icon = Icons.Rounded.ContentCopy,
                    label = stringResource(R.string.common_copy),
                    onClick = {
                        scope.launch {
                            clipboard.setPlainText(editorState.snapshotText())
                            services.tipNotifier.show(copiedMessage)
                        }
                    },
                    enabled = !missing,
                )
            },
        ) { contentPadding ->
            if (missing) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(contentPadding),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        Icons.Rounded.ErrorOutline,
                        contentDescription = null,
                        modifier = Modifier.size(40.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.endpoint_editor_missing),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(contentPadding)
                        .consumeWindowInsets(contentPadding),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    val focusProgress by animateFloatAsState(
                        targetValue = if (editorFocus.expanded) 1f else 0f,
                        animationSpec = codeEditorFocusFade(),
                        label = "endpoint-editor-width",
                    )
                    val normalWidth = minOf(maxWidth, 800.dp)
                    val editorWidth = normalWidth + (maxWidth - normalWidth) * focusProgress
                    CodeEditorFocusLayout(
                        expanded = editorFocus.expanded,
                        modifier = Modifier.width(editorWidth).fillMaxHeight(),
                        supportingContent = {
                            EditorSectionCard(
                                title = stringResource(R.string.endpoint_editor_type),
                                description = endpointTypeSummary(type),
                            ) {
                                Surface(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 6.dp),
                                    shape = AsteriskShapeTokens.InnerContainer,
                                    color = MaterialTheme.colorScheme.secondaryContainer,
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Icon(
                                            endpointTypeIcon(type),
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                        )
                                        Spacer(Modifier.width(12.dp))
                                        Text(
                                            endpointTypeTitle(type),
                                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                                        )
                                    }
                                }
                                OutlinedTextField(
                                    value = remarks,
                                    onValueChange = { remarks = it },
                                    enabled = !saving,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 6.dp),
                                    label = { Text(stringResource(R.string.endpoint_editor_remarks)) },
                                    singleLine = true,
                                    shape = AsteriskShapeTokens.InnerContainer,
                                )
                                SettingsDropdownRow(
                                    title = stringResource(R.string.endpoint_editor_detour),
                                    icon = Icons.AutoMirrored.Rounded.AltRoute,
                                    items = detourLabels,
                                    selectedIndex = detourValues.indexOf(detour).coerceAtLeast(0),
                                    onSelectedIndexChange = { index -> detour = detourValues[index] },
                                )
                                SettingsDropdownRow(
                                    title = stringResource(R.string.endpoint_editor_domain_resolver),
                                    icon = Icons.Rounded.Dns,
                                    items = dnsResolverLabels,
                                    selectedIndex = dnsResolverValues.indexOf(domainResolver).coerceAtLeast(0),
                                    onSelectedIndexChange = { index ->
                                        domainResolver = dnsResolverValues[index]
                                    },
                                )
                            }
                            Spacer(Modifier.height(14.dp))
                            Text(
                                stringResource(R.string.endpoint_editor_json),
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                stringResource(R.string.endpoint_editor_json_summary),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
                            )
                        },
                    ) { modifier ->
                        JsonCodeEditor(
                            state = editorState,
                            readOnly = saving,
                            modifier = modifier.then(editorModifier),
                            searchState = codeSearch,
                        )
                    }
                }
            }
        }
    }
}
