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
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
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
 * "连接测试" entry inside the regular (常规) settings section.
 *
 * Layout follows the husi pattern (xchacha20-poly1305/husi:
 * composeApp/.../MiscSettingsGroup.kt): the URL is a text field, the
 * two numeric settings (concurrency, timeout) are sliders, and the two
 * measurement toggles (unified delay, ignore handshake) are switches.
 * Field changes write through to AppState on the fly, so the sheet has
 * no 取消/保存 buttons — the user dismisses by tap-outside, back, or
 * drag, matching the "本地代理设置" sheet.
 *
 * Wrapped in [key] over [show] so re-opening the sheet resets the URL
 * text-field state to the latest AppState value, mirroring the keyboard
 * / ime reset behaviour the local proxy sheet uses.
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

                // Concurrent slider: 1..32 with 30 intermediate stops, so the
                // integer step equals 1 across the whole range (matching husi's
                // SliderPreference(valueRange = 1f..32f, valueSteps = 32)).
                IntSliderPreference(
                    title = stringResource(R.string.connection_test_concurrent),
                    value = connectionTestConcurrent
                        .coerceIn(ConnectionTestConcurrentBounds.min, ConnectionTestConcurrentBounds.max),
                    onValueChange = onConnectionTestConcurrentChange,
                    valueRange = ConnectionTestConcurrentBounds.min..ConnectionTestConcurrentBounds.max,
                    steps = ConnectionTestConcurrentBounds.max -
                        ConnectionTestConcurrentBounds.min - 1,
                )

                // Timeout slider: 1024..8192 with 20 intermediate stops, so the
                // integer step equals ~358 ms across the whole range (matching
                // husi's SliderPreference(valueRange = 1024f..8192f,
                // valueSteps = 20)).
                IntSliderPreference(
                    title = stringResource(R.string.connection_test_timeout),
                    value = connectionTestTimeoutMillis
                        .coerceIn(ConnectionTestTimeoutBounds.min, ConnectionTestTimeoutBounds.max),
                    onValueChange = onConnectionTestTimeoutChange,
                    valueRange = ConnectionTestTimeoutBounds.min..ConnectionTestTimeoutBounds.max,
                    steps = 20,
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
 * Compact label + integer slider + value row, in the same visual style as
 * the other sheets entries. The current value is right-aligned in the
 * primary tint, mirroring the readout in husi's SliderPreference.
 *
 * @param steps Number of intermediate stops between [valueRange.first]
 *   and [valueRange.last], exclusive of the endpoints. Pass 0 for
 *   continuous. For a per-integer slider over [min..max], pass
 *   `max - min - 1` (the slider still snaps to the exact range bounds).
 */
@Composable
private fun IntSliderPreference(
    title: String,
    value: Int,
    onValueChange: (Int) -> Unit,
    valueRange: IntRange,
    steps: Int,
    modifier: Modifier = Modifier,
) {
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
                text = value.toString(),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        val sliderState = androidx.compose.material3.rememberSliderState(
            value = value.toFloat(),
            steps = steps.coerceAtLeast(0),
            trackRange = valueRange.first.toFloat()..valueRange.last.toFloat(),
        )
        androidx.compose.material3.Slider(
            state = sliderState,
            onValueChange = { newValue ->
                val coerced = newValue.toInt()
                    .coerceIn(valueRange.first, valueRange.last)
                if (coerced != value) onValueChange(coerced)
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
