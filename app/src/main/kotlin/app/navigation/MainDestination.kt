// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package app.navigation

internal enum class MainDestination(
    val id: String,
) {
    Home("home"),
    Proxies("proxies"),
    Groups("groups"),
    Settings("settings"),
    ;

    val index: Int
        get() = ordinal
}
