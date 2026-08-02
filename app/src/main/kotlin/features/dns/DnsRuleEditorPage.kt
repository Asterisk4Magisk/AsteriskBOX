// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.dns

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
import app.SingBoxDnsRuleState
import app.collectAppState
import app.managedInboundTags
import app.managedRuleSetChoices
import app.nextAvailableDnsRuleId
import app.selectablePreferredByDnsServers
import engine.singbox.config.validateSingBoxRuntimeConfiguration
import features.logs.FailureLogContext
import features.logs.reportFailure
import features.resources.runtime.singBoxRuleSetFiles
import features.settings.sheets.DnsRuleEditorScaffold
import features.settings.sheets.DnsRuleEditorState
import features.settings.sheets.dnsServerTypeLabel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.asterisk.zcc.abox.R
import ui.components.managedInboundChoices

@Composable
internal fun DnsRuleEditorPage(
    padding: PaddingValues,
    ruleId: Int,
) {
    val appState by LocalAppStateStore.current.collectAppState()
    val updateAppState = LocalUpdateAppState.current
    val navigator = LocalNavigator.current
    val context = LocalContext.current
    val tipNotifier = LocalAppServices.current.tipNotifier
    val scope = rememberCoroutineScope()
    val isWideScreen = LocalIsWideScreen.current
    val editing = appState.dnsRules.firstOrNull { rule -> rule.id == ruleId }
    val serverChoices = appState.dnsServers.map { server ->
        server.tag to server.remarks.ifBlank { dnsServerTypeLabel(server.type) }
    }
    val serverTags = serverChoices.map { choice -> choice.first }
    var draft by remember(ruleId, editing) {
        mutableStateOf(
            editing ?: SingBoxDnsRuleState(
                id = appState.nextAvailableDnsRuleId(),
                server = appState.dnsFinal
                    .takeIf(serverTags::contains)
                    ?: serverTags.firstOrNull().orEmpty(),
            ),
        )
    }
    var saving by remember(ruleId) { mutableStateOf(false) }
    val saveFailedMessage = stringResource(R.string.dns_rule_save_failed)
    val ruleSetChoices = remember(appState.customResourceFiles) {
        appState.managedRuleSetChoices(
            context.singBoxRuleSetFiles(appState.customResourceFiles).map { file -> file.name },
        ).map { choice -> choice.tag to choice.remarks }
    }
    val inboundChoices = managedInboundChoices(managedInboundTags(appState))
    val preferredByChoices = selectablePreferredByDnsServers(appState).map { choice ->
        choice.tag to choice.remarks.ifBlank {
            appState.dnsServers
                .firstOrNull { server -> server.tag == choice.tag }
                ?.let { server -> dnsServerTypeLabel(server.type) }
                .orEmpty()
        }
    }
    val editorIndex = if (ruleId == 0) {
        null
    } else {
        appState.dnsRules.indexOfFirst { rule -> rule.id == ruleId }
            .takeIf { index -> index >= 0 }
    }
    val matchResponseChoices = selectableDnsMatchResponseValues(
        rules = appState.dnsRules,
        currentIndex = editorIndex,
    ).mapIndexed { index, choice ->
        choice to choice.remarks.ifBlank {
            stringResource(R.string.dns_rule_evaluation_fallback, index + 1)
        }
    }

    LaunchedEffect(ruleId, editing) {
        if (ruleId != 0 && editing == null) {
            tipNotifier.show(saveFailedMessage)
            navigator.pop()
        }
    }

    fun save(saved: SingBoxDnsRuleState) {
        if (saving) return
        val baseState = appState
        if (ruleId != 0 && baseState.dnsRules.none { rule -> rule.id == ruleId }) {
            scope.launch { tipNotifier.show(saveFailedMessage) }
            return
        }
        val candidateState = baseState.withSavedDnsRule(
            rule = saved,
            isNew = ruleId == 0,
        )
        saving = true
        scope.launch {
            try {
                val committed = validateAndCommitDnsRuleState(
                    baseState = baseState,
                    candidateState = candidateState,
                    validate = { candidate ->
                        withContext(Dispatchers.IO) {
                            validateSingBoxRuntimeConfiguration(context, candidate)
                        }
                    },
                    commit = { expected, next ->
                        var didCommit = false
                        updateAppState { current ->
                            if (current === expected) {
                                didCommit = true
                                next
                            } else {
                                current
                            }
                        }
                        didCommit
                    },
                )
                if (committed) {
                    navigator.pop()
                } else {
                    tipNotifier.show(saveFailedMessage)
                }
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                reportFailure(
                    context = FailureLogContext(
                        operation = "save_dns_rule",
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

    BackHandler {
        if (!saving) navigator.pop()
    }

    DnsRuleEditorScaffold(
        outerPadding = padding,
        isWideScreen = isWideScreen,
        editor = DnsRuleEditorState(
            index = editorIndex,
            rule = draft,
        ),
        serverChoices = serverChoices,
        inboundChoices = inboundChoices,
        preferredByChoices = preferredByChoices,
        matchResponseChoices = matchResponseChoices,
        ruleSetChoices = ruleSetChoices,
        saving = saving,
        onEditorChange = { draft = it },
        onDismissRequest = navigator::pop,
        onSave = ::save,
    )
}
