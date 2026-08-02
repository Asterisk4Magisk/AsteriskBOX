// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.settings

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.asterisk.zcc.abox.R
import app.modes.RunModeBpf2Socks
import app.modes.RunModeEbpf
import app.modes.RunModeTun2Socks
import app.modes.RunModeTproxy
import androidx.compose.ui.res.stringResource
import ui.icons.AsteriskIcons as Icons
import ui.text.formatTemplate
import ui.theme.AsteriskMotion

private val SettingsTrailingValueMaxWidth = 160.dp
internal val LocalSettingsSearchQuery = compositionLocalOf { "" }

@Composable
internal fun SmallTitle(text: String) = SettingsSectionTitle(text)

@Composable
internal fun ArrowPreference(
    title: String,
    onClick: () -> Unit,
    summary: String = "",
    icon: ImageVector = Icons.Rounded.Tune,
    enabled: Boolean = true,
) = SettingsActionRow(
    title = title,
    summary = summary,
    icon = icon,
    onClick = onClick,
    enabled = enabled,
)

@Composable
internal fun SwitchPreference(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    summary: String = "",
    icon: ImageVector = Icons.Rounded.Tune,
) = SettingsSwitchRow(
    title = title,
    summary = summary,
    icon = icon,
    checked = checked,
    onCheckedChange = onCheckedChange,
)

@Composable
internal fun OverlayDropdownPreference(
    title: String,
    items: List<String>,
    selectedIndex: Int,
    onSelectedIndexChange: (Int) -> Unit,
    summary: String = "",
    icon: ImageVector = Icons.Rounded.Tune,
) = SettingsDropdownRow(
    title = title,
    summary = summary,
    icon = icon,
    items = items,
    selectedIndex = selectedIndex,
    onSelectedIndexChange = onSelectedIndexChange,
)

@Composable
internal fun SettingsSearchProvider(query: String, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalSettingsSearchQuery provides query, content = content)
}

@Composable
internal fun SettingsSectionTitle(text: String) {
    Text(
        text = text,
        modifier = Modifier.padding(start = 8.dp, end = 8.dp, top = 12.dp, bottom = 8.dp),
        color = MaterialTheme.colorScheme.primary,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
    )
}

@Composable
internal fun SettingsSectionCard(
    modifier: Modifier = Modifier,
    bottomPadding: Dp = 12.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = bottomPadding),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        shape = MaterialTheme.shapes.extraLarge,
    ) {
        Column(content = content)
    }
}

@Composable
internal fun SettingsActionRow(
    title: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    summary: String = "",
    value: String = "",
    enabled: Boolean = true,
) {
    if (!settingsRowMatchesQuery(title, summary, value)) return
    val rowAlpha by animateFloatAsState(
        targetValue = if (enabled) 1f else 0.38f,
        animationSpec = AsteriskMotion.fastEffects(),
        label = "settings-action-enabled",
    )
    SettingsRow(
        title = title,
        icon = icon,
        summary = summary,
        value = value,
        modifier = modifier
            .graphicsLayer { alpha = rowAlpha }
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        trailing = {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
    )
}

@Composable
internal fun SettingsSwitchRow(
    title: String,
    icon: ImageVector,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    summary: String = "",
    enabled: Boolean = true,
) {
    if (!settingsRowMatchesQuery(title, summary, checked.toString())) return
    SettingsRow(
        title = title,
        icon = icon,
        summary = summary,
        modifier = modifier.clickable(enabled = enabled, role = Role.Switch) {
            onCheckedChange(!checked)
        },
        trailing = {
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                enabled = enabled,
            )
        },
    )
}

@Composable
internal fun SettingsDropdownRow(
    title: String,
    icon: ImageVector,
    items: List<String>,
    selectedIndex: Int,
    onSelectedIndexChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    summary: String = "",
    enabled: Boolean = true,
) {
    if (items.isEmpty()) return
    val safeIndex = selectedIndex.coerceIn(items.indices)
    val value = items[safeIndex]
    if (!settingsRowMatchesQuery(title, summary, value, items)) return
    var expanded by remember { mutableStateOf(false) }
    val expansionRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = AsteriskMotion.fastEffects(),
        label = "settings-dropdown-indicator",
    )
    Box(modifier = modifier.fillMaxWidth()) {
        SettingsRow(
            title = title,
            icon = icon,
            summary = summary,
            value = value,
            modifier = Modifier.clickable(enabled = enabled, role = Role.DropdownList) {
                expanded = !expanded
            },
            trailing = {
                Icon(
                    imageVector = Icons.Rounded.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier.rotate(expansionRotation),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
        )
        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 16.dp)
                .size(1.dp),
        ) {
            DropdownMenu(
                expanded = expanded && enabled,
                onDismissRequest = { expanded = false },
                modifier = Modifier.widthIn(min = 180.dp, max = 280.dp),
            ) {
                items.forEachIndexed { index, item ->
                    val selected = index == safeIndex
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = item,
                                color = if (selected) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                            )
                        },
                        leadingIcon = {
                            if (selected) {
                                Icon(
                                    imageVector = Icons.Rounded.Check,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            } else {
                                Spacer(Modifier.size(24.dp))
                            }
                        },
                        onClick = {
                            expanded = false
                            onSelectedIndexChange(index)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsRow(
    title: String,
    icon: ImageVector,
    modifier: Modifier,
    summary: String = "",
    value: String = "",
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(36.dp),
            contentAlignment = Alignment.Center,
        ) {
            Card(
                modifier = Modifier.size(32.dp),
                shape = CircleShape,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
            ) {
                Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        modifier = Modifier.size(19.dp),
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (summary.isNotBlank()) {
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (value.isNotBlank()) {
            Spacer(Modifier.width(12.dp))
            Text(
                text = value,
                modifier = Modifier.widthIn(max = SettingsTrailingValueMaxWidth),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (trailing != null) {
            Spacer(Modifier.width(8.dp))
            trailing()
        }
    }
}

@Composable
private fun settingsRowMatchesQuery(
    title: String,
    summary: String,
    value: String,
    optionText: List<String> = emptyList(),
): Boolean =
    SettingsSearchItem(SettingsSectionId.Theme, title, summary, value, optionText)
        .matchesSettingsQuery(LocalSettingsSearchQuery.current)

@Composable
internal fun localProxySettingsSummary(
    runMode: Int,
    port: String,
    listenAllInterfaces: Boolean,
    transparentProxyPort: String,
    bpf2SocksBridgePort: String,
    socks5ProxyPort: String,
): String {
    val summary = if (listenAllInterfaces) {
        stringResource(R.string.settings_local_proxy_summary_all_interfaces)
    } else {
        stringResource(R.string.settings_local_proxy_summary_fixed)
    }
    val localProxySummary = summary.formatTemplate("port" to port)
    val inboundProxySummary = when (runMode) {
        RunModeTproxy -> stringResource(R.string.settings_local_proxy_summary_tproxy)
            .formatTemplate("port" to transparentProxyPort)
        RunModeTun2Socks -> stringResource(R.string.settings_local_proxy_summary_tun2socks)
            .formatTemplate("port" to socks5ProxyPort)
        RunModeBpf2Socks -> stringResource(R.string.settings_local_proxy_summary_bpf2socks)
            .formatTemplate("bridgePort" to bpf2SocksBridgePort, "socksPort" to socks5ProxyPort)
        RunModeEbpf -> stringResource(R.string.settings_local_proxy_summary_ebpf)
            .formatTemplate("port" to bpf2SocksBridgePort)
        else -> ""
    }
    return listOf(inboundProxySummary, localProxySummary).filter(String::isNotBlank).joinToString("，")
}
