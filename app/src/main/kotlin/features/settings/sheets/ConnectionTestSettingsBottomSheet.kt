// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.settings.sheets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.ConnectionTestConcurrentBounds
import app.ConnectionTestTimeoutBounds
import app.ConnectionTestTimeoutStepMillis
import app.R
import app.isValidConnectionTestUrl
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.delay
import ui.components.AsteriskModalBottomSheet
import ui.icons.AsteriskIcons as Icons

/**
 * Bottom sheet for the five connection-test settings, opened from the
 * "连接测试" entry inside the regular (常规) settings section.
 *
 * Field changes write through to AppState on the fly, so the sheet has
 * no 取消/保存 buttons — the user dismisses by tap-outside, back, or
 * drag, matching the "本地代理设置" sheet.
 *
 * Wrapped in [key] over [show] so re-opening the sheet resets the URL
 * text-field state and both slider positions to the latest AppState
 * values.
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
    AsteriskModalBottomSheet(
        show = show,
        onDismissRequest = onDismissRequest,
        title = stringResource(R.string.connection_test_settings_title),
    ) {
        key(show) {
            SettingsSheetContent {
                // Local text so partial / invalid input never reaches
                // AppState: it is persisted only once it validates as an
                // HTTP(S) URL, after a short typing pause or on dismiss.
                var urlText by remember { mutableStateOf(connectionTestUrl) }
                val latestUrlText by rememberUpdatedState(urlText)
                val committedUrl by rememberUpdatedState(connectionTestUrl)
                val commitUrl by rememberUpdatedState { text: String ->
                    val trimmed = text.trim()
                    if (isValidConnectionTestUrl(trimmed) && trimmed != committedUrl) {
                        onConnectionTestUrlChange(trimmed)
                    }
                }
                val urlError = if (!isValidConnectionTestUrl(urlText)) {
                    stringResource(R.string.connection_test_url_invalid)
                } else {
                    null
                }
                SettingsTextField(
                    value = urlText,
                    onValueChange = { urlText = it },
                    label = stringResource(R.string.connection_test_url),
                    errorText = urlError,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Next,
                    ),
                )
                LaunchedEffect(urlText) {
                    delay(UrlCommitDebounceMillis.milliseconds)
                    commitUrl(urlText)
                }
                DisposableEffect(Unit) {
                    onDispose { commitUrl(latestUrlText) }
                }

                IntSliderPreference(
                    title = stringResource(R.string.connection_test_concurrent),
                    value = connectionTestConcurrent,
                    onValueChange = onConnectionTestConcurrentChange,
                    valueRange = ConnectionTestConcurrentBounds.min..ConnectionTestConcurrentBounds.max,
                    // One stop per integer across 1..32.
                    steps = ConnectionTestConcurrentBounds.max - ConnectionTestConcurrentBounds.min - 1,
                )

                IntSliderPreference(
                    title = stringResource(R.string.connection_test_timeout),
                    value = connectionTestTimeoutMillis,
                    onValueChange = onConnectionTestTimeoutChange,
                    valueRange = ConnectionTestTimeoutBounds.min..ConnectionTestTimeoutBounds.max,
                    steps = (ConnectionTestTimeoutBounds.max - ConnectionTestTimeoutBounds.min) /
                        ConnectionTestTimeoutStepMillis - 1,
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

/**
 * Plain slider row: title on the left, live value on the right, native MD3
 * [Slider] underneath. Dragging only moves a local position; the config is
 * written once, when the gesture ends ([onValueChangeFinished]).
 */
// Plain value/onValueChange Slider overload on purpose: the drag position
// stays local and is committed once in onValueChangeFinished.
@Suppress("DEPRECATION")
@Composable
private fun IntSliderPreference(
    title: String,
    value: Int,
    onValueChange: (Int) -> Unit,
    valueRange: IntRange,
    steps: Int,
    modifier: Modifier = Modifier,
) {
    var position by remember { mutableFloatStateOf(value.toFloat()) }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = position.roundToInt().toString(),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Slider(
            value = position,
            onValueChange = { position = it },
            valueRange = valueRange.first.toFloat()..valueRange.last.toFloat(),
            steps = steps.coerceAtLeast(0),
            onValueChangeFinished = {
                onValueChange(
                    position.roundToInt().coerceIn(valueRange.first, valueRange.last),
                )
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private const val UrlCommitDebounceMillis = 500L
