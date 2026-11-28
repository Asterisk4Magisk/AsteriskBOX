// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.resources

import ui.layout.CodeEditorFocusLayout
import ui.layout.rememberCodeEditorFocusState
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.LocalAppServices
import app.LocalAppStateStore
import app.LocalIsWideScreen
import app.LocalNavigator
import app.collectAppState
import features.logs.FailureLogContext
import features.singbox.HostsCodeEditor
import features.singbox.JsonCodeEditor
import features.singbox.SingBoxCodeEditorState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import app.R
import ui.components.AsteriskCodeActionButton
import ui.components.AsteriskCodeSearchHost
import ui.components.EditorPageScaffold
import ui.components.rememberAsteriskCodeSearchState
import ui.theme.AsteriskMotion
import ui.theme.AsteriskShapeTokens
import ui.icons.AsteriskIcons as Icons

@Composable
internal fun ResourceTextEditorPage(
    padding: PaddingValues,
    resourceId: Int,
) {
    val appState by LocalAppStateStore.current.collectAppState()
    val navigator = LocalNavigator.current
    val services = LocalAppServices.current
    val isWideScreen = LocalIsWideScreen.current
    val scope = rememberCoroutineScope()
    val file = appState.customResourceFiles
        .firstOrNull { candidate -> candidate.id == resourceId }
        ?.takeIf { candidate -> candidate.name.isEditableResource() }
    val isHosts = file?.name?.isHostsResource() == true
    val editorState = remember(resourceId) { SingBoxCodeEditorState("") }
    var loadState by remember(resourceId) {
        mutableStateOf(ResourceTextEditorLoadState.Loading)
    }
    var saving by remember(resourceId) { mutableStateOf(false) }
    var loadedSnapshot by remember(resourceId) {
        mutableStateOf<ResourceTextEditorSnapshot?>(null)
    }
    val transitionSpec = AsteriskMotion.fadeThrough<ResourceTextEditorLoadState>(
        AsteriskMotion.fastEffects(),
    )
    val invalidJsonMessage = stringResource(R.string.settings_resource_json_editor_invalid_json)
    val missingMessage = stringResource(if (isHosts) R.string.settings_resource_hosts_editor_missing else R.string.settings_resource_json_editor_missing)
    val saveFailedMessage = stringResource(if (isHosts) R.string.settings_resource_hosts_editor_save_failed else R.string.settings_resource_json_editor_save_failed)
    val invalidHostsMessage = stringResource(R.string.settings_resource_hosts_editor_invalid)
    val invalidRuleSetMessage = stringResource(R.string.settings_resource_json_editor_invalid_rule_set)
    val changedMessage = stringResource(R.string.settings_resource_editor_changed)
    val savedMessage = stringResource(
        R.string.settings_resource_json_editor_saved,
        file?.name.orEmpty(),
    )
    val validationMessages = mapOf(
        JsonRuleSetValidationError.Empty to
            stringResource(R.string.settings_resource_json_editor_empty),
        JsonRuleSetValidationError.InvalidJson to invalidJsonMessage,
        JsonRuleSetValidationError.InvalidRoot to
            stringResource(R.string.settings_resource_json_editor_invalid_root),
        JsonRuleSetValidationError.InvalidVersion to
            stringResource(R.string.settings_resource_json_editor_invalid_version),
        JsonRuleSetValidationError.InvalidRules to
            stringResource(R.string.settings_resource_json_editor_invalid_rules),
    )

    LaunchedEffect(resourceId, file?.name) {
        loadState = ResourceTextEditorLoadState.Loading
        loadedSnapshot = null
        if (file == null) {
            loadState = ResourceTextEditorLoadState.Unavailable
            return@LaunchedEffect
        }
        try {
            val snapshot = services.resourceFileUseCase.readCustomText(file)
            editorState.replaceText(snapshot.content)
            loadedSnapshot = snapshot
            loadState = ResourceTextEditorLoadState.Ready
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            loadState = ResourceTextEditorLoadState.Unavailable
            services.tipNotifier.showError(
                error = error,
                fallbackMessage = missingMessage,
                failureContext = FailureLogContext(operation = "resource_text_load"),
            )
        }
    }

    val codeSearch = rememberAsteriskCodeSearchState()
    val editorFocus = rememberCodeEditorFocusState(
        editorFocused = editorState.isFocused,
        clearEditorFocus = editorState::clearFocus,
        searchActive = codeSearch.active,
        backEnabled = !saving,
    )

    fun formatCurrentJson() {
        runCatching { formatJsonRuleSet(editorState.snapshotText()) }
            .onSuccess(editorState::replaceText)
            .onFailure { scope.launch { services.tipNotifier.show(invalidJsonMessage) } }
    }

    fun save() {
        val currentFile = file ?: return
        val snapshot = loadedSnapshot ?: return
        if (saving || loadState != ResourceTextEditorLoadState.Ready) return
        val content = editorState.snapshotText()
        if (!isHosts) {
            validateJsonRuleSetStructure(content)?.let { error ->
                scope.launch { services.tipNotifier.show(validationMessages.getValue(error)) }
                return
            }
        }
        saving = true
        scope.launch {
            try {
                services.resourceFileUseCase.saveCustomText(
                    customFile = currentFile,
                    content = content,
                    expectedOrigin = snapshot.origin,
                )
                services.tipNotifier.show(savedMessage)
                navigator.pop()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                val fallback = (error as? InvalidJsonRuleSetStructureException)
                    ?.reason
                    ?.let(validationMessages::getValue)
                    ?: when (error) {
                        is InvalidHostsResourceException -> {
                            invalidHostsMessage.format(error.lineNumber)
                        }

                        is InvalidSingBoxJsonRuleSetException -> {
                            invalidRuleSetMessage
                        }

                        is features.resources.runtime.ResourceFileChangedException -> {
                            changedMessage
                        }

                        else -> {
                            saveFailedMessage
                        }
                    }
                services.tipNotifier.showError(
                    error = error,
                    fallbackMessage = fallback,
                    failureContext = FailureLogContext(operation = "resource_text_save"),
                )
            } finally {
                saving = false
            }
        }
    }

    AsteriskCodeSearchHost(
        state = codeSearch,
        modifier = Modifier.fillMaxSize(),
        enabled = loadState == ResourceTextEditorLoadState.Ready && !saving,
        editorTools = if (isHosts) null else { searchButton ->
            searchButton()
            AsteriskCodeActionButton(
                icon = Icons.Rounded.AutoFixHigh,
                label = stringResource(R.string.settings_resource_json_editor_format),
                onClick = ::formatCurrentJson,
            )
        },
    ) { searchButton, editorModifier, backdrop ->
        EditorPageScaffold(
            outerPadding = padding,
            isWideScreen = isWideScreen,
            title = {
                Column {
                    Text(stringResource(if (isHosts) R.string.settings_resource_hosts_editor_title else R.string.settings_resource_json_editor_title))
                    Text(
                        file?.name ?: stringResource(R.string.settings_resource_json_editor_summary),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            saving = saving,
            saveEnabled = loadState == ResourceTextEditorLoadState.Ready,
            onBack = {
                when {
                    codeSearch.active -> codeSearch.close()
                    editorFocus.expanded -> editorFocus.exit()
                    else -> navigator.pop()
                }
            },
            onSave = ::save,
            backdrop = backdrop,
            actions = { if (isHosts) searchButton() },
        ) { contentPadding ->
            AnimatedContent(
                targetState = loadState,
                transitionSpec = transitionSpec,
                label = "resource-text-editor-state",
                modifier = Modifier.fillMaxSize().padding(contentPadding),
            ) { state ->
                when (state) {
                    ResourceTextEditorLoadState.Loading -> ResourceTextEditorMessage {
                        CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.height(12.dp))
                        Text(stringResource(if (isHosts) R.string.settings_resource_hosts_editor_loading else R.string.settings_resource_json_editor_loading))
                    }
                    ResourceTextEditorLoadState.Unavailable -> ResourceTextEditorMessage {
                        Icon(
                            Icons.Rounded.ErrorOutline,
                            contentDescription = null,
                            modifier = Modifier.size(40.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            stringResource(if (isHosts) R.string.settings_resource_hosts_editor_missing else R.string.settings_resource_json_editor_missing),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    ResourceTextEditorLoadState.Ready -> CodeEditorFocusLayout(
                        expanded = editorFocus.expanded,
                        modifier = Modifier.fillMaxSize().padding(bottom = 12.dp).consumeWindowInsets(contentPadding),
                        supportingContent = {
                            Text(
                                stringResource(if (isHosts) R.string.settings_resource_hosts_editor_content else R.string.settings_resource_json_editor_content),
                                style = MaterialTheme.typography.titleMedium,
                            )
                            AnimatedVisibility(
                                visible = loadedSnapshot?.isDraft == true,
                                enter = AsteriskMotion.contentEnter(),
                                exit = AsteriskMotion.contentExit(),
                            ) {
                                Surface(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                                    shape = AsteriskShapeTokens.InnerContainer,
                                    color = MaterialTheme.colorScheme.primaryContainer,
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Icon(
                                            Icons.Rounded.Description,
                                            contentDescription = null,
                                            modifier = Modifier.size(20.dp),
                                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                        )
                                        Text(
                                            stringResource(R.string.settings_resource_json_editor_draft),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                                        )
                                    }
                                }
                            }
                            Text(
                                stringResource(if (isHosts) R.string.settings_resource_hosts_editor_content_summary else R.string.settings_resource_json_editor_content_summary),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
                            )
                        },
                    ) { modifier ->
                        if (isHosts) {
                            HostsCodeEditor(
                                state = editorState,
                                readOnly = saving,
                                modifier = modifier.then(editorModifier),
                                searchState = codeSearch,
                            )
                        } else {
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
}

@Composable
private fun ResourceTextEditorMessage(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        content = { content() },
    )
}

private enum class ResourceTextEditorLoadState {
    Loading,
    Ready,
    Unavailable,
}
