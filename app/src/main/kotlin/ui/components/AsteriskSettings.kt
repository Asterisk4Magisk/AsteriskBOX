// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package ui.components

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonGroup
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.material3.ToggleButtonShapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Immutable
internal data class AsteriskSegmentItem<T>(
    val value: T,
    val label: String,
    val icon: ImageVector? = null,
    /** Dims the segment without disabling it, for modes that only show a tip when tapped. */
    val muted: Boolean = false,
)

/**
 * Connected segmented switch: a Material 3 expressive
 * [ButtonGroup] of [ToggleButton]s whose corners round only on the group's
 * outer edges. The selected segment is filled with `primaryContainer` and its
 * label goes bold; unselected segments stay on `surfaceVariant`. Pressing a
 * segment morphs its shape through [ButtonGroupDefaults]' press shapes, and
 * the width animates through the group's `animateWidth` scope.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun <T> AsteriskSegmentedControl(
    items: List<AsteriskSegmentItem<T>>,
    selectedValue: T,
    onSelected: (T) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    containerColor: Color = MaterialTheme.colorScheme.surfaceVariant,
    contentColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    checkedContainerColor: Color = MaterialTheme.colorScheme.primaryContainer,
    checkedContentColor: Color = MaterialTheme.colorScheme.onPrimaryContainer,
    contentPadding: PaddingValues = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
    textStyle: TextStyle = MaterialTheme.typography.labelLarge,
) {
    if (items.isEmpty()) return
    val interactionSources = remember(items.size) {
        List(items.size) { MutableInteractionSource() }
    }

    ButtonGroup(
        overflowIndicator = {},
        modifier = modifier.fillMaxWidth().selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
    ) {
        items.forEachIndexed { index, item ->
            val selected = item.value == selectedValue
            val interactionSource = interactionSources[index]

            customItem(
                buttonGroupContent = {
                    val shapes = when {
                        items.size == 1 -> {
                            val pill = RoundedCornerShape(percent = 50)
                            ToggleButtonShapes(shape = pill, pressedShape = pill, checkedShape = pill)
                        }
                        index == 0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                        index == items.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                        else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                    }
                    ToggleButton(
                        checked = selected,
                        onCheckedChange = { onSelected(item.value) },
                        enabled = enabled,
                        modifier = Modifier
                            // Equal-width segments: the expressive press-width
                            // animation cannot play under weight(1f), so it is
                            // deliberately not applied here.
                            .weight(1f)
                            .alpha(if (item.muted) 0.38f else 1f),
                        shapes = shapes,
                        colors = ToggleButtonDefaults.colors(
                            containerColor = containerColor,
                            contentColor = contentColor,
                            checkedContainerColor = checkedContainerColor,
                            checkedContentColor = checkedContentColor,
                            disabledContainerColor = containerColor.copy(alpha = 0.3f),
                            disabledContentColor = contentColor.copy(alpha = 0.38f),
                        ),
                        contentPadding = contentPadding,
                        interactionSource = interactionSource,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            if (item.icon != null) {
                                Icon(
                                    imageVector = item.icon,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                            Text(
                                text = item.label,
                                style = textStyle,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                },
                menuContent = {},
            )
        }
    }
}
