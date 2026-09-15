// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.singbox

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.R
import engine.root.runtime.ProxyErrorBus
import engine.root.runtime.ProxyErrorExplanation
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Subscribes to [ProxyErrorBus] and renders the failure dialog whenever a non-null explanation
 * is published. Renders nothing while the bus slot is empty.
 *
 * Hosted at the app content level so the dialog is visible whichever destination the user is on
 * — proxy start failures are triggered from the home page toggle.
 */
@Composable
internal fun ProxyErrorHost() {
    val explanation by ProxyErrorBus.observe().collectAsState(initial = null)
    val current = explanation
    if (current != null) {
        ProxyErrorDialog(
            explanation = current,
            onDismiss = { ProxyErrorBus.acknowledge() },
        )
    }
}

@Composable
private fun ProxyErrorDialog(
    explanation: ProxyErrorExplanation,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val timestamp = remember(explanation.occurredAtEpochMillis) {
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            .format(Date(explanation.occurredAtEpochMillis))
    }
    val copiedLabel = stringResource(R.string.proxy_error_dialog_copied)
    val suggestions = explanation.diagnostics.map { id -> stringResource(id) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.proxy_error_dialog_title)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    text = stringResource(R.string.proxy_error_dialog_mode_title, explanation.mode),
                    style = MaterialTheme.typography.titleSmall,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.proxy_error_dialog_occurred_at, timestamp),
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.proxy_error_dialog_details),
                    style = MaterialTheme.typography.labelLarge,
                )
                Spacer(modifier = Modifier.height(4.dp))
                SelectionContainer {
                    Text(
                        text = explanation.rawMessage,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                if (suggestions.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = stringResource(R.string.proxy_error_dialog_diagnostics),
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    suggestions.forEach { suggestion ->
                        Text(
                            text = "• $suggestion",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                copyToClipboard(context, explanation.rawMessage)
                runCatching {
                    android.widget.Toast.makeText(context, copiedLabel, android.widget.Toast.LENGTH_SHORT).show()
                }
            }) { Text(stringResource(R.string.proxy_error_dialog_copy)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_close)) }
        },
    )
}

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    clipboard?.setPrimaryClip(ClipData.newPlainText("AsteriskBOX proxy error", text))
}
