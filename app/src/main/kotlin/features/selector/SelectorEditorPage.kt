// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.selector

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.LocalAppServices
import app.LocalAppStateStore
import app.LocalIsWideScreen
import app.LocalNavigator
import app.LocalUpdateAppState
import app.SingBoxSelectorState
import app.collectAppState
import engine.singbox.config.validateSingBoxRuntimeConfiguration
import features.logs.FailureLogContext
import features.logs.reportFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.asterisk.zcc.abox.R

@Composable
internal fun SelectorEditorPage(
    padding: PaddingValues,
    selectorId: Int,
) {
    val appState by LocalAppStateStore.current.collectAppState()
    val updateAppState = LocalUpdateAppState.current
    val navigator = LocalNavigator.current
    val services = LocalAppServices.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val isWideScreen = LocalIsWideScreen.current
    val editing = appState.selectors.firstOrNull { selector -> selector.id == selectorId }
    val defaultRemarks = stringResource(R.string.selector_default_remarks)
    val initialSelector = remember(selectorId, editing, defaultRemarks) {
        editing ?: SingBoxSelectorState(
            id = 0,
            remarks = defaultRemarks,
            outbounds = emptyList(),
        )
    }
    var saving by remember(selectorId) { mutableStateOf(false) }
    val savedMessage = stringResource(R.string.selector_saved)
    val saveFailedMessage = stringResource(R.string.selector_save_failed)

    LaunchedEffect(selectorId, editing) {
        if (selectorId != 0 && editing == null) {
            services.tipNotifier.show(saveFailedMessage)
            navigator.pop()
        }
    }

    fun save(draft: SingBoxSelectorState) {
        if (saving) return
        val baseState = appState
        if (selectorId != 0 && baseState.selectors.none { selector -> selector.id == selectorId }) {
            scope.launch { services.tipNotifier.show(saveFailedMessage) }
            return
        }
        saving = true
        scope.launch {
            try {
                val candidateState = baseState.withSavedSelector(draft)
                withContext(Dispatchers.IO) {
                    validateSingBoxRuntimeConfiguration(context, candidateState)
                }
                var committed = false
                updateAppState { current ->
                    if (current === baseState) {
                        committed = true
                        candidateState
                    } else {
                        current
                    }
                }
                if (committed) {
                    navigator.pop()
                    services.tipNotifier.show(savedMessage)
                } else {
                    reportFailure(
                        FailureLogContext(
                            operation = "selector_save",
                            stage = "commit",
                        ),
                    )
                    services.tipNotifier.show(saveFailedMessage)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                services.tipNotifier.showError(
                    error,
                    saveFailedMessage,
                    FailureLogContext(operation = "selector_save"),
                )
            } finally {
                saving = false
            }
        }
    }

    BackHandler {
        if (!saving) navigator.pop()
    }

    SelectorEditorScaffold(
        outerPadding = padding,
        isWideScreen = isWideScreen,
        selector = initialSelector,
        state = appState,
        saving = saving,
        onDismissRequest = navigator::pop,
        onSave = ::save,
    )
}
