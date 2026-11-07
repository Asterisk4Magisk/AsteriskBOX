// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.routing

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.R
import ui.components.AsteriskExpressiveCard
import ui.components.EditDeleteDropdownMenu
import ui.components.ruleEditorAnimatedListContentKey
import ui.theme.AsteriskMotion
import ui.icons.AsteriskIcons as Icons

/**
 * The child conditions of a logical rule, shared by route and DNS rule editors. The rule
 * type stays generic; callers describe how to read the fields the card shows.
 */
@Composable
internal fun <T> LogicalRuleConditionsCard(
    rules: List<T>,
    stableId: (T) -> Any,
    animationLabel: String,
    isEnabled: (T) -> Boolean,
    isLogical: (T) -> Boolean,
    remarks: (T) -> String,
    summary: @Composable (T) -> String,
    onAdd: () -> Unit,
    onEdit: (T) -> Unit,
    onEnabledChange: (T, Boolean) -> Unit,
    onDelete: (T) -> Unit,
    structureError: (List<T>) -> Int? = { null },
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f).padding(horizontal = 4.dp)) {
                    Text(
                        text = stringResource(R.string.routing_conditions),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        text = pluralStringResource(
                            R.plurals.routing_condition_count,
                            rules.size,
                            rules.size,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onAdd) {
                    Icon(
                        imageVector = Icons.Rounded.Add,
                        contentDescription = stringResource(R.string.routing_add_condition),
                    )
                }
            }

            val listMotion = AsteriskMotion.effects<Float>()
            AnimatedContent(
                targetState = rules,
                transitionSpec = AsteriskMotion.fadeThrough(listMotion),
                contentKey = { visibleRules ->
                    ruleEditorAnimatedListContentKey(visibleRules, stableId)
                },
                label = animationLabel,
            ) { visibleRules ->
                if (visibleRules.isEmpty()) {
                    Text(
                        text = stringResource(R.string.routing_conditions_empty),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 12.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        structureError(visibleRules)?.let { message ->
                            Text(
                                text = stringResource(message),
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        visibleRules.forEachIndexed { index, child ->
                            LogicalRuleConditionCard(
                                title = remarks(child).takeIf(String::isNotBlank)
                                    ?: stringResource(R.string.routing_condition_number, index + 1),
                                summary = summary(child),
                                enabled = isEnabled(child),
                                logical = isLogical(child),
                                onEdit = { onEdit(child) },
                                onEnabledChange = { enabled -> onEnabledChange(child, enabled) },
                                onDelete = { onDelete(child) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LogicalRuleConditionCard(
    title: String,
    summary: String,
    enabled: Boolean,
    logical: Boolean,
    onEdit: () -> Unit,
    onEnabledChange: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    AsteriskExpressiveCard(
        onClick = onEdit,
        modifier = Modifier.fillMaxWidth().alpha(if (enabled) 1f else 0.68f),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (logical) Icons.Rounded.AccountTree else Icons.Rounded.FilterAlt,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Column(
                modifier = Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Switch(
                checked = enabled,
                onCheckedChange = onEnabledChange,
            )
            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(Icons.Rounded.MoreVert, stringResource(R.string.common_more))
                }
                EditDeleteDropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                    onEdit = onEdit,
                    onDelete = onDelete,
                )
            }
        }
    }
}
