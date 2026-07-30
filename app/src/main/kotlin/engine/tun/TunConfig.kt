// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package engine.tun

import app.AppState
import engine.singbox.SingBoxConfigFactory
import engine.proxy.LocalProxyOptions
import engine.proxy.toLocalProxyOptions
import engine.root.RootConfigBuildContext
import engine.root.AsteriskdConfig
import engine.root.AsteriskdMode
import engine.root.RootEbpfRuntimeConfig
import engine.root.RootIptablesConfig
import engine.root.RootModeStartConfig
import engine.root.RootStartConfig
import engine.root.AsteriskdBypassConsumerChains
import engine.root.buildAsteriskdConfig
import engine.tun2socks.Tun2SocksBaseIptablesConfig
import engine.tun2socks.Tun2SocksOutput6Chain
import engine.tun2socks.Tun2SocksOutputChain
import engine.tun2socks.Tun2SocksPrerouting6Chain
import engine.tun2socks.Tun2SocksPreroutingChain
import engine.vpn.TunOptions
import engine.vpn.toTunOptions

const val SingBoxTunDevice = "asterisk0"

internal data class TunStartConfig(
    override val root: RootStartConfig,
    override val localProxyOptions: LocalProxyOptions?,
    val tunConfig: SingBoxTunConfig,
    val iptablesConfig: RootIptablesConfig,
    override val asteriskdConfig: AsteriskdConfig,
    override val rootEbpfConfig: RootEbpfRuntimeConfig?,
) : RootModeStartConfig

internal data class SingBoxTunConfig(
    val device: String,
    val stack: String,
    val mtu: Int,
    val ipv4Address: String?,
    val ipv6Address: String?,
)

internal val TunBaseIptablesConfig = Tun2SocksBaseIptablesConfig

internal fun RootConfigBuildContext.buildTunStartConfig(): TunStartConfig {
    val appState = this.appState
    val rootStartConfig = buildRootStartConfig()
    val iptablesConfig = buildRootIptablesConfig(TunBaseIptablesConfig)
    val tunConfig = appState.buildSingBoxTunConfig(appState.toTunOptions())
    return TunStartConfig(
        root = rootStartConfig,
        localProxyOptions = appState.toLocalProxyOptions(),
        tunConfig = tunConfig,
        iptablesConfig = iptablesConfig,
        asteriskdConfig = rootStartConfig.buildAsteriskdConfig(
            mode = AsteriskdMode.Tun,
            iptablesConfig = iptablesConfig,
            virtualInterfaces = listOf(tunConfig.device),
            bypassConsumerChains = AsteriskdBypassConsumerChains(
                ipv4 = listOf(Tun2SocksPreroutingChain, Tun2SocksOutputChain),
                ipv6 = listOf(Tun2SocksPrerouting6Chain, Tun2SocksOutput6Chain),
            ),
        ),
        rootEbpfConfig = buildRootEbpfRuntimeConfig(iptablesConfig),
    )
}

private fun AppState.buildSingBoxTunConfig(tunOptions: TunOptions): SingBoxTunConfig {
    return SingBoxTunConfig(
        device = SingBoxTunDevice,
        stack = SingBoxConfigFactory.tunStack(this),
        mtu = tunOptions.mtu,
        ipv4Address = "${tunOptions.ipv4Address.address}/${tunOptions.ipv4Address.prefixLength}",
        ipv6Address = if (enableIpv6) {
            "${tunOptions.ipv6Address.address}/${tunOptions.ipv6Address.prefixLength}"
        } else {
            null
        },
    )
}
