// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.settings.sheets

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
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
import app.SingBoxDnsRuleLogicalModeOr
import app.SingBoxDnsRuleState
import app.SingBoxDnsRuleTypeLogical
import engine.singbox.config.hasValidDnsRuleStructure
import features.dns.dnsMatcherCount
import org.asterisk.zcc.abox.R
import ui.components.AsteriskExpressiveCard
import ui.theme.AsteriskMotion
import ui.icons.AsteriskIcons as Icons

@Composable
internal fun DnsLogicalChildrenCard(
    rules: List<SingBoxDnsRuleState>,
    onAdd: () -> Unit,
    onEdit: (SingBoxDnsRuleState) -> Unit,
    onEnabledChange: (SingBoxDnsRuleState, Boolean) -> Unit,
    onDelete: (SingBoxDnsRuleState) -> Unit,
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
                label = "dns-logical-conditions",
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
                        val structureError = when {
                            visibleRules.none(SingBoxDnsRuleState::enabled) ->
                                R.string.settings_dns_logical_enabled_condition_required
                            visibleRules.any { child ->
                                !child.hasValidDnsRuleStructure(nested = true)
                            } -> R.string.settings_dns_logical_invalid_condition
                            else -> null
                        }
                        structureError?.let { message ->
                            Text(
                                text = stringResource(message),
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        visibleRules.forEachIndexed { index, child ->
                            DnsLogicalChildCard(
                                rule = child,
                                fallbackName = stringResource(
                                    R.string.routing_condition_number,
                                    index + 1,
                                ),
                                onEdit = { onEdit(child) },
                                onEnabledChange = { enabled ->
                                    onEnabledChange(child, enabled)
                                },
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
private fun DnsLogicalChildCard(
    rule: SingBoxDnsRuleState,
    fallbackName: String,
    onEdit: () -> Unit,
    onEnabledChange: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    AsteriskExpressiveCard(
        onClick = onEdit,
        modifier = Modifier.fillMaxWidth().alpha(if (rule.enabled) 1f else 0.68f),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (rule.type == SingBoxDnsRuleTypeLogical) {
                    Icons.Rounded.AccountTree
                } else {
                    Icons.Rounded.FilterAlt
                },
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Column(
                modifier = Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    text = rule.remarks.takeIf(String::isNotBlank) ?: fallbackName,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = dnsRuleMatchSummary(rule),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Switch(
                checked = rule.enabled,
                onCheckedChange = onEnabledChange,
            )
            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(Icons.Rounded.MoreVert, stringResource(R.string.common_more))
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.common_edit)) },
                        leadingIcon = { Icon(Icons.Rounded.Edit, contentDescription = null) },
                        onClick = {
                            menuExpanded = false
                            onEdit()
                        },
                    )
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.common_delete)) },
                        leadingIcon = { Icon(Icons.Rounded.Delete, contentDescription = null) },
                        onClick = {
                            menuExpanded = false
                            onDelete()
                        },
                    )
                }
            }
        }
    }
}

@Composable
internal fun dnsRuleMatchSummary(rule: SingBoxDnsRuleState): String {
    if (rule.type == SingBoxDnsRuleTypeLogical) {
        val modeLabel = stringResource(
            if (rule.logicalMode == SingBoxDnsRuleLogicalModeOr) {
                R.string.routing_logical_mode_or_short
            } else {
                R.string.routing_logical_mode_and_short
            },
        )
        val conditionCount = pluralStringResource(
            R.plurals.routing_condition_count,
            rule.logicalRules.size,
            rule.logicalRules.size,
        )
        return stringResource(R.string.routing_logical_summary, modeLabel, conditionCount)
    }
    val matcherCount = rule.dnsMatcherCount()
    return if (matcherCount == 0) {
        stringResource(R.string.settings_dns_rule_match_all)
    } else {
        pluralStringResource(R.plurals.routing_match_count, matcherCount, matcherCount)
    }
}
