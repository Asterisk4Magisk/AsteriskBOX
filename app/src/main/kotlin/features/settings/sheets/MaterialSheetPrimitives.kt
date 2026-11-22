// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.settings.sheets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import features.settings.SettingsDropdownRow
import features.settings.SettingsSwitchRow
import ui.components.AsteriskModalBottomSheet
import ui.components.AsteriskActionButton
import ui.layout.LocalAdaptiveWindow
import ui.layout.avoidFold
import ui.theme.AsteriskShapeTokens

@Composable
internal fun SettingsModalBottomSheet(
    show: Boolean,
    dismissEnabled: Boolean = true,
    title: String,
    startAction: @Composable () -> Unit,
    endAction: @Composable () -> Unit,
    onDismissRequest: () -> Unit,
    content: @Composable () -> Unit,
) {
    val window = LocalAdaptiveWindow.current
    val sheetContent: @Composable () -> Unit = {
        Column(modifier = Modifier.fillMaxWidth().heightIn(max = 720.dp)) {
            Box(Modifier.fillMaxWidth().weight(1f, fill = false)) { content() }
        }
    }
    if (window.hasSeparatingFold) {
        if (!show) return
        Dialog(
            onDismissRequest = onDismissRequest,
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false,
                dismissOnBackPress = dismissEnabled,
                dismissOnClickOutside = false,
            ),
        ) {
            Box(
                modifier = Modifier.fillMaxSize().avoidFold(window).safeDrawingPadding().imePadding(),
                contentAlignment = Alignment.BottomCenter,
            ) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = AsteriskShapeTokens.Sheet,
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                ) {
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            startAction()
                            Text(
                                text = title,
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.titleLarge,
                            )
                            endAction()
                        }
                        sheetContent()
                    }
                }
            }
        }
    } else {
        AsteriskModalBottomSheet(
            show = show,
            dismissEnabled = dismissEnabled,
            onDismissRequest = onDismissRequest,
            title = title,
            startAction = { startAction() },
            endAction = { endAction() },
        ) {
            sheetContent()
        }
    }
}

@Composable
internal fun TextButton(
    text: String,
    icon: ImageVector,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    AsteriskActionButton(
        text = text,
        icon = icon,
        onClick = onClick,
        enabled = enabled,
    )
}

@Composable
internal fun WindowDropdownPreference(
    title: String,
    icon: ImageVector,
    items: List<String>,
    selectedIndex: Int,
    onSelectedIndexChange: (Int) -> Unit,
    summary: String = "",
    horizontalPadding: Dp = 16.dp,
) {
    SettingsDropdownRow(
        title = title,
        summary = summary,
        icon = icon,
        items = items,
        selectedIndex = selectedIndex,
        onSelectedIndexChange = onSelectedIndexChange,
        horizontalPadding = horizontalPadding,
    )
}

@Composable
internal fun SwitchPreference(
    title: String,
    icon: ImageVector,
    summary: String = "",
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
) {
    SettingsSwitchRow(
        title = title,
        summary = summary,
        icon = icon,
        checked = checked,
        enabled = enabled,
        onCheckedChange = onCheckedChange,
    )
}

@Composable
internal fun SettingsSheetSection(
    title: String,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth(),
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
