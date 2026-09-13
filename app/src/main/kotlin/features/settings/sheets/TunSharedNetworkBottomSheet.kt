// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.settings.sheets

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import engine.singbox.config.isSingBoxSharedNetworkInterface
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.R
import engine.singbox.EbpfDnsModes
import engine.singbox.EbpfSharedDataPlanes
import features.settings.SettingsSearchProvider
import ui.components.StringListEditor
import ui.icons.AsteriskIcons as Icons
import ui.text.formatTemplate
import utils.toTrimmedNonEmptyDistinctList

internal fun List<String>.sanitizeTunSharedNetworkInterfaces(): List<String> {
    return toTrimmedNonEmptyDistinctList().filterNot { it == "lo" }
}

@Composable
internal fun tunSharedNetworkInterfacesSummary(interfaces: List<String>): String {
    val values = interfaces.sanitizeTunSharedNetworkInterfaces()
    if (values.isEmpty()) {
        return stringResource(R.string.settings_tun_shared_network_none)
    }
    return stringResource(R.string.settings_tun_shared_network_selected)
        .formatTemplate("count" to values.size)
}

@Composable
internal fun TunSharedNetworkBottomSheet(
    show: Boolean,
    showEbpfOptions: Boolean,
    enableLocalDns: Boolean,
    ebpfSharedDataPlane: String,
    ebpfSharedDnsMode: String,
    onEbpfSharedDataPlaneChange: (String) -> Unit,
    onEbpfSharedDnsModeChange: (String) -> Unit,
    interfaces: List<String>,
    onInterfacesChange: (List<String>) -> Unit,
    onDismissRequest: () -> Unit,
    onSave: (List<String>, String, String) -> Unit,
) {
    var editorPending by remember(show) { mutableStateOf(false) }
    val invalidMessage = stringResource(R.string.settings_tun_shared_network_invalid)
    val normalizedInterfaces = interfaces.sanitizeTunSharedNetworkInterfaces()
    val canSave = !editorPending && normalizedInterfaces.all(::isSingBoxSharedNetworkInterface) &&
        (!showEbpfOptions || (ebpfSharedDataPlane in EbpfSharedDataPlanes && ebpfSharedDnsMode in EbpfDnsModes))
    SettingsModalBottomSheet(
        show = show,
        title = stringResource(R.string.settings_tun_shared_network),
        startAction = {
            TextButton(
                text = stringResource(R.string.common_cancel),
                icon = Icons.Rounded.Close,
                onClick = onDismissRequest,
            )
        },
        endAction = {
            TextButton(
                text = stringResource(R.string.common_save),
                icon = Icons.Rounded.Save,
                onClick = { onSave(normalizedInterfaces, ebpfSharedDataPlane, ebpfSharedDnsMode) },
                enabled = canSave,
            )
        },
        onDismissRequest = onDismissRequest,
    ) {
        SettingsSearchProvider("") {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 24.dp),
            ) {
                if (showEbpfOptions) {
                    item("ebpf-data-plane") {
                        WindowDropdownPreference(
                            title = stringResource(R.string.settings_ebpf_data_plane),
                            icon = Icons.Rounded.AccountTree,
                            items = EbpfSharedDataPlanes,
                            selectedIndex = EbpfSharedDataPlanes.indexOf(ebpfSharedDataPlane).coerceAtLeast(0),
                            summary = stringResource(R.string.settings_ebpf_shared_data_plane_summary),
                            onSelectedIndexChange = { index ->
                                EbpfSharedDataPlanes.getOrNull(index)?.let(onEbpfSharedDataPlaneChange)
                            },
                        )
                    }
                    item("ebpf-dns-mode") {
                        WindowDropdownPreference(
                            title = stringResource(R.string.settings_ebpf_dns_mode),
                            icon = Icons.Rounded.Dns,
                            items = EbpfDnsModes,
                            selectedIndex = EbpfDnsModes.indexOf(ebpfSharedDnsMode).coerceAtLeast(0),
                            summary = stringResource(
                                if (enableLocalDns) R.string.settings_ebpf_shared_dns_mode_summary
                                else R.string.settings_ebpf_dns_disabled,
                            ),
                            onSelectedIndexChange = { index ->
                                EbpfDnsModes.getOrNull(index)?.let(onEbpfSharedDnsModeChange)
                            },
                        )
                    }
                }
                item {
                    StringListEditor(
                        editorKey = "tun-shared-network:$show",
                        title = stringResource(R.string.settings_tun_shared_network_input),
                        description = stringResource(R.string.settings_tun_shared_network_description),
                        values = normalizedInterfaces,
                        onValuesChange = { values ->
                            onInterfacesChange(values.sanitizeTunSharedNetworkInterfaces())
                        },
                        emptyText = stringResource(R.string.settings_tun_shared_network_empty),
                        validateInput = { value ->
                            if (isSingBoxSharedNetworkInterface(value)) null else invalidMessage
                        },
                        onPendingChange = { editorPending = it },
                    )
                }
            }
        }
    }
}
