// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.routing

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import app.LocalAppServices
import app.LocalAppStateStore
import app.LocalIsWideScreen
import app.LocalNavigator
import app.LocalUpdateAppState
import app.SingBoxRouteRuleState
import app.collectAppState
import app.managedInboundTags
import app.managedRuleSetChoices
import app.nextAvailableRouteRuleId
import app.navigation.Route
import engine.singbox.config.APP_GLOBAL_SELECTOR
import engine.singbox.config.validateSingBoxRuntimeConfiguration
import features.logs.FailureLogContext
import features.logs.reportFailure
import features.resources.runtime.singBoxRuleSetFiles
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import app.R
import ui.components.EditorPagePane
import ui.components.managedInboundChoices
import ui.layout.EditorListItem
import java.util.UUID

@Composable
internal fun RouteRuleEditorPage(
    padding: PaddingValues,
    ruleId: Int,
    initialDraft: SingBoxRouteRuleState?,
    resultKey: String,
    nested: Boolean,
) {
    val appState by LocalAppStateStore.current.collectAppState()
    val updateAppState = LocalUpdateAppState.current
    val navigator = LocalNavigator.current
    val context = LocalContext.current
    val tipNotifier = LocalAppServices.current.tipNotifier
    val scope = rememberCoroutineScope()
    val isWideScreen = LocalIsWideScreen.current
    val focusManager = LocalFocusManager.current
    var activeRuleId by rememberSaveable(ruleId) { mutableIntStateOf(ruleId) }
    val stored = appState.routeRules.firstOrNull { rule -> rule.id == activeRuleId }
    val initial = initialDraft ?: stored ?: SingBoxRouteRuleState(
        id = appState.nextAvailableRouteRuleId(),
        outbound = APP_GLOBAL_SELECTOR,
    )
    var editorRuleJson by rememberSaveable(activeRuleId, initialDraft, resultKey) {
        mutableStateOf(encodeRouteRule(initial))
    }
    val editorSessionId = rememberSaveable { UUID.randomUUID().toString() }
    val editorRule = remember(editorRuleJson) { decodeRouteRule(editorRuleJson) }
    var childSession by rememberSaveable { mutableIntStateOf(0) }
    var childResultKey by rememberSaveable { mutableStateOf<String?>(null) }
    var saving by remember(activeRuleId, resultKey) { mutableStateOf(false) }
    val saveFailedMessage = stringResource(R.string.routing_save_failed)
    val outboundChoices = managedOutboundChoices(appState)
    val inboundChoices = managedInboundChoices(managedInboundTags(appState))
    val ruleSetChoices = remember(appState.customResourceFiles) {
        appState.managedRuleSetChoices(
            context.singBoxRuleSetFiles(appState.customResourceFiles).map { file -> file.name },
        ).map { choice -> choice.tag to choice.remarks }
    }

    LaunchedEffect(activeRuleId, stored, nested) {
        if (!nested && activeRuleId != 0 && stored == null) {
            tipNotifier.show(saveFailedMessage)
            navigator.pop()
        }
    }

    LaunchedEffect(childResultKey) {
        val key = childResultKey ?: return@LaunchedEffect
        if (!navigator.hasResultRequest(key)) {
            childResultKey = null
            return@LaunchedEffect
        }
        val saved = navigator.observeResult<SingBoxRouteRuleState>(key).first()
        editorRuleJson = encodeRouteRule(
            decodeRouteRule(editorRuleJson).withSavedLogicalRule(saved),
        )
        navigator.clearResult(key)
        childResultKey = null
    }

    fun editChild(
        parent: SingBoxRouteRuleState,
        child: SingBoxRouteRuleState,
    ) {
        editorRuleJson = encodeRouteRule(parent)
        childSession += 1
        val key = "route-rule:$editorSessionId:${parent.id}:child:${child.id}:$childSession"
        childResultKey = key
        navigator.navigateForResult(
            Route.RouteRuleEdit(
                initialDraft = child,
                resultKey = key,
                nested = true,
            ),
            key,
        )
    }

    fun save(saved: SingBoxRouteRuleState) {
        if (saving) return
        if (nested) {
            if (resultKey.isNotBlank()) {
                saving = true
                navigator.setResult(resultKey, saved.sanitized())
            }
            return
        }
        val baseState = appState
        if (activeRuleId != 0 && baseState.routeRules.none { rule -> rule.id == activeRuleId }) {
            scope.launch { tipNotifier.show(saveFailedMessage) }
            return
        }
        val candidateState = baseState.withSavedRouteRule(
            rule = saved,
            isNew = activeRuleId == 0,
        )
        saving = true
        scope.launch {
            try {
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
                } else {
                    tipNotifier.show(saveFailedMessage)
                }
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                reportFailure(
                    context = FailureLogContext(
                        operation = "save_route_rule",
                        stage = "validate",
                    ),
                    error = error,
                )
                tipNotifier.show(saveFailedMessage)
            } finally {
                saving = false
            }
        }
    }

    val unnamedRule = stringResource(R.string.routing_edit_rule)
    RouteRuleEditorScaffold(
        outerPadding = padding,
        isWideScreen = isWideScreen,
        rule = editorRule,
        outboundChoices = outboundChoices,
        inboundChoices = inboundChoices,
        ruleSetChoices = ruleSetChoices,
        saving = saving,
        onDismiss = navigator::pop,
        onSave = ::save,
        onEditChild = ::editChild,
        nested = nested,
        editorPane = if (nested) null else EditorPagePane(
            title = stringResource(R.string.routing_title),
            entries = appState.routeRules.mapIndexed { index, rule ->
                EditorListItem(rule.id, rule.remarks.ifBlank { "$unnamedRule ${index + 1}" })
            },
            selectedId = activeRuleId,
            selectionEnabled = editorRule == initial,
            onSelect = { selectedId ->
                if (selectedId != activeRuleId) {
                    focusManager.clearFocus(force = true)
                    activeRuleId = selectedId
                }
            },
        ),
    )
}

private fun encodeRouteRule(rule: SingBoxRouteRuleState): String =
    Json.encodeToString(SingBoxRouteRuleState.serializer(), rule)

private fun decodeRouteRule(encoded: String): SingBoxRouteRuleState =
    Json.decodeFromString(SingBoxRouteRuleState.serializer(), encoded)
