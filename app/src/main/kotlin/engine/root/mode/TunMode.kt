// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package engine.root.mode

import app.AppState
import engine.singbox.SingBoxConfigFactory
import engine.proxy.LocalProxyOptions
import engine.proxy.toLocalProxyOptions
import engine.root.config.RootConfigBuildContext
import engine.root.daemon.config.AsteriskdConfig
import engine.root.daemon.config.AsteriskdMode
import engine.root.daemon.config.AsteriskdModeOptions
import engine.root.config.RootIptablesConfig
import engine.root.config.RootModeStartConfig
import engine.root.config.RootStartConfig
import engine.root.config.buildAsteriskdConfig
import engine.vpn.TunOptions
import engine.vpn.toTunOptions
import engine.singbox.config.SingBoxTunDevice

internal data class SingBoxTunConfig(
    val device: String,
    val stack: String,
    val mtu: Int,
    val ipv4Address: String?,
    val ipv6Address: String?,
)

internal val TunBaseIptablesConfig = Tun2SocksBaseIptablesConfig

internal fun RootConfigBuildContext.buildTunStartConfig(): RootModeStartConfig {
    val appState = this.appState
    val rootStartConfig = buildRootStartConfig()
    val iptablesConfig = buildRootIptablesConfig(TunBaseIptablesConfig)
    val tunConfig = appState.buildSingBoxTunConfig(appState.toTunOptions())
    return RootModeStartConfig(
        root = rootStartConfig,
        localProxyOptions = appState.toLocalProxyOptions(),
        asteriskdConfig = rootStartConfig.buildAsteriskdConfig(
            mode = AsteriskdMode.Tun,
            iptablesConfig = iptablesConfig,
            virtualInterfaces = listOf(tunConfig.device),
            modeOptions = AsteriskdModeOptions(
                transparentPort = null,
                tunnelName = tunConfig.device,
            ),
        ),
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
