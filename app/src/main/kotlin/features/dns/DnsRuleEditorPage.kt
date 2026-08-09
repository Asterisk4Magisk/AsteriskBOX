// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.dns

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
import engine.singbox.config.sanitized
import engine.singbox.config.validateSingBoxRuntimeConfiguration
import features.logs.FailureLogContext
import features.logs.reportFailure
import features.resources.runtime.singBoxRuleSetFiles
import features.settings.sheets.DnsRuleEditorScaffold
import features.settings.sheets.DnsRuleEditorState
import features.settings.sheets.dnsServerTypeLabel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.asterisk.zcc.abox.R
import ui.components.managedInboundChoices
import java.util.UUID

@Composable
internal fun DnsRuleEditorPage(
    padding: PaddingValues,
    ruleId: Int,
    initialDraft: SingBoxDnsRuleState?,
    resultKey: String,
    nested: Boolean,
    topLevelRuleId: Int,
) {
    val appState by LocalAppStateStore.current.collectAppState()
    val updateAppState = LocalUpdateAppState.current
    val navigator = LocalNavigator.current
    val context = LocalContext.current
    val tipNotifier = LocalAppServices.current.tipNotifier
    val scope = rememberCoroutineScope()
    val isWideScreen = LocalIsWideScreen.current
    val stored = appState.dnsRules.firstOrNull { rule -> rule.id == ruleId }
    val serverChoices = appState.dnsServers.map { server ->
        server.tag to server.remarks.ifBlank { dnsServerTypeLabel(server.type) }
    }
    val serverTags = serverChoices.map { choice -> choice.first }
    val initial = initialDraft ?: stored ?: SingBoxDnsRuleState(
        id = appState.nextAvailableDnsRuleId(),
        server = appState.dnsFinal
            .takeIf(serverTags::contains)
            ?: serverTags.firstOrNull().orEmpty(),
    )
    var editorRuleJson by rememberSaveable(ruleId, initialDraft, resultKey) {
        mutableStateOf(encodeDnsRule(initial))
    }
    val editorSessionId = rememberSaveable { UUID.randomUUID().toString() }
    val editorRule = remember(editorRuleJson) { decodeDnsRule(editorRuleJson) }
    var childSession by rememberSaveable { mutableIntStateOf(0) }
    var childResultKey by rememberSaveable { mutableStateOf<String?>(null) }
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
    val evaluationOwnerId = if (nested) topLevelRuleId else ruleId
    val editorIndex = if (evaluationOwnerId == 0) {
        null
    } else {
        appState.dnsRules.indexOfFirst { rule -> rule.id == evaluationOwnerId }
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

    LaunchedEffect(ruleId, stored, nested) {
        if (!nested && ruleId != 0 && stored == null) {
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
        val saved = navigator.observeResult<SingBoxDnsRuleState>(key).first()
        editorRuleJson = encodeDnsRule(
            decodeDnsRule(editorRuleJson).withSavedLogicalRule(saved),
        )
        navigator.clearResult(key)
        childResultKey = null
    }

    fun editChild(
        parent: SingBoxDnsRuleState,
        child: SingBoxDnsRuleState,
    ) {
        editorRuleJson = encodeDnsRule(parent)
        childSession += 1
        val key = "dns-rule:$editorSessionId:${parent.id}:child:${child.id}:$childSession"
        childResultKey = key
        navigator.navigateForResult(
            app.navigation.Route.DnsRuleEdit(
                initialDraft = child,
                resultKey = key,
                nested = true,
                topLevelRuleId = evaluationOwnerId,
            ),
            key,
        )
    }

    fun save(saved: SingBoxDnsRuleState) {
        if (saving) return
        if (nested) {
            if (resultKey.isNotBlank()) {
                saving = true
                navigator.setResult(resultKey, saved.sanitized())
            }
            return
        }
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

    DnsRuleEditorScaffold(
        outerPadding = padding,
        isWideScreen = isWideScreen,
        editor = DnsRuleEditorState(
            index = editorIndex,
            rule = editorRule,
        ),
        serverChoices = serverChoices,
        inboundChoices = inboundChoices,
        preferredByChoices = preferredByChoices,
        matchResponseChoices = matchResponseChoices,
        ruleSetChoices = ruleSetChoices,
        saving = saving,
        onEditorChange = { editorRuleJson = encodeDnsRule(it) },
        onDismissRequest = navigator::pop,
        onSave = ::save,
        onEditChild = ::editChild,
        nested = nested,
    )
}

private fun encodeDnsRule(rule: SingBoxDnsRuleState): String =
    Json.encodeToString(SingBoxDnsRuleState.serializer(), rule)

private fun decodeDnsRule(encoded: String): SingBoxDnsRuleState =
    Json.decodeFromString(SingBoxDnsRuleState.serializer(), encoded)
