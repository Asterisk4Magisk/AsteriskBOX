// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.settings.sheets

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.ConnectionTestConcurrentBounds
import app.ConnectionTestTimeoutBounds
import app.R
import ui.components.AsteriskModalBottomSheet
import ui.icons.AsteriskIcons as Icons

/**
 * Bottom sheet for the five connection-test settings, opened from the
 * "连接测试" entry inside the regular (常规) settings section. Field
 * changes write through to AppState on the fly, so the sheet has no
 * 取消/保存 buttons — the user dismisses by tap-outside, back, or drag,
 * matching the pattern of the "本地代理设置" sheet.
 *
 * Wrapped in [key] over [show] so re-opening the sheet resets the
 * internal [SettingsTextField] state to the latest AppState value. That
 * mirrors the keyboard / ime reset behaviour the local proxy sheet uses.
 */
@Composable
internal fun ConnectionTestSettingsBottomSheet(
    show: Boolean,
    connectionTestUrl: String,
    connectionTestConcurrent: Int,
    connectionTestTimeoutMillis: Int,
    connectionTestUnifiedDelay: Boolean,
    connectionTestIgnoreHandshakeTime: Boolean,
    onConnectionTestUrlChange: (String) -> Unit,
    onConnectionTestConcurrentChange: (Int) -> Unit,
    onConnectionTestTimeoutChange: (Int) -> Unit,
    onConnectionTestUnifiedDelayChange: (Boolean) -> Unit,
    onConnectionTestIgnoreHandshakeTimeChange: (Boolean) -> Unit,
    onDismissRequest: () -> Unit,
) {
    val concurrentBounds = ConnectionTestConcurrentBounds
    val timeoutBounds = ConnectionTestTimeoutBounds
    AsteriskModalBottomSheet(
        show = show,
        onDismissRequest = onDismissRequest,
        title = stringResource(R.string.connection_test_settings_title),
    ) {
        key(show) {
            SettingsSheetContent {
                Text(
                    text = stringResource(R.string.connection_test_settings_summary),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 8.dp),
                )

                val urlError = if (connectionTestUrl.isBlank() ||
                    !(connectionTestUrl.startsWith("http://") ||
                        connectionTestUrl.startsWith("https://"))
                ) {
                    stringResource(R.string.connection_test_url_invalid)
                } else {
                    null
                }
                SettingsTextField(
                    value = connectionTestUrl,
                    onValueChange = onConnectionTestUrlChange,
                    label = stringResource(R.string.connection_test_url),
                    errorText = urlError,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Next,
                    ),
                )

                val concurrentDisplay = connectionTestConcurrent
                    .coerceIn(concurrentBounds.min, concurrentBounds.max)
                    .toString()
                val concurrentError = if (concurrentDisplay.toIntOrNull() == null) {
                    stringResource(R.string.common_error_positive_number)
                } else {
                    null
                }
                SettingsTextField(
                    value = concurrentDisplay,
                    onValueChange = { raw ->
                        val parsed = raw.filter(Char::isDigit).take(3).toIntOrNull()
                            ?: return@SettingsTextField
                        if (parsed in concurrentBounds.min..concurrentBounds.max) {
                            onConnectionTestConcurrentChange(parsed)
                        }
                    },
                    label = stringResource(R.string.connection_test_concurrent),
                    errorText = concurrentError,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Next,
                    ),
                )
                Text(
                    text = stringResource(
                        R.string.connection_test_concurrent_summary,
                        concurrentDisplay.toIntOrNull() ?: concurrentBounds.min,
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )

                val timeoutDisplay = connectionTestTimeoutMillis
                    .coerceIn(timeoutBounds.min, timeoutBounds.max)
                    .toString()
                val timeoutError = if (timeoutDisplay.toIntOrNull() == null) {
                    stringResource(R.string.common_error_positive_number)
                } else {
                    null
                }
                SettingsTextField(
                    value = timeoutDisplay,
                    onValueChange = { raw ->
                        val parsed = raw.filter(Char::isDigit).take(5).toIntOrNull()
                            ?: return@SettingsTextField
                        if (parsed in timeoutBounds.min..timeoutBounds.max) {
                            onConnectionTestTimeoutChange(parsed)
                        }
                    },
                    label = stringResource(R.string.connection_test_timeout),
                    errorText = timeoutError,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Done,
                    ),
                )
                Text(
                    text = stringResource(
                        R.string.connection_test_timeout_summary,
                        timeoutDisplay.toIntOrNull() ?: timeoutBounds.min,
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )

                SwitchPreference(
                    title = stringResource(R.string.connection_test_unified_delay),
                    icon = Icons.Rounded.Speed,
                    summary = stringResource(R.string.connection_test_unified_delay_summary),
                    checked = connectionTestUnifiedDelay,
                    onCheckedChange = onConnectionTestUnifiedDelayChange,
                )
                SwitchPreference(
                    title = stringResource(R.string.connection_test_ignore_handshake_time),
                    icon = Icons.Rounded.Speed,
                    summary = stringResource(R.string.connection_test_ignore_handshake_time_summary),
                    checked = connectionTestIgnoreHandshakeTime,
                    onCheckedChange = onConnectionTestIgnoreHandshakeTimeChange,
                )
            }
        }
    }
}
