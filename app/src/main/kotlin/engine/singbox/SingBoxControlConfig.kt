// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package engine.singbox

import app.AppState
import engine.network.findAvailableTcpPort
import engine.network.isTcpPortAvailable
import engine.network.toPortOrNull
import engine.root.RootModeEngine
import engine.vpn.VpnDefaults

internal const val SingBoxControlHost = "127.0.0.1"
internal const val DefaultSingBoxControlPort = 9090
internal const val DefaultSingBoxDelayTestUrl = "https://www.gstatic.com/generate_204"
internal const val DefaultSingBoxDelayTimeoutMillis = 5000

internal data class SingBoxControlConfig(
    val host: String = SingBoxControlHost,
    val port: Int = DefaultSingBoxControlPort,
    val secret: String = "",
    val scheme: String = "http",
) {
    val address: String
        get() = if (":" in host) "[$host]:$port" else "$host:$port"

    val baseUrl: String
        get() = "$scheme://$address"

    override fun toString(): String {
        return "SingBoxControlConfig(host=$host, port=$port, scheme=$scheme, secret=<redacted>)"
    }
}

internal fun AppState.singBoxControlConfig(): SingBoxControlConfig {
    return SingBoxControlConfig(
        port = singBoxControlPort.toPortOrNull() ?: DefaultSingBoxControlPort,
        secret = singBoxControlSecret.trim(),
    )
}

internal fun AppState.withResolvedSingBoxControlPort(): AppState {
    val configuredPort = singBoxControlPort.toPortOrNull()
    val excludedPorts = singBoxControlExcludedPorts()
    val resolvedPort = when {
        configuredPort != null &&
            configuredPort !in excludedPorts &&
            isTcpPortAvailable(SingBoxControlHost, configuredPort) -> configuredPort

        else -> availableSingBoxControlPort(excludedPorts) ?: configuredPort ?: DefaultSingBoxControlPort
    }
    val resolvedPortText = resolvedPort.toString()
    return if (singBoxControlPort == resolvedPortText) this else copy(singBoxControlPort = resolvedPortText)
}

private fun AppState.singBoxControlExcludedPorts(): Set<Int> {
    return buildSet {
        add(localProxyPort.toPortOrNull() ?: VpnDefaults.LOCAL_PROXY_PORT)
        add(transparentProxyPort.toPortOrNull() ?: RootModeEngine.DefaultTproxyPort)
        add(socks5ProxyPort.toPortOrNull() ?: RootModeEngine.DefaultTun2SocksProxyPort)
        add(bpf2SocksBridgePort.toPortOrNull() ?: RootModeEngine.DefaultBpf2SocksBridgePort)
    }
}

private fun availableSingBoxControlPort(excludedPorts: Set<Int>): Int? {
    return findAvailableTcpPort(
        listenAddress = SingBoxControlHost,
        excludedPorts = excludedPorts,
        attempts = RandomPortAttempts,
    )
}

private const val RandomPortAttempts = 32
