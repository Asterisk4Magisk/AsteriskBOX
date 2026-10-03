// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ui.theme.AsteriskMotion

internal fun <T, K> ruleEditorAnimatedListContentKey(
    items: List<T>,
    stableId: (T) -> K,
): List<K> = items.map(stableId)

internal data class RuleEditorChoice(
    val value: String,
    val label: String,
)

internal fun resolveRuleEditorChoice(
    choices: List<RuleEditorChoice>,
    selectedValue: String,
    missingLabel: (String) -> String,
): RuleEditorChoice = choices.firstOrNull { choice -> choice.value == selectedValue }
    ?: RuleEditorChoice(selectedValue, missingLabel(selectedValue))

internal fun ruleEditorChoices(
    values: List<String>,
    labels: List<String>,
): List<RuleEditorChoice> {
    require(values.size == labels.size) {
        "Rule editor choice values and labels must align"
    }
    return values.zip(labels) { value, label ->
        RuleEditorChoice(value, label)
    }
}

internal fun ruleEditorChoiceInteractionEnabled(
    choices: List<RuleEditorChoice>,
    enabled: Boolean = true,
): Boolean = enabled && choices.isNotEmpty()

internal fun <T> ruleEditorChipChoices(
    choices: List<Pair<T, String>>,
    selected: Set<T>,
    staleLabel: ((T) -> String)?,
): List<Pair<T, String>> {
    if (staleLabel == null) return choices
    return choices + selected
        .filterNot { selectedValue ->
            choices.any { choice -> choice.first == selectedValue }
        }
        .map { selectedValue -> selectedValue to staleLabel(selectedValue) }
}

@Composable
internal fun RuleEditorChoiceCard(
    title: String,
    summary: String,
    choices: List<RuleEditorChoice>,
    selectedValue: String,
    onSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    missingLabel: (String) -> String = { value -> value },
) {
    var expanded by remember { mutableStateOf(false) }
    val interactionEnabled = ruleEditorChoiceInteractionEnabled(choices, enabled)
    val selected = resolveRuleEditorChoice(choices, selectedValue, missingLabel)
    val labelMotion = AsteriskMotion.fastEffects<Float>()
    Box {
        Card(
            onClick = { expanded = true },
            modifier = modifier.fillMaxWidth(),
            enabled = interactionEnabled,
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
            ),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 14.dp)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    if (summary.isNotBlank()) {
                        Text(
                            summary,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                AnimatedContent(
                    targetState = selected.label,
                    modifier = Modifier.widthIn(max = 160.dp),
                    transitionSpec = AsteriskMotion.fadeThrough(labelMotion),
                    label = "rule-editor-choice-label",
                ) { label ->
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                AsteriskDropdownAnchor(
                    expanded = expanded && interactionEnabled,
                    onDismissRequest = { expanded = false },
                    menuModifier = Modifier.widthIn(min = 220.dp, max = 320.dp),
                ) {
                    choices.forEach { choice ->
                        AsteriskDropdownMenuItem(
                            text = choice.label,
                            selected = choice.value == selectedValue,
                            onClick = {
                                expanded = false
                                onSelected(choice.value)
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun RuleEditorSwitchCard(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    AsteriskExpressiveCard(
        onClick = { onCheckedChange(!checked) },
        modifier = Modifier.fillMaxWidth(),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleSmall,
            )
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
    }
}

@Composable
internal fun RuleEditorSectionTitle(text: String, topPadding: Dp = 8.dp) {
    Text(
        text = text,
        modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = topPadding),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
    )
}

@Composable
internal fun RuleEditorTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    errorText: String?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    sanitizeInput: (String) -> String = { it },
) {
    val focusManager = LocalFocusManager.current
    OutlinedTextField(
        value = value,
        onValueChange = { proposed -> onValueChange(sanitizeInput(proposed)) },
        modifier = modifier.fillMaxWidth(),
        label = { AsteriskTextFieldLabel(label) },
        singleLine = true,
        enabled = enabled,
        isError = errorText != null,
        supportingText = errorText?.let { message ->
            { Text(message) }
        },
        keyboardOptions = keyboardOptions,
        keyboardActions = KeyboardActions(
            onNext = { focusManager.moveFocus(FocusDirection.Down) },
        ),
    )
}

@Composable
internal fun <T> RuleEditorChipGroupCard(
    title: String,
    choices: List<Pair<T, String>>,
    selected: Set<T>,
    onToggle: (T) -> Unit,
    staleLabel: ((T) -> String)? = null,
) {
    val visibleChoices = ruleEditorChipChoices(choices, selected, staleLabel)
    Card(
        modifier = Modifier.fillMaxWidth(),
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
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                visibleChoices.forEach { (value, label) ->
                    AsteriskFilterChip(
                        selected = value in selected,
                        onClick = { onToggle(value) },
                        label = label,
                    )
                }
            }
        }
    }
}
