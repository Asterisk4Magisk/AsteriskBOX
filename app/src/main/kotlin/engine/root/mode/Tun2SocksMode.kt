// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package engine.root.mode

import engine.proxy.toLocalProxyOptions
import engine.root.daemon.config.AsteriskdHevSocks5TunnelHelper
import engine.root.daemon.config.AsteriskdMode
import engine.root.daemon.config.AsteriskdModeOptions
import engine.root.config.RootConfigBuildContext
import engine.root.config.RootIptablesConfig
import engine.root.config.RootModeStartConfig
import engine.root.config.buildAsteriskdConfig
import engine.root.config.tun2SocksInternalProxyPortValue
import engine.vpn.toTunOptions

internal val Tun2SocksBaseIptablesConfig = RootIptablesConfig(
    mark = Tun2SocksFwmark,
    ipv4Table = Tun2SocksRouteTable,
    ipv6Table = Tun2SocksRouteTable,
)

internal fun RootConfigBuildContext.buildTun2SocksStartConfig(): RootModeStartConfig {
    val appState = appState
    val tunOptions = appState.toTunOptions()
    val socksPort = appState.tun2SocksInternalProxyPortValue()
    val rootStartConfig = buildRootStartConfig()
    val iptablesConfig = buildRootIptablesConfig(Tun2SocksBaseIptablesConfig)
    return RootModeStartConfig(
        root = rootStartConfig,
        localProxyOptions = appState.toLocalProxyOptions(),
        asteriskdConfig = rootStartConfig.buildAsteriskdConfig(
            mode = AsteriskdMode.Tun2Socks,
            iptablesConfig = iptablesConfig,
            virtualInterfaces = listOf("asterisk0"),
            modeOptions = AsteriskdModeOptions(transparentPort = null, tunnelName = null),
            helper = AsteriskdHevSocks5TunnelHelper(
                executablePath = rootStartConfig.runtimePaths.hevSocks5TunnelExecutablePath,
                socksHost = Tun2SocksListenAddress,
                socksPort = socksPort,
                tunnelName = "asterisk0",
                mtu = tunOptions.mtu,
                ipv4Address = tunOptions.ipv4Address.address,
                ipv6Address = tunOptions.ipv6Address.address.takeIf { appState.enableIpv6 },
                multiQueue = true,
                tcpFastOpen = true,
            ),
        ),
    )
}

internal const val Tun2SocksListenAddress = "127.0.0.1"
internal const val DefaultTun2SocksProxyPort = 65534
private const val Tun2SocksFwmark = "0x20000000/0x60000000"
private const val Tun2SocksRouteTable = "168"
