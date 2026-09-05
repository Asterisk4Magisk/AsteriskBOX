// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.settings.sheets

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.asterisk.zcc.abox.R
import ui.components.ReferenceSelectionCard
import ui.icons.AsteriskIcons as Icons
import ui.text.formatTemplate

internal fun sanitizeTunBypassRuleSetTags(tags: List<String>): List<String> =
    tags.map(String::trim).filter(String::isNotEmpty).distinct()

internal fun toggleTunBypassRuleSetTag(
    tags: List<String>,
    tag: String,
): List<String> =
    if (tag in tags) tags.filterNot { value -> value == tag } else tags + tag

@Composable
internal fun tunBypassRuleSetSummary(
    selectedTags: List<String>,
    choices: List<Pair<String, String>>,
): String {
    val selected = sanitizeTunBypassRuleSetTags(selectedTags)
    if (selected.isEmpty()) {
        return stringResource(R.string.settings_tun_bypass_rule_sets_summary_none)
    }
    val labels = choices.toMap()
    val unavailable = stringResource(R.string.common_unavailable)
    val selectedLabels = selected.map { tag -> labels[tag] ?: unavailable }
    return stringResource(R.string.settings_tun_bypass_rule_sets_summary_selected)
        .formatTemplate("ruleSets" to selectedLabels.joinToString())
}

@Composable
internal fun TunBypassRuleSetBottomSheet(
    show: Boolean,
    saving: Boolean,
    choices: List<Pair<String, String>>,
    selectedTags: List<String>,
    onSelectedTagsChange: (List<String>) -> Unit,
    onDismissRequest: () -> Unit,
    onSave: (List<String>) -> Unit,
) {
    val selected = sanitizeTunBypassRuleSetTags(selectedTags)
    SettingsModalBottomSheet(
        show = show,
        dismissEnabled = !saving,
        title = stringResource(R.string.settings_root_ebpf_bypass_direct_cidrs),
        startAction = {
            TextButton(
                text = stringResource(R.string.common_cancel),
                icon = Icons.Rounded.Close,
                onClick = onDismissRequest,
                enabled = !saving,
            )
        },
        endAction = {
            TextButton(
                text = stringResource(R.string.common_save),
                icon = Icons.Rounded.Save,
                onClick = { onSave(selected) },
                enabled = !saving,
            )
        },
        onDismissRequest = onDismissRequest,
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
        ) {
            item {
                ReferenceSelectionCard(
                    title = stringResource(R.string.settings_tun_bypass_rule_sets_picker_title),
                    emptyText = stringResource(R.string.routing_rule_sets_empty),
                    choices = choices,
                    selected = selected.toSet(),
                    onToggle = { tag ->
                        onSelectedTagsChange(toggleTunBypassRuleSetTag(selected, tag))
                    },
                    enabled = !saving,
                    modifier = Modifier.padding(start = 16.dp, top = 12.dp, end = 16.dp),
                )
            }
            item {
                Text(
                    text = stringResource(R.string.settings_tun_bypass_rule_sets_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
    }
}
