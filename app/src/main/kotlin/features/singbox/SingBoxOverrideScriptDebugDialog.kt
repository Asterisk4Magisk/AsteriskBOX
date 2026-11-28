// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.singbox

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.R
import ui.components.AsteriskCodePreviewDialog
import ui.components.AsteriskCodePreviewTabs
import ui.components.AsteriskCodeActionButton
import ui.layout.rememberCodeEditorFocusState
import ui.theme.AsteriskMotion
import ui.icons.AsteriskIcons as Icons

import engine.singbox.config.SingBoxScriptResult
import engine.singbox.config.SingBoxScriptStatus
import engine.singbox.config.encodeSingBoxJson

@Composable
internal fun SingBoxOverrideScriptDebugDialog(
    result: SingBoxScriptResult?,
    onDismissRequest: () -> Unit,
    onCopy: (String) -> Unit,
) {
    if (result == null) return
    var showOutput by remember(result) { mutableStateOf(false) }
    val consoleText = result.logs.takeIf(List<*>::isNotEmpty)
        ?.joinToString(separator = "\n") { log -> "[${log.level}] ${log.message}" }
        ?: stringResource(R.string.singbox_override_script_debug_no_logs)
    val outputText = result.output?.let(::encodeSingBoxJson)?.takeIf(String::isNotBlank)
        ?: stringResource(R.string.singbox_override_script_debug_no_output)
    val outputEditorState = remember(result, outputText) {
        SingBoxCodeEditorState().also { state ->
            state.replaceText(outputText, placeCursorAtEnd = false)
        }
    }
    val outputFocus = rememberCodeEditorFocusState(
        editorFocused = outputEditorState.isFocused,
        clearEditorFocus = outputEditorState::clearFocus,
    )
    val outputEffectsMotion = AsteriskMotion.fastEffects<Float>()
    val hasDiagnostics = !result.error.isNullOrBlank() || !result.warning.isNullOrBlank()

    AsteriskCodePreviewDialog(
        title = stringResource(R.string.singbox_override_script_debug_title),
        onDismissRequest = onDismissRequest,
        searchAvailable = showOutput,
        keepSearchButtonVisible = true,
        focusState = outputFocus,
        actions = {
            AsteriskCodeActionButton(
                icon = Icons.Rounded.ContentCopy,
                label = stringResource(R.string.singbox_override_script_debug_copy),
                onClick = {
                    onCopy(
                        result.toClipboardReport(
                            outputText = outputEditorState.snapshotText()
                                .takeUnless { result.output == null && it == outputText }.orEmpty(),
                        ),
                    )
                },
            )
        },
        topBarContent = {
            AsteriskCodePreviewTabs(
                showOutput = showOutput,
                onShowOutputChange = { showOutput = it },
                consoleLabel = stringResource(R.string.singbox_override_script_debug_logs),
                outputLabel = stringResource(R.string.singbox_override_script_debug_output),
            )
        },
    ) { searchState, editorModifier ->
        BoxWithConstraints(
            modifier = Modifier.fillMaxSize(),
        ) {
            val diagnosticsMaxHeight = maxHeight * 0.35f
            Column(modifier = Modifier.fillMaxSize()) {
                AnimatedVisibility(
                    visible = !outputFocus.expanded,
                    enter = AsteriskMotion.contentEnter(),
                    exit = AsteriskMotion.contentExit(),
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth()
                            .padding(bottom = if (hasDiagnostics) 8.dp else 0.dp)
                            .heightIn(max = diagnosticsMaxHeight)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        result.error?.takeIf(String::isNotBlank)?.let { error ->
                            DebugSection(
                                title = stringResource(R.string.singbox_override_script_debug_error),
                                body = error,
                                error = true,
                            )
                        }
                        result.warning?.takeIf(String::isNotBlank)?.let { warning ->
                            DebugSection(
                                title = stringResource(R.string.singbox_override_script_debug_warning),
                                body = warning,
                                warning = true,
                                modifier = Modifier.padding(top = if (!result.error.isNullOrBlank()) 8.dp else 0.dp),
                            )
                        }
                    }
                }
                AnimatedContent(
                    targetState = showOutput,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    transitionSpec = AsteriskMotion.fadeThrough(outputEffectsMotion),
                    contentAlignment = Alignment.TopStart,
                    label = "script-debug-output",
                ) { showingOutput ->
                    if (showingOutput) {
                        JsonCodeEditor(
                            state = outputEditorState,
                            searchState = searchState,
                            modifier = Modifier.fillMaxSize().then(editorModifier),
                        )
                    } else {
                        Surface(
                            modifier = Modifier.fillMaxSize(),
                            shape = MaterialTheme.shapes.large,
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        ) {
                            SelectionContainer {
                                Text(
                                    text = consoleText,
                                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                    modifier = Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
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
private fun DebugSection(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    error: Boolean = false,
    warning: Boolean = false,
) {
    val contentColor = when {
        error -> MaterialTheme.colorScheme.onErrorContainer
        warning -> MaterialTheme.colorScheme.onTertiaryContainer
        else -> MaterialTheme.colorScheme.onSurface
    }
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = when {
            error -> MaterialTheme.colorScheme.errorContainer
            warning -> MaterialTheme.colorScheme.tertiaryContainer
            else -> MaterialTheme.colorScheme.surfaceContainerLow
        },
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                color = if (error || warning) contentColor else MaterialTheme.colorScheme.primary,
            )
            Text(
                text = body,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = contentColor,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

internal fun SingBoxScriptResult.toClipboardReport(
    outputText: String = output?.let(::encodeSingBoxJson).orEmpty(),
): String {
    return buildString {
        appendLine(when (status) {
            SingBoxScriptStatus.Success -> "Script debug: success"
            SingBoxScriptStatus.Warning -> "Script debug: warning"
            SingBoxScriptStatus.Failed -> "Script debug: failed"
        })
        error?.takeIf(String::isNotBlank)?.let { error ->
            appendLine()
            appendLine("Error:")
            appendLine(error)
        }
        warning?.takeIf(String::isNotBlank)?.let { warning ->
            appendLine()
            appendLine("Warning:")
            appendLine(warning)
        }
        appendLine()
        appendLine("Console:")
        if (logs.isEmpty()) {
            appendLine("(empty)")
        } else {
            logs.forEach { log -> appendLine("[${log.level}] ${log.message}") }
        }
        outputText.takeIf(String::isNotBlank)?.let { output ->
            appendLine()
            appendLine("Output JSON:")
            appendLine(output)
        }
    }
}
