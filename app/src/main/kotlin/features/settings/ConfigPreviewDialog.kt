// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.AppState
import app.LocalAppServices
import app.R
import engine.singbox.SingBoxConfigFactory
import engine.singbox.config.loadSingBoxConfigPreview
import features.resources.runtime.singBoxResourceFilesDir
import features.singbox.JsonCodeEditor
import features.singbox.SingBoxCodeEditorState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import ui.clipboard.setPlainText
import ui.components.AsteriskActionButton
import ui.icons.AsteriskIcons as Icons
import java.io.File

@Composable
internal fun ConfigPreviewDialog(
    appState: AppState,
    onDismissRequest: () -> Unit,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val services = LocalAppServices.current
    val scope = rememberCoroutineScope()
    val copiedMessage = stringResource(R.string.proxy_error_dialog_copied)
    var content by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(appState, attempt) {
        content = null
        failed = false
        try {
            content = withContext(Dispatchers.IO) {
                loadSingBoxConfigPreview(
                    running = appState.proxyRunning,
                    configFile = File(context.singBoxResourceFilesDir(), "config.json"),
                    generateConfig = { SingBoxConfigFactory.buildConfig(context, appState) },
                )
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            failed = true
        }
    }

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Card(
            modifier = Modifier.fillMaxWidth().widthIn(max = 880.dp).padding(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                val previewContent = content
                Text(
                    text = stringResource(R.string.settings_config_preview),
                    style = MaterialTheme.typography.headlineSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                )
                if (previewContent != null) {
                    val editorState = remember(previewContent) {
                        SingBoxCodeEditorState(previewContent).also {
                            it.replaceText(previewContent, placeCursorAtEnd = false)
                        }
                    }
                    JsonCodeEditor(
                        state = editorState,
                        readOnly = true,
                        modifier = Modifier.fillMaxWidth().weight(1f, fill = false)
                            .heightIn(min = 360.dp, max = 560.dp),
                    )
                } else {
                    Box(
                        modifier = Modifier.fillMaxWidth().weight(1f, fill = false)
                            .heightIn(min = 360.dp, max = 560.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (failed) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(stringResource(R.string.settings_config_preview_failed))
                                AsteriskActionButton(
                                    text = stringResource(R.string.common_retry),
                                    icon = Icons.Rounded.Refresh,
                                    onClick = { attempt += 1 },
                                    modifier = Modifier.padding(top = 8.dp),
                                )
                            }
                        } else {
                            CircularProgressIndicator()
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                ) {
                    if (previewContent != null && previewContent.isNotBlank()) {
                        AsteriskActionButton(
                            text = stringResource(R.string.common_copy),
                            icon = Icons.Rounded.ContentCopy,
                            onClick = {
                                scope.launch {
                                    clipboard.setPlainText(previewContent)
                                    services.tipNotifier.show(copiedMessage)
                                }
                            },
                        )
                    }
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
