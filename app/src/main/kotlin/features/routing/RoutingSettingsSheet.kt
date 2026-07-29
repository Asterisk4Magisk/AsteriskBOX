// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package features.routing

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.SingBoxRouteNetworkStrategies
import app.SingBoxRouteNetworkTypes
import features.settings.SettingsActionRow
import features.settings.SettingsDropdownRow
import features.settings.SettingsSectionCard
import features.settings.SettingsSwitchRow
import features.settings.sheets.SettingsSheetContent
import features.settings.sheets.SettingsTextField
import org.asterisk.zcc.abox.R
import ui.components.AsteriskActionButton
import ui.components.AsteriskFilterChip
import ui.components.AsteriskModalBottomSheet
import ui.components.singBoxOptionLabel
import ui.icons.AsteriskIcons as Icons
import ui.theme.AsteriskMotion

@Composable
internal fun RoutingSettingsEntryCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SettingsSectionCard(
        modifier = modifier,
        bottomPadding = 0.dp,
    ) {
        SettingsActionRow(
            title = stringResource(R.string.routing_settings_title),
            summary = stringResource(R.string.routing_settings_summary),
            icon = Icons.Rounded.Tune,
            onClick = onClick,
        )
    }
}

@Composable
internal fun RoutingSettingsSheet(
    show: Boolean,
    initialDraft: RoutingSettingsDraft,
    saving: Boolean,
    onDismiss: () -> Unit,
    onSave: (RoutingSettingsDraft) -> Unit,
) {
    var draft by remember { mutableStateOf(initialDraft) }
    LaunchedEffect(show) {
        if (show) draft = initialDraft
    }

    val fallbackDelayValid = draft.hasValidFallbackDelay()
    AsteriskModalBottomSheet(
        show = show,
        onDismissRequest = onDismiss,
        title = stringResource(R.string.routing_settings_title),
        startAction = {
            AsteriskActionButton(
                text = stringResource(R.string.common_cancel),
                icon = Icons.Rounded.Close,
                enabled = !saving,
                onClick = onDismiss,
            )
        },
        endAction = {
            AsteriskActionButton(
                text = stringResource(R.string.common_save),
                icon = Icons.Rounded.Save,
                enabled = fallbackDelayValid && !saving,
                onClick = { onSave(draft.sanitized()) },
            )
        },
    ) {
        SettingsSheetContent {
            RoutingSettingsSectionTitle(
                stringResource(R.string.routing_settings_section_interface),
            )
            SettingsSectionCard(
                modifier = Modifier.padding(horizontal = 16.dp),
            ) {
                SettingsSwitchRow(
                    title = routingSettingLabel(
                        R.string.routing_settings_auto_detect_interface,
                        "auto_detect_interface",
                    ),
                    summary = stringResource(
                        R.string.routing_settings_auto_detect_interface_summary,
                    ),
                    icon = Icons.Rounded.SettingsInputComponent,
                    checked = draft.routeAutoDetectInterface,
                    onCheckedChange = { checked ->
                        draft = draft.copy(routeAutoDetectInterface = checked)
                    },
                )
                SettingsSwitchRow(
                    title = routingSettingLabel(
                        R.string.routing_settings_override_android_vpn,
                        "override_android_vpn",
                    ),
                    summary = stringResource(
                        R.string.routing_settings_override_android_vpn_summary,
                    ),
                    icon = Icons.Rounded.VpnLock,
                    checked = draft.routeOverrideAndroidVpn,
                    enabled = draft.routeAutoDetectInterface,
                    onCheckedChange = { checked ->
                        draft = draft.copy(routeOverrideAndroidVpn = checked)
                    },
                )
            }

            AnimatedVisibility(
                visible = draft.showNetworkSettings,
                enter = AsteriskMotion.contentEnter(),
                exit = AsteriskMotion.contentExit(),
            ) {
                Column {
                    RoutingSettingsSectionTitle(
                        stringResource(R.string.routing_settings_section_network),
                    )
                    SettingsSectionCard(
                        modifier = Modifier.padding(horizontal = 16.dp),
                    ) {
                        val strategyValues = listOf("") + SingBoxRouteNetworkStrategies
                        SettingsDropdownRow(
                            title = routingSettingLabel(
                                R.string.routing_settings_default_network_strategy,
                                "default_network_strategy",
                            ),
                            summary = stringResource(
                                R.string.routing_settings_default_network_strategy_summary,
                            ),
                            icon = Icons.AutoMirrored.Rounded.AltRoute,
                            items = listOf(
                                stringResource(R.string.common_not_specified),
                                singBoxOptionLabel(
                                    stringResource(R.string.routing_settings_strategy_default),
                                    "default",
                                ),
                                singBoxOptionLabel(
                                    stringResource(R.string.routing_settings_strategy_hybrid),
                                    "hybrid",
                                ),
                                singBoxOptionLabel(
                                    stringResource(R.string.routing_settings_strategy_fallback),
                                    "fallback",
                                ),
                            ),
                            selectedIndex = strategyValues
                                .indexOf(draft.routeDefaultNetworkStrategy)
                                .coerceAtLeast(0),
                            onSelectedIndexChange = { index ->
                                draft = draft.copy(
                                    routeDefaultNetworkStrategy = strategyValues[index],
                                )
                            },
                        )
                        RoutingNetworkTypesField(
                            title = routingSettingLabel(
                                R.string.routing_settings_default_network_type,
                                "default_network_type",
                            ),
                            summary = stringResource(
                                R.string.routing_settings_default_network_type_summary,
                            ),
                            selected = draft.routeDefaultNetworkTypes,
                            onSelectedChange = { selected ->
                                draft = draft.copy(routeDefaultNetworkTypes = selected)
                            },
                        )
                    }

                    AnimatedVisibility(
                        visible = draft.showFallbackSettings,
                        enter = AsteriskMotion.contentEnter(),
                        exit = AsteriskMotion.contentExit(),
                    ) {
                        Column {
                            RoutingSettingsSectionTitle(
                                stringResource(R.string.routing_settings_section_fallback),
                            )
                            SettingsSectionCard(
                                modifier = Modifier.padding(horizontal = 16.dp),
                            ) {
                                RoutingNetworkTypesField(
                                    title = routingSettingLabel(
                                        R.string.routing_settings_default_fallback_network_type,
                                        "default_fallback_network_type",
                                    ),
                                    summary = stringResource(
                                        R.string
                                            .routing_settings_default_fallback_network_type_summary,
                                    ),
                                    selected = draft.routeDefaultFallbackNetworkTypes,
                                    onSelectedChange = { selected ->
                                        draft = draft.copy(
                                            routeDefaultFallbackNetworkTypes = selected,
                                        )
                                    },
                                )
                                SettingsTextField(
                                    value = draft.routeDefaultFallbackDelay,
                                    onValueChange = { value ->
                                        draft = draft.copy(routeDefaultFallbackDelay = value)
                                    },
                                    label = routingSettingLabel(
                                        R.string.routing_settings_default_fallback_delay,
                                        "default_fallback_delay",
                                    ),
                                    errorText = if (fallbackDelayValid) {
                                        null
                                    } else {
                                        stringResource(
                                            R.string.routing_settings_duration_invalid,
                                        )
                                    },
                                )
                                Text(
                                    text = stringResource(
                                        R.string.routing_settings_default_fallback_delay_summary,
                                    ),
                                    modifier = Modifier.padding(
                                        start = 16.dp,
                                        end = 16.dp,
                                        bottom = 12.dp,
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }

            RoutingSettingsSectionTitle(
                stringResource(R.string.routing_settings_section_diagnostics),
            )
            SettingsSectionCard(
                modifier = Modifier.padding(horizontal = 16.dp),
                bottomPadding = 0.dp,
            ) {
                SettingsSwitchRow(
                    title = routingSettingLabel(
                        R.string.routing_settings_find_process,
                        "find_process",
                    ),
                    summary = stringResource(R.string.routing_settings_find_process_summary),
                    icon = Icons.Rounded.TravelExplore,
                    checked = draft.routeFindProcess,
                    onCheckedChange = { checked ->
                        draft = draft.copy(routeFindProcess = checked)
                    },
                )
            }
        }
    }
}

@Composable
private fun RoutingSettingsSectionTitle(text: String) {
    Text(
        text = text,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 4.dp),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
    )
}

@Composable
private fun RoutingNetworkTypesField(
    title: String,
    summary: String,
    selected: List<String>,
    onSelectedChange: (List<String>) -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
            )
            Text(
                text = summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SingBoxRouteNetworkTypes.forEach { value ->
                    AsteriskFilterChip(
                        selected = value in selected,
                        onClick = {
                            val updated = if (value in selected) {
                                selected - value
                            } else {
                                selected + value
                            }
                            val selectedSet = updated.toSet()
                            onSelectedChange(
                                SingBoxRouteNetworkTypes.filter(selectedSet::contains),
                            )
                        },
                        label = singBoxOptionLabel(
                            stringResource(networkTypeLabelResource(value)),
                            value,
                        ),
                    )
                }
            }
        }
    }
}

@Composable
private fun routingSettingLabel(
    @StringRes labelResource: Int,
    coreValue: String,
): String = singBoxOptionLabel(stringResource(labelResource), coreValue)

@StringRes
private fun networkTypeLabelResource(value: String): Int =
    when (value) {
        "wifi" -> R.string.routing_network_type_wifi
        "cellular" -> R.string.routing_network_type_cellular
        "ethernet" -> R.string.routing_network_type_ethernet
        else -> R.string.routing_network_type_other
    }
