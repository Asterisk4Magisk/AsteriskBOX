// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.R
import ui.layout.pageContentPaddingWithCutout
import ui.layout.pageListPadding
import ui.icons.AsteriskIcons as Icons

internal data class EditorPageScaffoldState(
    val saving: Boolean,
    val requestedSaveEnabled: Boolean,
) {
    val backEnabled: Boolean get() = !saving
    val saveEnabled: Boolean get() = requestedSaveEnabled && !saving
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun EditorPageScaffold(
    outerPadding: PaddingValues,
    isWideScreen: Boolean,
    title: @Composable () -> Unit,
    saving: Boolean,
    saveEnabled: Boolean,
    onBack: () -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    val state = EditorPageScaffoldState(saving, saveEnabled)

    BackHandler(enabled = saving) {}

    AsteriskScaffold(
        modifier = modifier,
        topBar = {
            AsteriskTopAppBar(
                title = title,
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        enabled = state.backEnabled,
                    ) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowBack,
                            stringResource(R.string.common_back),
                        )
                    }
                },
                actions = {
                    actions()
                    AsteriskActionButton(
                        text = stringResource(R.string.common_save),
                        icon = Icons.Rounded.Save,
                        onClick = onSave,
                        enabled = state.saveEnabled,
                        loading = saving,
                    )
                },
            )
        },
    ) { innerPadding ->
        content(
            pageListPadding(
                pageContentPaddingWithCutout(
                    innerPadding = innerPadding,
                    outerPadding = outerPadding,
                    isWideScreen = isWideScreen,
                ),
            ),
        )
    }
}
