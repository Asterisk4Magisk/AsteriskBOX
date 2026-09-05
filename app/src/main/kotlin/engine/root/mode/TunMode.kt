// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package engine.root.mode

import engine.proxy.toLocalProxyOptions
import engine.root.config.RootConfigBuildContext
import engine.root.config.RootIptablesConfig
import engine.root.config.RootModeStartConfig
import engine.root.config.buildAsteriskdConfig
import engine.root.daemon.config.AsteriskdMode
import engine.root.daemon.config.AsteriskdModeOptions
import engine.singbox.config.SingBoxTunDevice

internal fun RootConfigBuildContext.buildTunStartConfig(): RootModeStartConfig {
    val appState = this.appState
    val rootStartConfig = buildRootStartConfig()
    return RootModeStartConfig(
        root = rootStartConfig,
        localProxyOptions = appState.toLocalProxyOptions(),
        asteriskdConfig = rootStartConfig.buildAsteriskdConfig(
            mode = AsteriskdMode.Tun,
            iptablesConfig = RootIptablesConfig(
                externalInterfacePrefixes = appState.tunSharedNetworkInterfaces
                    .map(String::trim).filter { it.isNotEmpty() && it != "lo" }.distinct(),
            ),
            virtualInterfaces = emptyList(),
            modeOptions = AsteriskdModeOptions(
                transparentPort = null,
                tunnelName = SingBoxTunDevice,
            ),
        ),
    )
}
