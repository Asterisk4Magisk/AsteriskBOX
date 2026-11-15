// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.resources

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.CustomResourceFileState
import app.LocalAppServices
import app.R
import features.singbox.JsonCodeEditor
import features.singbox.SingBoxCodeEditorState
import kotlin.coroutines.cancellation.CancellationException
import ui.components.AsteriskActionButton
import ui.components.AsteriskCodeCopyButton
import ui.components.AsteriskCodePreviewDialog
import ui.components.rememberCodePreviewDocument
import ui.icons.AsteriskIcons as Icons

@Composable
internal fun ResourceRuleSetPreviewDialog(
    file: CustomResourceFileState,
    onDismissRequest: () -> Unit,
) {
    val resourceFiles = LocalAppServices.current.resourceFileUseCase
    var content by remember(file.id, file.name) { mutableStateOf<String?>(null) }
    var failure by remember(file.id, file.name) { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(file.id, file.name, attempt) {
        content = null
        failure = null
        try {
            content = resourceFiles.readCustomRuleSetPreview(file)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            failure = error.localizedMessage.orEmpty()
        }
    }
    val previewContent = content
    val document = rememberCodePreviewDocument(previewContent.orEmpty())
    val editorState = remember(document) { document?.let { SingBoxCodeEditorState(it, placeCursorAtEnd = false) } }
    val ready = previewContent != null && editorState != null
    AsteriskCodePreviewDialog(
        title = file.name,
        onDismissRequest = onDismissRequest,
        searchAvailable = ready,
        actions = { if (ready) AsteriskCodeCopyButton(editorState::snapshotText) },
    ) { searchState, editorModifier ->
        if (ready) {
            JsonCodeEditor(editorState, Modifier.fillMaxSize().then(editorModifier), searchState = searchState)
        } else if (failure != null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(stringResource(R.string.settings_resource_rule_set_preview_failed))
                    if (!failure.isNullOrBlank()) {
                        Text(
                            text = failure.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center,
                        )
                    }
                    AsteriskActionButton(
                        text = stringResource(R.string.common_retry),
                        icon = Icons.Rounded.Refresh,
                        onClick = { attempt += 1 },
                    )
                }
            }
        } else {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        }
    }
}
