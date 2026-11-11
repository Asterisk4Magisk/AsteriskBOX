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

// Bottom navigation bar layout: the full-width glass bar or the compact
// circle-pill bar with icon-only tabs.
const val NavigationBarStyleClassic = 0
const val NavigationBarStyleCompact = 1

fun normalizeNavigationBarStyle(value: Int): Int = when (value) {
    NavigationBarStyleClassic, NavigationBarStyleCompact -> value
    else -> NavigationBarStyleClassic
}

