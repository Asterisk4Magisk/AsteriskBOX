// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.settings

import engine.singbox.SingBoxLogLevels

internal enum class SettingsCoreItem {
    DnsManagement,
    Sniffer,
    Outbounds,
    AppManagement,
    Resources,
    Selectors,
    Endpoints,
    Routing,
    LogLevel,
}

internal val SettingsCoreItems = listOf(
    SettingsCoreItem.DnsManagement,
    SettingsCoreItem.Sniffer,
    SettingsCoreItem.Outbounds,
    SettingsCoreItem.AppManagement,
    SettingsCoreItem.Resources,
    SettingsCoreItem.Endpoints,
    SettingsCoreItem.Selectors,
    SettingsCoreItem.Routing,
    SettingsCoreItem.LogLevel,
)

internal val SettingsCoreLogLevelOptions = SingBoxLogLevels
