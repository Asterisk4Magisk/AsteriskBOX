// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.asterisk.zcc.abox.R
import ui.layout.pageContentPaddingWithCutout
import ui.layout.pageListPadding
import ui.theme.AsteriskMotion
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
    val saveEffectsMotion = AsteriskMotion.fastEffects<Float>()

    BackHandler(enabled = saving) {}

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
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
                    IconButton(
                        onClick = onSave,
                        enabled = state.saveEnabled,
                    ) {
                        AnimatedContent(
                            targetState = saving,
                            transitionSpec = {
                                fadeIn(saveEffectsMotion)
                                    .togetherWith(fadeOut(saveEffectsMotion))
                            },
                            label = "editor-save-progress",
                        ) { inProgress ->
                            if (inProgress) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp,
                                )
                            } else {
                                Icon(
                                    Icons.Rounded.Save,
                                    stringResource(R.string.common_save),
                                )
                            }
                        }
                    }
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
