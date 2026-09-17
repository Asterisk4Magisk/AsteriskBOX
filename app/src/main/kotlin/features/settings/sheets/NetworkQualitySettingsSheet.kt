// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.settings.sheets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.R
import ui.components.AsteriskActionButton
import ui.icons.AsteriskIcons as Icons

/**
 * Secondary MD3 popup opened from the main network quality sheet's settings
 * icon. Holds the four test parameters (config URL, max runtime, serial
 * measurement, HTTP/3) and validates inputs before closing. Saves nothing
 * to AppState; the values are read back through the shared
 * [NetworkQualityTestController].
 */
@Composable
internal fun NetworkQualitySettingsSheet(
    controller: NetworkQualityTestController,
    onDismissRequest: () -> Unit,
) {
    var configUrl by remember(controller, controller.showSettings) {
        mutableStateOf(controller.configUrl)
    }
    var maxRuntimeText by remember(controller, controller.showSettings) {
        mutableStateOf(controller.maxRuntimeSeconds.toString())
    }
    var serial by remember(controller, controller.showSettings) {
        mutableStateOf(controller.serial)
    }
    var http3 by remember(controller, controller.showSettings) {
        mutableStateOf(controller.http3)
    }
    val maxRuntimeError = if (maxRuntimeText.toIntOrNull()?.let { it > 0 } != true) {
        stringResource(R.string.common_error_positive_number)
    } else {
        null
    }
    val urlError = if (configUrl.isBlank()) {
        stringResource(R.string.common_error_required)
    } else {
        null
    }

    SettingsModalBottomSheet(
        show = controller.showSettings,
        onDismissRequest = onDismissRequest,
        title = stringResource(R.string.monitor_network_quality_settings_title),
        startAction = {
            AsteriskActionButton(
                text = stringResource(R.string.common_cancel),
                icon = Icons.Rounded.Close,
                onClick = onDismissRequest,
            )
        },
        endAction = {
            AsteriskActionButton(
                text = stringResource(R.string.common_save),
                icon = Icons.Rounded.Save,
                enabled = urlError == null && maxRuntimeError == null,
                onClick = {
                    val parsed = maxRuntimeText.toIntOrNull()
                    if (parsed != null && parsed > 0 && configUrl.isNotBlank()) {
                        controller.configUrl = configUrl
                        controller.maxRuntimeSeconds = parsed
                        controller.serial = serial
                        controller.http3 = http3
                        onDismissRequest()
                    }
                },
            )
        },
    ) {
        SettingsSheetContent {
            SnifferSheetSection(
                title = stringResource(R.string.monitor_network_quality_section_endpoint),
            ) {
                Text(
                    text = stringResource(R.string.monitor_network_quality_endpoint_summary),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
                SettingsTextField(
                    value = configUrl,
                    onValueChange = { configUrl = it },
                    label = stringResource(R.string.monitor_network_quality_config_url),
                    errorText = urlError,
                    enabled = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Next,
                    ),
                )
            }

            SnifferSheetSection(
                title = stringResource(R.string.monitor_network_quality_section_timing),
            ) {
                Text(
                    text = stringResource(R.string.monitor_network_quality_timing_summary),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
                SettingsTextField(
                    value = maxRuntimeText,
                    onValueChange = { raw ->
                        maxRuntimeText = raw.filter(Char::isDigit).take(5)
                    },
                    label = stringResource(R.string.monitor_network_quality_max_runtime),
                    errorText = maxRuntimeError,
                    enabled = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Done,
                    ),
                )
            }

            SnifferSheetSection(
                title = stringResource(R.string.monitor_network_quality_section_options),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                    SwitchPreference(
                        title = stringResource(R.string.monitor_network_quality_serial),
                        icon = Icons.Rounded.Speed,
                        summary = stringResource(R.string.monitor_network_quality_serial_summary),
                        checked = serial,
                        onCheckedChange = { serial = it },
                    )
                    SwitchPreference(
                        title = stringResource(R.string.monitor_network_quality_http3),
                        icon = Icons.Rounded.Lock,
                        summary = stringResource(R.string.monitor_network_quality_http3_summary),
                        checked = http3,
                        onCheckedChange = { http3 = it },
                    )
                }
            }
        }
    }
}
