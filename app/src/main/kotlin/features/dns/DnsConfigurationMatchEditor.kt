// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.dns

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.R
import app.SingBoxDnsServerState
import engine.singbox.config.DnsConfigurationServerTypes
import engine.singbox.config.dnsConfigurationEntryTag
import engine.singbox.config.isDnsConfigurationEntry
import ui.components.StringListEditor
import ui.components.ReferenceSelectionCard
import ui.theme.AsteriskMotion

internal fun dnsConfigurationSelectionNeedsValues(
    selectedTags: Set<String>,
    values: List<String>,
): Boolean = selectedTags.any { tag ->
    values.none { entry -> dnsConfigurationEntryTag(entry) == tag }
}

@Composable
internal fun DnsConfigurationMatchEditor(
    editorKey: Any,
    field: String,
    title: String,
    dnsServers: List<SingBoxDnsServerState>,
    values: List<String>,
    onValuesChange: (List<String>) -> Unit,
    onPendingChange: (Boolean) -> Unit,
) {
    val servers = dnsServers.filter { it.type in DnsConfigurationServerTypes }
    val availableTags = servers.map { it.tag }.toSet()
    var selectedServers by rememberSaveable(editorKey, field) {
        mutableStateOf(values.map(::dnsConfigurationEntryTag).distinct())
    }
    val selectedTags = (selectedServers + values.map(::dnsConfigurationEntryTag)).toSet()
    val tags = (servers.map { it.tag } + selectedTags).distinct()
    val pending = remember(editorKey, field) { mutableStateMapOf<String, Boolean>() }
    val invalid = stringResource(R.string.settings_dns_rule_value_invalid)
    val unavailable = stringResource(R.string.common_unavailable)
    val unnamed = stringResource(R.string.settings_dns_server_fallback)
    val labels = dnsServers.associate { it.tag to it.remarks.ifBlank { unnamed } }
    val currentOnPendingChange by rememberUpdatedState(onPendingChange)
    val hasInvalidValues = values.any {
        dnsConfigurationEntryTag(it) !in availableTags || !isDnsConfigurationEntry(field, it)
    }
    val hasPending = selectedTags.any { pending[it] == true } || hasInvalidValues ||
        dnsConfigurationSelectionNeedsValues(selectedTags, values)
    LaunchedEffect(hasPending) { currentOnPendingChange(hasPending) }
    Column {
        ReferenceSelectionCard(
            title = title,
            emptyText = stringResource(R.string.settings_dns_configuration_servers_empty),
            choices = servers.map { it.tag to labels.getValue(it.tag) },
            selected = selectedTags,
            onToggle = { tag ->
                if (tag in selectedTags) {
                    selectedServers = selectedTags.filterNot { it == tag }
                    pending.remove(tag)
                    onValuesChange(values.filterNot { dnsConfigurationEntryTag(it) == tag })
                } else {
                    selectedServers = selectedTags.toList() + tag
                }
            },
            staleLabel = { tag -> labels[tag]?.let { "$it · $unavailable" } ?: unavailable },
        )
        tags.forEach { tag ->
            key(tag) {
                AnimatedVisibility(
                    visible = tag in selectedTags,
                    enter = AsteriskMotion.contentEnter(),
                    exit = AsteriskMotion.contentExit(),
                ) {
                    val entries = values.filter { dnsConfigurationEntryTag(it) == tag }
                    StringListEditor(
                        editorKey = "$editorKey:$field:$tag",
                        title = labels[tag] ?: unavailable,
                        modifier = Modifier.padding(top = 14.dp),
                        description = stringResource(
                            if (field == "dns_server_address") R.string.settings_dns_server_address_match_summary
                            else R.string.settings_dns_search_domain_match_summary,
                        ),
                        values = entries.flatMap { it.substringAfter('=', "").split(',') }
                            .map(String::trim).filter(String::isNotEmpty),
                        onValuesChange = { updated ->
                            selectedServers = (selectedTags + tag).toList()
                            onValuesChange(values.filterNot { dnsConfigurationEntryTag(it) == tag } +
                                updated.map { "$tag=$it" })
                        },
                        emptyText = stringResource(R.string.settings_dns_configuration_values_required),
                        validateInput = { input ->
                            if (tag in availableTags && isDnsConfigurationEntry(field, "$tag=$input")) null
                            else invalid
                        },
                        onPendingChange = { pending[tag] = it },
                        horizontalPadding = 0.dp,
                    )
                }
            }
        }
    }
}
