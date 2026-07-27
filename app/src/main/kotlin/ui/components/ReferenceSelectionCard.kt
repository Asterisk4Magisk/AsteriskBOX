// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package ui.components

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
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import engine.singbox.config.APP_LOCAL_INBOUND
import engine.singbox.config.APP_ROOT_INBOUND
import engine.singbox.config.APP_TUN_INBOUND
import engine.singbox.SingBoxSnifferProtocols
import org.asterisk.zcc.abox.R

@Composable
internal fun <T> ReferenceSelectionCard(
    title: String,
    emptyText: String,
    choices: List<Pair<T, String>>,
    selected: Set<T>,
    onToggle: (T) -> Unit,
    modifier: Modifier = Modifier,
    staleLabel: ((T) -> String)? = null,
) {
    val unavailableLabel = stringResource(R.string.common_unavailable)
    val selectableChoices = choices + selected
        .filterNot { selectedValue -> choices.any { choice -> choice.first == selectedValue } }
        .map { selectedValue ->
            selectedValue to (staleLabel?.invoke(selectedValue) ?: unavailableLabel)
        }
    Card(
        modifier = modifier.fillMaxWidth(),
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
            if (selectableChoices.isEmpty()) {
                Text(
                    text = emptyText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 10.dp),
                )
            } else {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    selectableChoices.forEach { (value, label) ->
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
}

@Composable
internal fun managedInboundChoices(tags: List<String>): List<Pair<String, String>> =
    tags.map { tag ->
        val label = stringResource(
            when (tag) {
                APP_TUN_INBOUND -> R.string.managed_inbound_tun
                APP_ROOT_INBOUND -> R.string.managed_inbound_root
                else -> R.string.managed_inbound_local
            },
        )
        tag to label
    }

@Composable
internal fun singBoxProtocolChoices(): List<Pair<String, String>> =
    SingBoxSnifferProtocols.map { protocol ->
        val label = stringResource(
            when (protocol) {
                "http" -> R.string.settings_sniffer_protocol_http
                "tls" -> R.string.settings_sniffer_protocol_tls
                "quic" -> R.string.settings_sniffer_protocol_quic
                "stun" -> R.string.settings_sniffer_protocol_stun
                "dns" -> R.string.settings_sniffer_protocol_dns
                "bittorrent" -> R.string.settings_sniffer_protocol_bittorrent
                "dtls" -> R.string.settings_sniffer_protocol_dtls
                "ssh" -> R.string.settings_sniffer_protocol_ssh
                "rdp" -> R.string.settings_sniffer_protocol_rdp
                else -> R.string.settings_sniffer_protocol_ntp
            },
        )
        protocol to singBoxOptionLabel(label, protocol)
    }
