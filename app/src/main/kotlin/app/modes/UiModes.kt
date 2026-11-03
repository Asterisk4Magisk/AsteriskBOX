// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package app.modes

const val ColorModeSystem = 0
const val ColorModeLight = 1
const val ColorModeDark = 2

fun normalizeColorMode(value: Int): Int = when (value) {
    ColorModeSystem, ColorModeLight, ColorModeDark -> value
    else -> ColorModeSystem
}

// How the home "network activity" card draws its history.
const val NetworkActivityStyleDotted = 0
const val NetworkActivityStyleLines = 1

fun normalizeNetworkActivityStyle(value: Int): Int = when (value) {
    NetworkActivityStyleDotted, NetworkActivityStyleLines -> value
    else -> NetworkActivityStyleDotted
}

const val LanguageModeSystem = 0
const val LanguageModeEnglish = 1
const val LanguageModeSimplifiedChinese = 2

