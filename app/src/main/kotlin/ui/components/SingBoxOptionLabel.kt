// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.ManagedSingBoxTagPrefix
import org.asterisk.zcc.abox.R

@Composable
internal fun singBoxOptionLabel(
    label: String,
    rawValue: String,
): String {
    val normalizedValue = rawValue.trim()
    return if (
        normalizedValue.isEmpty() ||
        label.equals(normalizedValue, ignoreCase = true) ||
        normalizedValue.startsWith(ManagedSingBoxTagPrefix)
    ) {
        label
    } else {
        stringResource(R.string.sing_box_option_with_value, label, normalizedValue)
    }
}
