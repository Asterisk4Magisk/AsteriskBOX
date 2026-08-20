// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package engine.root.mode

import app.AppState
import engine.proxy.toLocalProxyOptions
import engine.network.toPortOrNull
import engine.network.NetworkLimits
import engine.root.config.RootConfigBuildContext
import engine.root.daemon.config.AsteriskdMode
import engine.root.daemon.config.AsteriskdModeOptions
import engine.root.config.RootIptablesConfig
import engine.root.config.RootModeStartConfig
import engine.root.config.buildAsteriskdConfig

internal val TproxyBaseIptablesConfig = RootIptablesConfig(
    mark = TproxyFwmark,
    ipv4Table = TproxyRouteTable,
    ipv6Table = TproxyRouteTable,
)

internal fun RootConfigBuildContext.buildTproxyStartConfig(): RootModeStartConfig {
    val appState = this.appState
    val tproxyPort = appState.tproxyPortValue()
    val rootStartConfig = buildRootStartConfig()
    val iptablesConfig = buildRootIptablesConfig(TproxyBaseIptablesConfig)
    return RootModeStartConfig(
        root = rootStartConfig,
        localProxyOptions = appState.toLocalProxyOptions(),
        asteriskdConfig = rootStartConfig.buildAsteriskdConfig(
            mode = AsteriskdMode.Tproxy,
            iptablesConfig = iptablesConfig,
            virtualInterfaces = emptyList(),
            modeOptions = AsteriskdModeOptions(
                transparentPort = tproxyPort,
                tunnelName = null,
            ),
        ),
    )
}

internal const val DefaultTproxyPort = NetworkLimits.PORT_MAX
private const val TproxyFwmark = "0x20000000/0x60000000"
private const val TproxyRouteTable = "160"

private fun AppState.tproxyPortValue(): Int {
    return transparentProxyPort.toPortOrNull() ?: DefaultTproxyPort
}
