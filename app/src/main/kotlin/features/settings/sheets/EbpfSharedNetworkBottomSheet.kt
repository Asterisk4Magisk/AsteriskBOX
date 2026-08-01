// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.settings.sheets

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.asterisk.zcc.abox.R
import ui.components.StringListEditor
import ui.icons.AsteriskIcons as Icons
import ui.text.formatTemplate
import utils.toTrimmedNonEmptyDistinctList

internal fun List<String>.sanitizeEbpfSharedNetworkInterfaces(): List<String> {
    return toTrimmedNonEmptyDistinctList()
}

@Composable
internal fun ebpfSharedNetworkInterfacesSummary(interfaces: List<String>): String {
    val values = interfaces.sanitizeEbpfSharedNetworkInterfaces()
    if (values.isEmpty()) {
        return stringResource(R.string.settings_ebpf_shared_network_none)
    }
    return stringResource(R.string.settings_ebpf_shared_network_selected)
        .formatTemplate("count" to values.size)
}

@Composable
internal fun EbpfSharedNetworkBottomSheet(
    show: Boolean,
    interfaces: List<String>,
    onInterfacesChange: (List<String>) -> Unit,
    onDismissRequest: () -> Unit,
    onSave: (List<String>) -> Unit,
) {
    val normalizedInterfaces = interfaces.sanitizeEbpfSharedNetworkInterfaces()
    SettingsModalBottomSheet(
        show = show,
        title = stringResource(R.string.settings_ebpf_shared_network),
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
                onClick = { onSave(normalizedInterfaces) },
            )
        },
        onDismissRequest = onDismissRequest,
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 24.dp),
        ) {
            item {
                StringListEditor(
                    editorKey = "ebpf-shared-network:$show",
                    title = stringResource(R.string.settings_ebpf_shared_network_input),
                    description = stringResource(R.string.settings_ebpf_shared_network_description),
                    values = normalizedInterfaces,
                    onValuesChange = { values ->
                        onInterfacesChange(values.sanitizeEbpfSharedNetworkInterfaces())
                    },
                    emptyText = stringResource(R.string.settings_ebpf_shared_network_empty),
                )
            }
        }
    }
}
