// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.settings

import engine.singbox.SingBoxLogLevels

internal enum class SettingsGeneralItem {
    OutboundGroups,
    Resources,
}

internal val SettingsGeneralItems = listOf(
    SettingsGeneralItem.OutboundGroups,
    SettingsGeneralItem.Resources,
)

internal enum class SettingsCoreItem {
    Dns,
    DnsRules,
    Sniffer,
    Outbounds,
    Selectors,
    Endpoints,
    Routing,
    LogLevel,
}

internal val SettingsCoreItems = listOf(
    SettingsCoreItem.Dns,
    SettingsCoreItem.DnsRules,
    SettingsCoreItem.Sniffer,
    SettingsCoreItem.Outbounds,
    SettingsCoreItem.Selectors,
    SettingsCoreItem.Endpoints,
    SettingsCoreItem.Routing,
    SettingsCoreItem.LogLevel,
)

internal val SettingsCoreLogLevelOptions = SingBoxLogLevels
