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
    DnsManagement,
    Sniffer,
    Outbounds,
    Selectors,
    Endpoints,
    Routing,
    LogLevel,
}

internal val SettingsCoreItems = listOf(
    SettingsCoreItem.DnsManagement,
    SettingsCoreItem.Sniffer,
    SettingsCoreItem.Outbounds,
    SettingsCoreItem.Endpoints,
    SettingsCoreItem.Selectors,
    SettingsCoreItem.Routing,
    SettingsCoreItem.LogLevel,
)

internal val SettingsCoreLogLevelOptions = SingBoxLogLevels
