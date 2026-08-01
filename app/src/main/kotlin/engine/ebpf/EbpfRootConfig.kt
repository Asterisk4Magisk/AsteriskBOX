// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package engine.ebpf

import engine.proxy.LocalProxyOptions
import engine.proxy.toLocalProxyOptions
import engine.root.AsteriskdConfig
import engine.root.AsteriskdMode
import engine.root.RootConfigBuildContext
import engine.root.RootIptablesConfig
import engine.root.RootModeStartConfig
import engine.root.RootStartConfig
import engine.root.bpf2SocksBridgePortValue
import engine.root.buildAsteriskdConfig

internal data class EbpfStartConfig(
    override val root: RootStartConfig,
    override val localProxyOptions: LocalProxyOptions?,
    val listenPort: Int,
    val sharedNetworkInterfaces: List<String>,
    override val asteriskdConfig: AsteriskdConfig,
) : RootModeStartConfig

internal fun RootConfigBuildContext.buildEbpfStartConfig(): EbpfStartConfig {
    val appState = this.appState
    val rootStartConfig = buildRootStartConfig()
    val asteriskdSettings = EbpfAsteriskdBaseConfig
    return EbpfStartConfig(
        root = rootStartConfig,
        localProxyOptions = appState.toLocalProxyOptions(),
        listenPort = appState.bpf2SocksBridgePortValue(),
        sharedNetworkInterfaces = normalizeEbpfSharedNetworkInterfaces(
            appState.ebpfSharedNetworkInterfaces,
        ),
        asteriskdConfig = rootStartConfig.buildAsteriskdConfig(
            mode = AsteriskdMode.Ebpf,
            iptablesConfig = asteriskdSettings,
            virtualInterfaces = emptyList(),
        ),
    )
}

private val EbpfAsteriskdBaseConfig = RootIptablesConfig(
    mark = "",
    ipv4Table = "",
    ipv6Table = "",
)
