// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.settings

import engine.singbox.SingBoxLogLevels

internal enum class SettingsToolsItem {
    ConfigPreview,
    NetworkQualityTest,
}

internal val SettingsToolsItems = listOf(
    SettingsToolsItem.ConfigPreview,
    SettingsToolsItem.NetworkQualityTest,
)

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
    SettingsCoreItem.Resources,
    SettingsCoreItem.DnsManagement,
    SettingsCoreItem.Sniffer,
    SettingsCoreItem.Outbounds,
    SettingsCoreItem.Endpoints,
    SettingsCoreItem.Selectors,
    SettingsCoreItem.Routing,
    SettingsCoreItem.AppManagement,
    SettingsCoreItem.LogLevel,
)

internal val SettingsCoreLogLevelOptions = SingBoxLogLevels
