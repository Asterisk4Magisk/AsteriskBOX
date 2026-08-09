// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.settings.sheets

import app.SingBoxDnsRuleState
import ui.components.RuleEditorChoice

internal const val DnsCustomResponseCodeChoice = "dns-custom-response-code"

internal data class DnsPredefinedResponseState(
    val custom: Boolean,
    val rcode: String,
)

internal fun dnsServerEditorChoices(
    choices: List<Pair<String, String>>,
): List<RuleEditorChoice> = choices.map { (value, label) ->
    RuleEditorChoice(value, label)
}

internal fun selectDnsRuleServer(
    rule: SingBoxDnsRuleState,
    selectedValue: String,
    choices: List<Pair<String, String>>,
): SingBoxDnsRuleState = if (choices.any { choice -> choice.first == selectedValue }) {
    rule.copy(server = selectedValue)
} else {
    rule
}

internal fun initialDnsPredefinedResponseState(
    rcode: String,
    responseCodes: List<String>,
): DnsPredefinedResponseState = DnsPredefinedResponseState(
    custom = rcode !in responseCodes,
    rcode = rcode,
)

internal fun selectDnsPredefinedResponseCode(
    state: DnsPredefinedResponseState,
    selectedValue: String,
    responseCodes: List<String>,
): DnsPredefinedResponseState {
    if (selectedValue == DnsCustomResponseCodeChoice) {
        return DnsPredefinedResponseState(
            custom = true,
            rcode = state.rcode.takeUnless(responseCodes::contains).orEmpty(),
        )
    }
    require(selectedValue in responseCodes) {
        "DNS predefined response code must be a known choice"
    }
    return DnsPredefinedResponseState(custom = false, rcode = selectedValue)
}
