// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.settings.sheets

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import engine.singbox.DefaultSingBoxSnifferProtocols
import engine.singbox.SingBoxSnifferProtocols
import engine.singbox.isNonNegativeSingBoxDuration
import engine.singbox.sanitizedSnifferProtocols
import engine.singbox.sanitizedSnifferTimeout
import features.settings.SnifferSettingsDraft
import org.asterisk.zcc.abox.R
import ui.components.AsteriskFilterChip
import ui.components.singBoxOptionLabel
import ui.icons.AsteriskIcons as Icons
import ui.theme.AsteriskMotion

@Composable
internal fun snifferSettingsSummary(
    enableSniffer: Boolean,
    snifferProtocols: List<String>,
    snifferTimeout: String,
): String {
    if (!enableSniffer) {
        return stringResource(R.string.settings_sniffer_summary_disabled)
    }

    val protocolCount = snifferProtocols.sanitizedSnifferProtocols().size
    return pluralStringResource(
        R.plurals.settings_sniffer_summary_enabled,
        protocolCount,
        protocolCount,
        snifferTimeout.sanitizedSnifferTimeout(),
    )
}

@Composable
internal fun SnifferSettingsBottomSheet(
    show: Boolean,
    saving: Boolean,
    draft: SnifferSettingsDraft,
    onDraftChange: (SnifferSettingsDraft) -> Unit,
    onDismissRequest: () -> Unit,
    onSave: (SnifferSettingsDraft) -> Unit,
) {
    val selectedProtocols = draft.snifferProtocols.normalizedSnifferProtocols()
    val timeoutError = if (
        draft.enableSniffer && !isNonNegativeSingBoxDuration(draft.snifferTimeout)
    ) {
        stringResource(R.string.settings_sniffer_duration_invalid)
    } else {
        null
    }
    val protocolsError = if (draft.enableSniffer && selectedProtocols.isEmpty()) {
        stringResource(R.string.settings_sniffer_protocols_empty)
    } else {
        null
    }
    val canSave = protocolsError == null && timeoutError == null
    SettingsModalBottomSheet(
        show = show,
        title = stringResource(R.string.settings_sniffer),
        startAction = {
            TextButton(
                text = stringResource(R.string.common_cancel),
                icon = Icons.Rounded.Close,
                enabled = !saving,
                onClick = onDismissRequest,
            )
        },
        endAction = {
            TextButton(
                text = stringResource(R.string.common_save),
                icon = Icons.Rounded.Save,
                enabled = canSave && !saving,
                onClick = {
                    if (canSave) onSave(draft.sanitized())
                },
            )
        },
        onDismissRequest = onDismissRequest,
    ) {
        key(show) {
            SettingsSheetContent {
                SnifferSheetSection(title = stringResource(R.string.settings_sniffer_section_behavior)) {
                    SwitchPreference(
                        title = stringResource(R.string.settings_sniffer_enable),
                        icon = Icons.Rounded.TravelExplore,
                        summary = stringResource(R.string.settings_sniffer_enable_summary),
                        checked = draft.enableSniffer,
                        onCheckedChange = { enabled ->
                            onDraftChange(draft.copy(enableSniffer = enabled))
                        },
                    )
                }

                AnimatedVisibility(
                    visible = draft.enableSniffer,
                    enter = AsteriskMotion.contentEnter(),
                    exit = AsteriskMotion.contentExit(),
                    label = "sniffer-options",
                ) {
                    Column {
                        SnifferSheetSection(
                            title = stringResource(R.string.settings_sniffer_section_protocols),
                        ) {
                            Text(
                                text = stringResource(R.string.settings_sniffer_protocols_summary),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                            )
                            FlowRow(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                SnifferProtocolOptions.forEach { option ->
                                    val selected = option.value in selectedProtocols
                                    AsteriskFilterChip(
                                        selected = selected,
                                        onClick = {
                                            val updated = if (selected) {
                                                selectedProtocols - option.value
                                            } else {
                                                selectedProtocols + option.value
                                            }
                                            onDraftChange(
                                                draft.copy(
                                                    snifferProtocols = SingBoxSnifferProtocols
                                                        .filter(updated::contains),
                                                ),
                                            )
                                        },
                                        label = singBoxOptionLabel(
                                            stringResource(option.label),
                                            option.value,
                                        ),
                                    )
                                }
                            }
                            AnimatedVisibility(
                                visible = protocolsError != null,
                                enter = AsteriskMotion.contentEnter(),
                                exit = AsteriskMotion.contentExit(),
                                label = "sniffer-protocol-error",
                            ) {
                                Text(
                                    text = protocolsError.orEmpty(),
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                                )
                            }
                        }

                        SnifferSheetSection(
                            title = stringResource(R.string.settings_sniffer_section_timing),
                        ) {
                            Text(
                                text = stringResource(R.string.settings_sniffer_timeout_summary),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                            )
                            SettingsTextField(
                                value = draft.snifferTimeout,
                                onValueChange = { value ->
                                    onDraftChange(draft.copy(snifferTimeout = value))
                                },
                                label = stringResource(R.string.settings_sniffer_timeout),
                                errorText = null,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                            AnimatedVisibility(
                                visible = timeoutError != null,
                                enter = AsteriskMotion.contentEnter(),
                                exit = AsteriskMotion.contentExit(),
                                label = "sniffer-timeout-error",
                            ) {
                                Text(
                                    text = timeoutError.orEmpty(),
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
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
private fun SnifferSheetSection(
    title: String,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp),
    ) {
        Text(
            text = title,
            color = MaterialTheme.colorScheme.primary,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        content()
    }
}

internal fun SnifferSettingsDraft.sanitized(): SnifferSettingsDraft {
    return copy(
        snifferProtocols = snifferProtocols.sanitizedSnifferProtocols(),
        snifferTimeout = snifferTimeout.sanitizedSnifferTimeout(),
    )
}

private fun List<String>.normalizedSnifferProtocols(): List<String> =
    map { value -> value.trim().lowercase() }
        .distinct()
        .filter(SingBoxSnifferProtocols::contains)

private data class SnifferProtocolOption(
    val value: String,
    @param:StringRes val label: Int,
)

private val SnifferProtocolOptions = listOf(
    SnifferProtocolOption("http", R.string.settings_sniffer_protocol_http),
    SnifferProtocolOption("tls", R.string.settings_sniffer_protocol_tls),
    SnifferProtocolOption("quic", R.string.settings_sniffer_protocol_quic),
    SnifferProtocolOption("stun", R.string.settings_sniffer_protocol_stun),
    SnifferProtocolOption("dns", R.string.settings_sniffer_protocol_dns),
    SnifferProtocolOption("bittorrent", R.string.settings_sniffer_protocol_bittorrent),
    SnifferProtocolOption("dtls", R.string.settings_sniffer_protocol_dtls),
    SnifferProtocolOption("ssh", R.string.settings_sniffer_protocol_ssh),
    SnifferProtocolOption("rdp", R.string.settings_sniffer_protocol_rdp),
    SnifferProtocolOption("ntp", R.string.settings_sniffer_protocol_ntp),
)
