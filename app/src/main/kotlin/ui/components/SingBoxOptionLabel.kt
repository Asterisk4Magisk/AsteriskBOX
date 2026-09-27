// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.isManagedSingBoxTag
import app.R

@Composable
internal fun singBoxOptionLabel(
    label: String,
    rawValue: String,
): String {
    val normalizedValue = rawValue.trim()
    return if (
        normalizedValue.isEmpty() ||
        label.optionLabelComparisonKey().equals(normalizedValue.optionLabelComparisonKey(), ignoreCase = true) ||
        isManagedSingBoxTag(normalizedValue)
    ) {
        label
    } else {
        stringResource(R.string.sing_box_option_with_value, label, normalizedValue)
    }
}

// Only known field nouns are singularized; protocol names such as HTTPS and SOCKS
// must not be treated as plurals by removing their trailing 's'.
private val OptionLabelSingularWords = mapOf(
    "addresses" to "address",
    "answers" to "answer",
    "bssids" to "bssid",
    "cidrs" to "cidr",
    "domains" to "domain",
    "ids" to "id",
    "inbounds" to "inbound",
    "interfaces" to "interface",
    "keywords" to "keyword",
    "names" to "name",
    "ports" to "port",
    "processes" to "process",
    "protocols" to "protocol",
    "ranges" to "range",
    "rules" to "rule",
    "servers" to "server",
    "sets" to "set",
    "ssids" to "ssid",
    "suffixes" to "suffix",
    "types" to "type",
    "users" to "user",
)

// Normalize words before removing separators so plural forms remain recognizable.
private fun String.optionLabelComparisonKey(): String =
    map { if (it.isWhitespace() || it == '_' || it == '-') ' ' else it }
        .joinToString("")
        .split(' ')
        .joinToString("") { OptionLabelSingularWords[it.lowercase()] ?: it }
