// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package engine.root.mode

import engine.proxy.toLocalProxyOptions
import engine.root.daemon.config.AsteriskdMode
import engine.root.daemon.config.AsteriskdModeOptions
import engine.root.config.RootConfigBuildContext
import engine.root.config.RootIptablesConfig
import engine.root.config.RootModeStartConfig
import engine.root.config.buildAsteriskdConfig

internal fun RootConfigBuildContext.buildEbpfStartConfig(): RootModeStartConfig {
    val appState = appState
    val rootStartConfig = buildRootStartConfig()
    return RootModeStartConfig(
        root = rootStartConfig,
        localProxyOptions = appState.toLocalProxyOptions(),
        asteriskdConfig = rootStartConfig.buildAsteriskdConfig(
            mode = AsteriskdMode.Ebpf,
            iptablesConfig = EbpfAsteriskdBaseConfig,
            virtualInterfaces = emptyList(),
            modeOptions = AsteriskdModeOptions(transparentPort = null, tunnelName = null),
        ),
    )
}

private val EbpfAsteriskdBaseConfig = RootIptablesConfig(
    mark = "",
    ipv4Table = "",
    ipv6Table = "",
)
