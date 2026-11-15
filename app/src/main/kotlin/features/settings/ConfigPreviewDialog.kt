// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.AppState
import app.R
import engine.singbox.SingBoxConfigFactory
import engine.singbox.config.loadSingBoxConfigPreview
import features.resources.runtime.singBoxResourceFilesDir
import features.singbox.JsonCodeEditor
import features.singbox.SingBoxCodeEditorState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ui.components.AsteriskCodePreviewDialog
import ui.components.AsteriskCodeCopyButton
import ui.components.rememberCodePreviewDocument
import ui.components.AsteriskActionButton
import ui.icons.AsteriskIcons as Icons
import java.io.File

@Composable
internal fun ConfigPreviewDialog(
    appState: AppState,
    onDismissRequest: () -> Unit,
) {
    val context = LocalContext.current
    var content by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(attempt) {
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

    val previewContent = content
    val document = rememberCodePreviewDocument(previewContent.orEmpty())
    val editorState = remember(document) { document?.let { SingBoxCodeEditorState(it, placeCursorAtEnd = false) } }
    AsteriskCodePreviewDialog(
        title = stringResource(R.string.settings_config_preview),
        onDismissRequest = onDismissRequest,
        searchAvailable = previewContent != null && editorState != null,
        actions = { if (previewContent != null && editorState != null) AsteriskCodeCopyButton(editorState::snapshotText) },
    ) { searchState, editorModifier ->
        if (previewContent != null && editorState != null) {
            JsonCodeEditor(editorState, Modifier.fillMaxSize().then(editorModifier), searchState = searchState)
        } else {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
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
                } else CircularProgressIndicator()
            }
        }
    }
}
