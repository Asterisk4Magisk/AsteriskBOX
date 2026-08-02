// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.settings.sheets

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.KeyboardActionHandler
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.byValue
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.then
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ui.theme.AsteriskShapeTokens
import engine.network.toPortOrNull
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import ui.components.StringListStatusText

private val SettingsSheetHorizontalPadding = 16.dp

@Composable
internal fun SettingsSheetContent(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 24.dp),
        content = content,
    )
}

@Composable
internal fun SettingsTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    errorText: String?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    keyboardOptions: KeyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
    sanitizeInput: (String) -> String = { it },
    horizontalPadding: Dp = SettingsSheetHorizontalPadding,
) {
    SheetTextField(
        value = value,
        onValueChange = onValueChange,
        label = label,
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = if (errorText == null) 12.dp else 4.dp),
        keyboardOptions = keyboardOptions,
        sanitizeInput = sanitizeInput,
        enabled = enabled,
        horizontalPadding = horizontalPadding,
    )
    errorText?.let {
        StringListStatusText(
            text = it,
            error = true,
            modifier = Modifier
                .padding(horizontal = horizontalPadding)
                .padding(bottom = 8.dp),
        )
    }
}

@Composable
internal fun SheetTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    keyboardOptions: KeyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
    sanitizeInput: (String) -> String = { it },
    horizontalPadding: Dp = SettingsSheetHorizontalPadding,
) {
    val latestOnValueChange by rememberUpdatedState(onValueChange)
    val latestSanitizeInput by rememberUpdatedState(sanitizeInput)
    val inputTransformation = remember {
        InputTransformation
            .byValue { _, proposed -> latestSanitizeInput(proposed.toString()) }
            .then {
                latestOnValueChange(asCharSequence().toString())
            }
    }
    val focusManager = LocalFocusManager.current

    OutlinedTextField(
        label = { Text(label) },
        state = rememberTextFieldState(initialText = value),
        lineLimits = TextFieldLineLimits.SingleLine,
        shape = AsteriskShapeTokens.InnerContainer,
        inputTransformation = inputTransformation,
        keyboardOptions = keyboardOptions,
        onKeyboardAction = KeyboardActionHandler {
            focusManager.moveFocus(FocusDirection.Down)
        },
        enabled = enabled,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = horizontalPadding),
    )
}

internal fun sanitizeFiveDigitInput(value: String): String {
    return value.filter(Char::isDigit).take(5)
}

internal fun fiveDigitKeyboardOptions(): KeyboardOptions {
    return KeyboardOptions(
        keyboardType = KeyboardType.Number,
        imeAction = ImeAction.Next,
    )
}

internal fun isPort(value: String): Boolean {
    return value.toPortOrNull() != null
}
