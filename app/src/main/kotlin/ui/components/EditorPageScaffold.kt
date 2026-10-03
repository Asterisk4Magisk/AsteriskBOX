// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
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
internal fun EditorPageScaffold(
    outerPadding: PaddingValues,
    isWideScreen: Boolean,
    title: @Composable () -> Unit,
    saving: Boolean,
    saveEnabled: Boolean,
    onBack: () -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
    topExtra: Dp = 8.dp,
    actions: @Composable RowScope.() -> Unit = {},
    searchQuery: String? = null,
    onSearchQueryChange: (String) -> Unit = {},
    searchPlaceholder: String = "",
    searchField: @Composable ((Modifier) -> Unit)? = null,
    content: @Composable (PaddingValues) -> Unit,
) {
    val state = EditorPageScaffoldState(saving, saveEnabled)

    BackHandler(enabled = saving) {}

    AsteriskScaffold(
        modifier = modifier,
        topBar = {
            val navigationIcon: @Composable () -> Unit = {
                IconButton(
                    onClick = onBack,
                    enabled = state.backEnabled,
                ) {
                    Icon(
                        Icons.AutoMirrored.Rounded.ArrowBack,
                        stringResource(R.string.common_back),
                    )
                }
            }
            val editorActions: @Composable RowScope.() -> Unit = {
                actions()
                AsteriskActionButton(
                    text = stringResource(R.string.common_save),
                    icon = Icons.Rounded.Save,
                    onClick = onSave,
                    enabled = state.saveEnabled,
                    loading = saving,
                )
            }
            if (searchQuery != null) {
                AsteriskSearchTopAppBar(
                    query = searchQuery,
                    onQueryChange = onSearchQueryChange,
                    placeholder = searchPlaceholder,
                    title = title,
                    navigationIcon = navigationIcon,
                    actions = editorActions,
                    searchAvailable = !saving,
                    searchField = { fieldModifier ->
                        if (searchField != null) {
                            searchField(fieldModifier)
                        } else {
                            AsteriskSearchField(
                                query = searchQuery,
                                onQueryChange = onSearchQueryChange,
                                placeholder = searchPlaceholder,
                                clearContentDescription = stringResource(R.string.common_clear),
                                modifier = fieldModifier,
                                highlightContainerOnFocus = false,
                            )
                        }
                    },
                )
            } else {
                AsteriskTopAppBar(
                    title = title,
                    navigationIcon = navigationIcon,
                    actions = editorActions,
                )
            }
        },
    ) { innerPadding ->
        content(
            pageListPadding(
                pageContentPaddingWithCutout(
                    innerPadding = innerPadding,
                    outerPadding = outerPadding,
                    isWideScreen = isWideScreen,
                ),
                topExtra = topExtra,
            ),
        )
    }
}
