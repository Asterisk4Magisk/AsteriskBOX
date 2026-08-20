// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package engine.root.mode

import engine.proxy.toLocalProxyOptions
import engine.root.config.RootConfigBuildContext
import engine.root.config.RootModeStartConfig
import engine.root.config.bpf2SocksBridgePortValue
import engine.root.config.buildAsteriskdConfig
import engine.root.config.tun2SocksInternalProxyPortValue
import engine.root.daemon.config.AsteriskdBpf2SocksHelper
import engine.root.daemon.config.AsteriskdMode
import engine.root.daemon.config.AsteriskdModeOptions

private const val RootBpf2SocksListenAddress = "0.0.0.0"
private const val RootBpf2SocksSocksInboundAddress = "127.0.0.1"

internal fun RootConfigBuildContext.buildBpf2SocksStartConfig(): RootModeStartConfig {
    val appState = appState
    val socksPort = appState.tun2SocksInternalProxyPortValue()
    val rootStartConfig = buildRootStartConfig()
    val iptablesConfig = buildRootIptablesConfig().copy(enableEbpfRules = true)
    return RootModeStartConfig(
        root = rootStartConfig,
        localProxyOptions = appState.toLocalProxyOptions(),
        asteriskdConfig = rootStartConfig.buildAsteriskdConfig(
            mode = AsteriskdMode.Bpf2Socks,
            iptablesConfig = iptablesConfig,
            virtualInterfaces = emptyList(),
            modeOptions = AsteriskdModeOptions(transparentPort = null, tunnelName = null),
            helper = AsteriskdBpf2SocksHelper(
                executablePath = rootStartConfig.runtimePaths.bpf2SocksExecutablePath,
                bridgeListenAddress = RootBpf2SocksListenAddress,
                bridgePort = appState.bpf2SocksBridgePortValue(),
                socksHost = RootBpf2SocksSocksInboundAddress,
                socksPort = socksPort,
            ),
        ),
    )
}
