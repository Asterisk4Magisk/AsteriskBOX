// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package ui.components

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.R
import ui.icons.AsteriskIcons as Icons

/**
 * The overflow menu of an editable list card: Edit, then [additionalItems], then Delete.
 * Choosing Edit or Delete dismisses the menu before running the action.
 */
@Composable
internal fun EditDeleteDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    showDividerBeforeDelete: Boolean = true,
    additionalItems: @Composable ColumnScope.() -> Unit = {},
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
    ) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.common_edit)) },
            leadingIcon = { Icon(Icons.Rounded.Edit, contentDescription = null) },
            onClick = {
                onDismissRequest()
                onEdit()
            },
        )
        additionalItems()
        if (showDividerBeforeDelete) {
            HorizontalDivider()
        }
        DropdownMenuItem(
            text = { Text(stringResource(R.string.common_delete)) },
            leadingIcon = { Icon(Icons.Rounded.Delete, contentDescription = null) },
            onClick = {
                onDismissRequest()
                onDelete()
            },
        )
    }
}
