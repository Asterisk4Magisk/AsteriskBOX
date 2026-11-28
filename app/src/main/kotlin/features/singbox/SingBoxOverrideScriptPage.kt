// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.singbox

import androidx.compose.foundation.layout.consumeWindowInsets
import features.settings.SettingsSectionCard
import features.settings.SettingsSwitchRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
import ui.components.AsteriskCodeActionButton
import ui.components.AsteriskCodeSearchHost
import ui.components.rememberAsteriskCodeSearchState
import ui.components.AsteriskScaffold
import ui.components.AsteriskTopAppBar
import ui.layout.pageContentPaddingWithCutout
import ui.layout.CodeEditorFocusLayout
import ui.layout.rememberCodeEditorFocusState
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

    val codeSearch = rememberAsteriskCodeSearchState()
    val editorFocus = rememberCodeEditorFocusState(
        editorFocused = editorState.isFocused,
        clearEditorFocus = codeSearch::clearEditorFocus,
        searchActive = codeSearch.active,
    )
    AsteriskCodeSearchHost(
        state = codeSearch,
        modifier = Modifier.fillMaxSize(),
        enabled = true,
        editorTools = { searchButton ->
            searchButton()
            JavaScriptCodeFormatButton(editorState)
        },
    ) { _, editorModifier, backdrop ->
        AsteriskScaffold(
            backdrop = backdrop,
            topBar = {
                AsteriskTopAppBar(
                    title = { Text(stringResource(R.string.singbox_override_script_title)) },
                    navigationIcon = {
                        IconButton(
                            onClick = {
                                when {
                                    codeSearch.active -> codeSearch.close()
                                    editorFocus.expanded -> editorFocus.exit()
                                    else -> navigator.pop()
                                }
                            },
                        ) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.common_back))
                        }
                    },
                    actions = {
                        AsteriskCodeActionButton(
                            icon = Icons.Rounded.BugReport,
                            label = stringResource(
                                if (debugging) R.string.singbox_override_script_debug_running
                                else R.string.singbox_override_script_debug_run,
                            ),
                            loading = debugging,
                            onClick = {
                                val script = editorState.snapshotText()
                                val snapshot = appState
                                debugging = true
                                scope.launch {
                                    result = try {
                                        withContext(Dispatchers.Default) {
                                            debugSingBoxConfigOverride(
                                                SingBoxConfigCompiler.generate(context, snapshot), script, snapshot.pluginProtocols,
                                            )
                                        }
                                    } catch (cancelled: CancellationException) {
                                        throw cancelled
                                    } catch (error: Exception) {
                                        SingBoxScriptResult(error = error.message ?: error.toString())
                                    } finally {
                                        debugging = false
                                    }
                                }
                            },
                        )
                        AsteriskCodeActionButton(
                            icon = Icons.Rounded.Save,
                            label = stringResource(R.string.common_save),
                            onClick = {
                                val script = editorState.snapshotText()
                                updateAppState { state ->
                                    state.copy(configOverrideScript = script, enableConfigOverrideScript = scriptEnabled)
                                }
                                navigator.pop()
                            },
                        )
                    },
                )
            },
        ) { innerPadding ->
            val contentPadding = pageContentPaddingWithCutout(innerPadding, padding, isWideScreen)
            CodeEditorFocusLayout(
                expanded = editorFocus.expanded,
                modifier = Modifier.fillMaxSize()
                    .padding(contentPadding)
                    .consumeWindowInsets(contentPadding)
                    .padding(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 12.dp),
                supportingContent = {
                    SettingsSectionCard {
                        SettingsSwitchRow(
                            title = stringResource(R.string.singbox_override_script_enable),
                            icon = Icons.Rounded.Code,
                            checked = scriptEnabled,
                            onCheckedChange = { scriptEnabled = it },
                        )
                    }
                },
            ) { modifier ->
                SoraCodeEditor(
                    state = editorState,
                    language = SingBoxCodeLanguage.JavaScript,
                    readOnly = false,
                    searchState = codeSearch,
                    modifier = modifier.then(editorModifier),
                )
            }
            SingBoxOverrideScriptDebugDialog(
                result = result,
                onDismissRequest = { result = null },
                onCopy = { report ->
                    scope.launch {
                        clipboard.setPlainText(report)
                        services.tipNotifier.show(copiedMessage)
                    }
                },
            )
        }
    }
}

@Composable
private fun JavaScriptCodeFormatButton(state: SingBoxCodeEditorState) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val tipNotifier = LocalAppServices.current.tipNotifier
    var formatting by remember { mutableStateOf(false) }
    val failed = stringResource(R.string.code_editor_format_failed)
    val changed = stringResource(R.string.code_editor_format_content_changed)
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
