// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package features.singbox

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import features.settings.SettingsSectionCard
import features.settings.SettingsSwitchRow
import ui.components.IconAccent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.LocalAppServices
import app.LocalAppStateStore
import app.LocalIsWideScreen
import app.LocalNavigator
import app.LocalUpdateAppState
import app.R
import app.collectAppState
import engine.singbox.config.SingBoxConfigCompiler
import engine.singbox.config.SingBoxScriptResult
import engine.singbox.config.debugSingBoxConfigOverride
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ui.clipboard.setPlainText
import ui.components.AsteriskScaffold
import ui.components.AsteriskTopAppBar
import ui.layout.pageContentPaddingWithCutout
import ui.theme.AsteriskShapeTokens
import ui.icons.AsteriskIcons as Icons

@Composable
internal fun SingBoxOverrideScriptPage(padding: PaddingValues) {
    val appState by LocalAppStateStore.current.collectAppState()
    val updateAppState = LocalUpdateAppState.current
    val navigator = LocalNavigator.current
    val services = LocalAppServices.current
    val context = LocalContext.current.applicationContext
    val clipboard = LocalClipboard.current
    val isWideScreen = LocalIsWideScreen.current
    val scope = rememberCoroutineScope()
    val editorState = remember { SingBoxCodeEditorState(appState.configOverrideScript) }
    var scriptEnabled by remember { mutableStateOf(appState.enableConfigOverrideScript) }
    var debugging by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<SingBoxScriptResult?>(null) }
    val copiedMessage = stringResource(R.string.common_copied)

    AsteriskScaffold(
        topBar = {
            AsteriskTopAppBar(
                title = { Text(stringResource(R.string.singbox_override_script_title)) },
                navigationIcon = {
                    IconButton(onClick = navigator::pop) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.common_back))
                    }
                },
                actions = {
                    TextButton(
                        enabled = !debugging,
                        onClick = {
                            val script = editorState.snapshotText()
                            val snapshot = appState
                            debugging = true
                            scope.launch {
                                try {
                                    result = withContext(Dispatchers.Default) {
                                        debugSingBoxConfigOverride(
                                            SingBoxConfigCompiler.generate(context, snapshot), script,
                                        )
                                    }
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (error: Exception) {
                                    result = SingBoxScriptResult(error = error.message ?: error.toString())
                                } finally {
                                    debugging = false
                                }
                            }
                        },
                    ) {
                        if (debugging) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Rounded.BugReport, contentDescription = null)
                        }
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(if (debugging) R.string.singbox_override_script_debug_running else R.string.singbox_override_script_debug_run))
                    }
                    TextButton(onClick = {
                        val script = editorState.snapshotText()
                        updateAppState { state ->
                            state.copy(configOverrideScript = script, enableConfigOverrideScript = scriptEnabled)
                        }
                        navigator.pop()
                    }) {
                        Icon(Icons.Rounded.Save, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.common_save))
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier.fillMaxSize()
                .padding(pageContentPaddingWithCutout(innerPadding, padding, isWideScreen))
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .imePadding(),
        ) {
            SettingsSectionCard {
                SettingsSwitchRow(
                    title = stringResource(R.string.singbox_override_script_enable),
                    icon = Icons.Rounded.Code,
                    checked = scriptEnabled,
                    onCheckedChange = { scriptEnabled = it },
                    accent = IconAccent.MaskPurple,
                )
            }
            JavaScriptCodeEditor(
                state = editorState,
                modifier = Modifier.fillMaxWidth().weight(1f),
            )
        }
        SingBoxOverrideScriptDebugDialog(
            result = result,
            onDismissRequest = { result = null },
            onCopy = { report ->
                scope.launch {
                    clipboard.setPlainText(report.toClipboardReport())
                    services.tipNotifier.show(copiedMessage)
                }
            },
        )
    }
}

@Composable
private fun JavaScriptCodeEditor(state: SingBoxCodeEditorState, modifier: Modifier = Modifier) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val tipNotifier = LocalAppServices.current.tipNotifier
    var formatting by remember { mutableStateOf(false) }
    val failed = stringResource(R.string.code_editor_format_failed)
    val changed = stringResource(R.string.code_editor_format_content_changed)
    Box(modifier) {
        SoraCodeEditor(state, SingBoxCodeLanguage.JavaScript, readOnly = false, modifier = Modifier.fillMaxSize())
        Surface(
            modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp),
            shape = AsteriskShapeTokens.InnerContainer,
            color = MaterialTheme.colorScheme.primaryContainer,
            tonalElevation = 3.dp,
        ) {
            IconButton(
                enabled = !formatting,
                onClick = {
                    val source = state.snapshotText()
                    formatting = true
                    scope.launch {
                        try {
                            val formatted = formatSingBoxScript(context, source)
                            if (source == state.snapshotText()) state.replaceText(formatted)
                            else tipNotifier.show(changed)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            tipNotifier.show(failed)
                        } finally {
                            formatting = false
                        }
                    }
                },
            ) {
                if (formatting) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Icon(Icons.Rounded.AutoFixHigh, stringResource(R.string.code_editor_format_javascript))
            }
        }
    }
}
