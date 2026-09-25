// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package features.singbox

import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.Dialog
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Card
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
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
import androidx.compose.ui.window.DialogProperties
import app.R
import ui.components.AsteriskActionButton
import ui.components.AsteriskFilterChip
import ui.theme.AsteriskMotion
import ui.icons.AsteriskIcons as Icons

import engine.singbox.config.SingBoxScriptResult
import engine.singbox.config.encodeSingBoxJson

@Composable
internal fun SingBoxOverrideScriptDebugDialog(
    result: SingBoxScriptResult?,
    onDismissRequest: () -> Unit,
    onCopy: (SingBoxScriptResult) -> Unit,
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
    val outputEffectsMotion = AsteriskMotion.fastEffects<Float>()

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Card(
            modifier = Modifier.fillMaxWidth().widthIn(max = 880.dp).padding(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.singbox_override_script_debug_title),
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = if (result.success) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.errorContainer
                        },
                    ) {
                        Text(
                            text = stringResource(
                                if (result.success) {
                                    R.string.singbox_override_script_debug_success
                                } else {
                                    R.string.singbox_override_script_debug_failed
                                },
                            ),
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        )
                    }
                }
                BoxWithConstraints(
                    modifier = Modifier.fillMaxWidth().height(560.dp).weight(1f, fill = false),
                ) {
                    val diagnosticsMaxHeight = maxHeight * 0.35f
                    Column(modifier = Modifier.fillMaxSize()) {
                        Column(
                            modifier = Modifier.fillMaxWidth()
                                .heightIn(max = diagnosticsMaxHeight)
                                .verticalScroll(rememberScrollState()),
                        ) {
                            result.error?.takeIf(String::isNotBlank)?.let { error ->
                                DebugSection(
                                    title = stringResource(R.string.singbox_override_script_debug_error),
                                    body = error,
                                    error = true,
                                    modifier = Modifier.padding(top = 14.dp),
                                )
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            AsteriskFilterChip(
                                selected = !showOutput,
                                onClick = { showOutput = false },
                                label = stringResource(R.string.singbox_override_script_debug_logs),
                                leadingIcon = { Icon(Icons.Rounded.Code, contentDescription = null) },
                            )
                            AsteriskFilterChip(
                                selected = showOutput,
                                onClick = { showOutput = true },
                                label = stringResource(R.string.singbox_override_script_debug_output),
                                leadingIcon = { Icon(Icons.Rounded.Description, contentDescription = null) },
                            )
                        }
                        AnimatedContent(
                            targetState = showOutput,
                            modifier = Modifier.fillMaxWidth().weight(1f).padding(top = 8.dp),
                            transitionSpec = AsteriskMotion.fadeThrough(outputEffectsMotion),
                            contentAlignment = Alignment.TopStart,
                            label = "script-debug-output",
                        ) { showingOutput ->
                            if (showingOutput) {
                                JsonCodeEditor(
                                    state = outputEditorState,
                                    readOnly = true,
                                    modifier = Modifier.fillMaxSize(),
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
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                ) {
                    AsteriskActionButton(
                        text = stringResource(R.string.singbox_override_script_debug_copy),
                        icon = Icons.Rounded.ContentCopy,
                        onClick = { onCopy(result) },
                    )
                    AsteriskActionButton(
                        text = stringResource(R.string.common_complete),
                        icon = Icons.Rounded.Check,
                        onClick = onDismissRequest,
                    )
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
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = if (error) {
            MaterialTheme.colorScheme.errorContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        },
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                color = if (error) {
                    MaterialTheme.colorScheme.onErrorContainer
                } else {
                    MaterialTheme.colorScheme.primary
                },
            )
            Text(
                text = body,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = if (error) {
                    MaterialTheme.colorScheme.onErrorContainer
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

internal fun SingBoxScriptResult.toClipboardReport(): String {
    return buildString {
        appendLine(if (success) "Script debug: success" else "Script debug: failed")
        error?.takeIf(String::isNotBlank)?.let { error ->
            appendLine()
            appendLine("Error:")
            appendLine(error)
        }
        appendLine()
        appendLine("Console:")
        if (logs.isEmpty()) {
            appendLine("(empty)")
        } else {
            logs.forEach { log -> appendLine("[${log.level}] ${log.message}") }
        }
        output?.let(::encodeSingBoxJson)?.takeIf(String::isNotBlank)?.let { output ->
            appendLine()
            appendLine("Output JSON:")
            appendLine(output)
        }
    }
}
