// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package features.resources

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import features.singbox.JsonCodeEditor
import features.singbox.SingBoxCodeEditorState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.asterisk.zcc.abox.R
import ui.components.EditorPageScaffold
import ui.theme.AsteriskMotion
import ui.theme.AsteriskShapeTokens
import ui.icons.AsteriskIcons as Icons

@Composable
internal fun ResourceJsonEditorPage(
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
        ?.takeIf { candidate -> candidate.name.isSingBoxJsonRuleSet() }
    val editorState = remember(resourceId) { SingBoxCodeEditorState("") }
    var loadState by remember(resourceId) {
        mutableStateOf<ResourceJsonEditorLoadState>(ResourceJsonEditorLoadState.Loading)
    }
    var saving by remember(resourceId) { mutableStateOf(false) }
    var loadedContent by remember(resourceId) { mutableStateOf<String?>(null) }
    val transitionSpec = AsteriskMotion.fadeThrough<ResourceJsonEditorLoadState>(
        AsteriskMotion.fastEffects(),
    )
    val invalidJsonMessage = stringResource(R.string.settings_resource_json_editor_invalid_json)
    val missingMessage = stringResource(R.string.settings_resource_json_editor_missing)
    val saveFailedMessage = stringResource(R.string.settings_resource_json_editor_save_failed)
    val invalidRuleSetMessage = stringResource(R.string.settings_resource_json_editor_invalid_rule_set)
    val changedMessage = stringResource(R.string.settings_resource_json_editor_changed)
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
        loadState = ResourceJsonEditorLoadState.Loading
        loadedContent = null
        if (file == null) {
            loadState = ResourceJsonEditorLoadState.Unavailable
            return@LaunchedEffect
        }
        try {
            val content = services.resourceFileUseCase.readCustomJson(file)
            editorState.replaceText(content)
            loadedContent = content
            loadState = ResourceJsonEditorLoadState.Ready
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            loadState = ResourceJsonEditorLoadState.Unavailable
            services.tipNotifier.showError(
                error = error,
                fallbackMessage = missingMessage,
                failureContext = FailureLogContext(operation = "resource_json_load"),
            )
        }
    }

    fun formatCurrentJson() {
        runCatching { formatJsonRuleSet(editorState.snapshotText()) }
            .onSuccess(editorState::replaceText)
            .onFailure { scope.launch { services.tipNotifier.show(invalidJsonMessage) } }
    }

    fun save() {
        val currentFile = file ?: return
        val expectedContent = loadedContent ?: return
        if (saving || loadState != ResourceJsonEditorLoadState.Ready) return
        val content = editorState.snapshotText()
        validateJsonRuleSetStructure(content)?.let { error ->
            scope.launch { services.tipNotifier.show(validationMessages.getValue(error)) }
            return
        }
        saving = true
        scope.launch {
            try {
                services.resourceFileUseCase.saveCustomJson(
                    customFile = currentFile,
                    content = content,
                    expectedContent = expectedContent,
                )
                services.tipNotifier.show(savedMessage)
                navigator.pop()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                val fallback = (error as? InvalidJsonRuleSetStructureException)
                    ?.reason
                    ?.let(validationMessages::getValue)
                    ?: if (error is InvalidSingBoxJsonRuleSetException) {
                        invalidRuleSetMessage
                    } else if (error is features.resources.runtime.ResourceFileChangedException) {
                        changedMessage
                    } else {
                        saveFailedMessage
                    }
                services.tipNotifier.showError(
                    error = error,
                    fallbackMessage = fallback,
                    failureContext = FailureLogContext(operation = "resource_json_save"),
                )
            } finally {
                saving = false
            }
        }
    }

    EditorPageScaffold(
        outerPadding = padding,
        isWideScreen = isWideScreen,
        title = {
            Column {
                Text(stringResource(R.string.settings_resource_json_editor_title))
                Text(
                    file?.name ?: stringResource(R.string.settings_resource_json_editor_summary),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        saving = saving,
        saveEnabled = loadState == ResourceJsonEditorLoadState.Ready,
        onBack = navigator::pop,
        onSave = ::save,
    ) { contentPadding ->
        AnimatedContent(
            targetState = loadState,
            transitionSpec = transitionSpec,
            label = "resource-json-editor-state",
            modifier = Modifier.fillMaxSize().padding(contentPadding),
        ) { state ->
            when (state) {
                ResourceJsonEditorLoadState.Loading -> ResourceJsonEditorMessage {
                    CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(R.string.settings_resource_json_editor_loading))
                }
                ResourceJsonEditorLoadState.Unavailable -> ResourceJsonEditorMessage {
                    Icon(
                        Icons.Rounded.ErrorOutline,
                        contentDescription = null,
                        modifier = Modifier.size(40.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.settings_resource_json_editor_missing),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                ResourceJsonEditorLoadState.Ready -> Column(
                    modifier = Modifier.fillMaxSize().padding(vertical = 12.dp),
                ) {
                    Text(
                        stringResource(R.string.settings_resource_json_editor_content),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        stringResource(R.string.settings_resource_json_editor_content_summary),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
                    )
                    Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                        JsonCodeEditor(
                            label = stringResource(R.string.settings_resource_json_editor_content),
                            state = editorState,
                            readOnly = saving,
                            modifier = Modifier.fillMaxSize(),
                        )
                        Surface(
                            modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp),
                            shape = AsteriskShapeTokens.InnerContainer,
                            color = MaterialTheme.colorScheme.primaryContainer,
                            tonalElevation = 3.dp,
                        ) {
                            IconButton(onClick = ::formatCurrentJson, enabled = !saving) {
                                Icon(
                                    Icons.Rounded.AutoFixHigh,
                                    contentDescription = stringResource(
                                        R.string.settings_resource_json_editor_format,
                                    ),
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ResourceJsonEditorMessage(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        content = { content() },
    )
}

private enum class ResourceJsonEditorLoadState {
    Loading,
    Ready,
    Unavailable,
}
