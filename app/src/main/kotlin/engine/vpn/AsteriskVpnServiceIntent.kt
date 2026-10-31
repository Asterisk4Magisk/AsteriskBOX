// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package engine.vpn

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.ResultReceiver
import app.modes.ProxyAppListModeGlobal
import engine.hevtun.DefaultHevSocks5TunnelLogLevel
import engine.hevtun.DefaultHevSocks5TunnelTcpReadWriteTimeoutMillis
import engine.hevtun.DefaultHevSocks5TunnelUdpReadWriteTimeoutMillis
import engine.hevtun.HevSocks5TunnelConfig
import engine.proxy.LocalProxyLoopbackAddress
import engine.proxy.LocalProxyOptions
import engine.singbox.SingBoxCoreLogPaths

internal object AsteriskVpnServiceIntents {
    const val ACTION_START = "app.action.START_VPN"
    const val ACTION_STOP = "app.action.STOP_VPN"
    const val ACTION_RELOAD = "app.action.RELOAD_VPN"

    /**
     * Starts the service without an embedded configuration. Used by the boot receiver and by
     * the system's always-on VPN start; both carry no configuration because the service replays
     * the one recorded by the last successful start (see [VpnStartConfigStore]).
     */
    const val ACTION_RESUME = "app.action.RESUME_VPN"

    fun resumeIntent(context: Context): Intent {
        return Intent(context, AsteriskVpnService::class.java).apply {
            action = ACTION_RESUME
        }
    }

    /**
     * The reply channel is installed by [AsteriskVpnService.awaitServiceCall] on the intent it
     * sends, because the service process is the only party that knows when a call is answered.
     */
    fun startIntent(context: Context, config: VpnServiceStartConfig): Intent {
        return Intent(context, AsteriskVpnService::class.java).apply {
            action = ACTION_START
            writeStartConfig(config)
        }
    }

    fun reloadIntent(context: Context): Intent {
        return Intent(context, AsteriskVpnService::class.java).apply {
            action = ACTION_RELOAD
        }
    }

    fun stopIntent(context: Context): Intent {
        return Intent(context, AsteriskVpnService::class.java).apply {
            action = ACTION_STOP
        }
    }
}

internal object AsteriskVpnServiceResults {
    const val CodeSuccess = 0
    const val CodeFailure = 1
}

private const val ExtraMessage = "message"
private const val ExtraResultReceiver = "service_result_receiver"

internal fun Intent.putServiceResultReceiver(receiver: ResultReceiver?) {
    if (receiver == null) {
        removeExtra(ExtraResultReceiver)
    } else {
        putExtra(ExtraResultReceiver, receiver)
    }
}

internal fun Intent.serviceResultReceiver(): ResultReceiver? {
    @Suppress("DEPRECATION")
    return getParcelableExtra(ExtraResultReceiver)
}

internal fun ResultReceiver.sendServiceResult(result: Result<Unit>) {
    result.fold(
        onSuccess = {
            send(AsteriskVpnServiceResults.CodeSuccess, Bundle())
        },
        onFailure = { error ->
            send(
                AsteriskVpnServiceResults.CodeFailure,
                Bundle().apply {
                    putString(ExtraMessage, error.message ?: error::class.java.simpleName)
                },
            )
        },
    )
}

internal fun readServiceFailureMessage(data: Bundle?): String {
    return data?.getString(ExtraMessage).orEmpty()
}

internal fun Intent.readVpnServiceStartConfig(): VpnServiceStartConfig? {
    return extras?.let { extras -> extras.readVpnServiceStartConfig() }
}

private fun Intent.writeStartConfig(config: VpnServiceStartConfig) {
    val extras = Bundle().apply { writeVpnServiceStartConfig(config) }
    putExtras(extras)
}

internal fun Bundle.writeVpnServiceStartConfig(config: VpnServiceStartConfig) {
    putString(EXTRA_SESSION_NAME, config.sessionName)
    putInt(EXTRA_MTU, config.mtu)
    putString(EXTRA_IPV4_ADDRESS, config.ipv4Address)
    putInt(EXTRA_IPV4_PREFIX_LENGTH, config.ipv4PrefixLength)
    putString(EXTRA_IPV6_ADDRESS, config.ipv6Address)
    putInt(EXTRA_IPV6_PREFIX_LENGTH, config.ipv6PrefixLength)
    putBoolean(EXTRA_ENABLE_IPV6, config.enableIpv6)
    putBoolean(EXTRA_ENABLE_LOCAL_DNS, config.enableLocalDns)
    putStringArray(EXTRA_DNS_SERVERS, config.dnsServers.toTypedArray())
    putString(EXTRA_SING_BOX_CONFIG_PATH, config.singBoxConfigPath)
    putString(EXTRA_SING_BOX_CONFIG_SIGNATURE, config.singBoxConfigSignature)
    putInt(EXTRA_PROXY_APP_LIST_MODE, config.applicationPolicy.mode)
    putStringArray(EXTRA_PROXY_APP_LIST_PACKAGES, config.applicationPolicy.packageNames.toTypedArray())
    putString(EXTRA_LOCAL_PROXY_LISTEN_ADDRESS, config.localProxyOptions.listenAddress)
    putInt(EXTRA_LOCAL_PROXY_PORT, config.localProxyOptions.port)
    putString(EXTRA_LOCAL_PROXY_USERNAME, config.localProxyOptions.username)
    putString(EXTRA_LOCAL_PROXY_PASSWORD, config.localProxyOptions.password)
    putBoolean(EXTRA_APPEND_HTTP_PROXY_ENABLED, config.appendHttpProxyOptions.enabled)
    putInt(EXTRA_APPEND_HTTP_PROXY_PORT, config.appendHttpProxyOptions.port)
    putString(EXTRA_ERROR_LOG_PATH, config.coreLogPaths.errorLogPath)
    putString(EXTRA_DATA_DIR, config.dataDir)
    config.hevSocks5TunnelConfig?.let { hevConfig ->
        writeHevSocks5TunnelConfig(hevConfig)
    }
}

internal fun Bundle.readVpnServiceStartConfig(): VpnServiceStartConfig? {
    val sessionName = getString(EXTRA_SESSION_NAME) ?: return null
    val ipv4Address = getString(EXTRA_IPV4_ADDRESS) ?: return null
    val singBoxConfigPath = getString(EXTRA_SING_BOX_CONFIG_PATH) ?: return null
    val singBoxConfigSignature = getString(EXTRA_SING_BOX_CONFIG_SIGNATURE) ?: return null
    val localProxyOptions = LocalProxyOptions(
        listenAddress = getString(EXTRA_LOCAL_PROXY_LISTEN_ADDRESS).orEmpty().ifBlank {
            LocalProxyLoopbackAddress
        },
        port = getInt(EXTRA_LOCAL_PROXY_PORT, VpnDefaults.LOCAL_PROXY_PORT),
        username = getString(EXTRA_LOCAL_PROXY_USERNAME).orEmpty(),
        password = getString(EXTRA_LOCAL_PROXY_PASSWORD).orEmpty(),
    )
    return VpnServiceStartConfig(
        sessionName = sessionName,
        mtu = getInt(EXTRA_MTU, VpnDefaults.MTU),
        ipv4Address = ipv4Address,
        ipv4PrefixLength = getInt(EXTRA_IPV4_PREFIX_LENGTH, defaultIpv4TunAddress.prefixLength),
        ipv6Address = getString(EXTRA_IPV6_ADDRESS),
        ipv6PrefixLength = getInt(EXTRA_IPV6_PREFIX_LENGTH, defaultIpv6TunAddress.prefixLength),
        enableIpv6 = getBoolean(EXTRA_ENABLE_IPV6, false),
        enableLocalDns = getBoolean(EXTRA_ENABLE_LOCAL_DNS, true),
        dnsServers = getStringArray(EXTRA_DNS_SERVERS)?.toList().orEmpty().ifEmpty {
            listOf(VpnDefaults.IPV4_DNS)
        },
        singBoxConfigPath = singBoxConfigPath,
        singBoxConfigSignature = singBoxConfigSignature,
        applicationPolicy = VpnApplicationPolicy(
            mode = getInt(EXTRA_PROXY_APP_LIST_MODE, ProxyAppListModeGlobal),
            packageNames = getStringArray(EXTRA_PROXY_APP_LIST_PACKAGES)?.toList().orEmpty(),
        ),
        localProxyOptions = localProxyOptions,
        appendHttpProxyOptions = VpnAppendHttpProxyOptions(
            enabled = getBoolean(EXTRA_APPEND_HTTP_PROXY_ENABLED, false),
            port = getInt(EXTRA_APPEND_HTTP_PROXY_PORT, localProxyOptions.port),
        ),
        coreLogPaths = SingBoxCoreLogPaths(
            errorLogPath = getString(EXTRA_ERROR_LOG_PATH).orEmpty(),
        ),
        dataDir = getString(EXTRA_DATA_DIR).orEmpty(),
        hevSocks5TunnelConfig = readHevSocks5TunnelConfig(),
    )
}

private fun Bundle.readHevSocks5TunnelConfig(): HevSocks5TunnelConfig? {
    val configPath = getString(EXTRA_HEV_TUN_CONFIG_PATH) ?: return null
    val logPath = getString(EXTRA_HEV_TUN_LOG_PATH) ?: return null
    return HevSocks5TunnelConfig(
        executablePath = getString(EXTRA_HEV_TUN_EXECUTABLE_PATH).orEmpty(),
        configPath = configPath,
        pidPath = getString(EXTRA_HEV_TUN_PID_PATH).orEmpty(),
        logPath = logPath,
        socksAddress = getString(EXTRA_HEV_TUN_SOCKS_ADDRESS).orEmpty().ifBlank {
            LocalProxyLoopbackAddress
        },
        socksPort = getInt(EXTRA_HEV_TUN_SOCKS_PORT, VpnDefaults.LOCAL_PROXY_PORT),
        socksUsername = getString(EXTRA_HEV_TUN_SOCKS_USERNAME).orEmpty(),
        socksPassword = getString(EXTRA_HEV_TUN_SOCKS_PASSWORD).orEmpty(),
        mtu = getInt(EXTRA_HEV_TUN_MTU, VpnDefaults.MTU),
        ipv4Address = getString(EXTRA_HEV_TUN_IPV4_ADDRESS) ?: defaultIpv4TunAddress.address,
        ipv6Address = getString(EXTRA_HEV_TUN_IPV6_ADDRESS),
        tunnelName = getString(EXTRA_HEV_TUN_TUNNEL_NAME),
        enableMultiQueue = getBoolean(EXTRA_HEV_TUN_MULTI_QUEUE, false),
        enableTcpFastOpen = getBoolean(EXTRA_HEV_TUN_TCP_FAST_OPEN, false),
        tcpReadWriteTimeoutMillis = getInt(
            EXTRA_HEV_TUN_TCP_RW_TIMEOUT,
            DefaultHevSocks5TunnelTcpReadWriteTimeoutMillis,
        ),
        udpReadWriteTimeoutMillis = getInt(
            EXTRA_HEV_TUN_UDP_RW_TIMEOUT,
            DefaultHevSocks5TunnelUdpReadWriteTimeoutMillis,
        ),
        logLevel = getString(EXTRA_HEV_TUN_LOG_LEVEL).orEmpty().ifBlank {
            DefaultHevSocks5TunnelLogLevel
        },
    )
}

private fun Bundle.writeHevSocks5TunnelConfig(config: HevSocks5TunnelConfig) {
    putString(EXTRA_HEV_TUN_EXECUTABLE_PATH, config.executablePath)
    putString(EXTRA_HEV_TUN_CONFIG_PATH, config.configPath)
    putString(EXTRA_HEV_TUN_PID_PATH, config.pidPath)
    putString(EXTRA_HEV_TUN_LOG_PATH, config.logPath)
    putString(EXTRA_HEV_TUN_SOCKS_ADDRESS, config.socksAddress)
    putInt(EXTRA_HEV_TUN_SOCKS_PORT, config.socksPort)
    putString(EXTRA_HEV_TUN_SOCKS_USERNAME, config.socksUsername)
    putString(EXTRA_HEV_TUN_SOCKS_PASSWORD, config.socksPassword)
    putInt(EXTRA_HEV_TUN_MTU, config.mtu)
    putString(EXTRA_HEV_TUN_IPV4_ADDRESS, config.ipv4Address)
    putString(EXTRA_HEV_TUN_IPV6_ADDRESS, config.ipv6Address)
    putString(EXTRA_HEV_TUN_TUNNEL_NAME, config.tunnelName)
    putBoolean(EXTRA_HEV_TUN_MULTI_QUEUE, config.enableMultiQueue)
    putBoolean(EXTRA_HEV_TUN_TCP_FAST_OPEN, config.enableTcpFastOpen)
    putInt(EXTRA_HEV_TUN_TCP_RW_TIMEOUT, config.tcpReadWriteTimeoutMillis)
    putInt(EXTRA_HEV_TUN_UDP_RW_TIMEOUT, config.udpReadWriteTimeoutMillis)
    putString(EXTRA_HEV_TUN_LOG_LEVEL, config.logLevel)
}

private const val EXTRA_SESSION_NAME = "session_name"
private const val EXTRA_MTU = "mtu"
private const val EXTRA_IPV4_ADDRESS = "ipv4_address"
private const val EXTRA_IPV4_PREFIX_LENGTH = "ipv4_prefix_length"
private const val EXTRA_IPV6_ADDRESS = "ipv6_address"
private const val EXTRA_IPV6_PREFIX_LENGTH = "ipv6_prefix_length"
private const val EXTRA_ENABLE_IPV6 = "enable_ipv6"
private const val EXTRA_ENABLE_LOCAL_DNS = "enable_local_dns"
private const val EXTRA_DNS_SERVERS = "dns_servers"
private const val EXTRA_SING_BOX_CONFIG_PATH = "sing_box_config_path"
private const val EXTRA_SING_BOX_CONFIG_SIGNATURE = "sing_box_config_signature"
private const val EXTRA_PROXY_APP_LIST_MODE = "proxy_app_list_mode"
private const val EXTRA_PROXY_APP_LIST_PACKAGES = "proxy_app_list_packages"
private const val EXTRA_LOCAL_PROXY_LISTEN_ADDRESS = "local_proxy_listen_address"
private const val EXTRA_LOCAL_PROXY_PORT = "local_proxy_port"
private const val EXTRA_LOCAL_PROXY_USERNAME = "local_proxy_username"
private const val EXTRA_LOCAL_PROXY_PASSWORD = "local_proxy_password"
private const val EXTRA_APPEND_HTTP_PROXY_ENABLED = "append_http_proxy_enabled"
private const val EXTRA_APPEND_HTTP_PROXY_PORT = "append_http_proxy_port"
private const val EXTRA_ERROR_LOG_PATH = "error_log_path"
private const val EXTRA_DATA_DIR = "data_dir"
private const val EXTRA_HEV_TUN_EXECUTABLE_PATH = "hev_tun_executable_path"
private const val EXTRA_HEV_TUN_CONFIG_PATH = "hev_tun_config_path"
private const val EXTRA_HEV_TUN_PID_PATH = "hev_tun_pid_path"
private const val EXTRA_HEV_TUN_LOG_PATH = "hev_tun_log_path"
private const val EXTRA_HEV_TUN_SOCKS_ADDRESS = "hev_tun_socks_address"
private const val EXTRA_HEV_TUN_SOCKS_PORT = "hev_tun_socks_port"
private const val EXTRA_HEV_TUN_SOCKS_USERNAME = "hev_tun_socks_username"
private const val EXTRA_HEV_TUN_SOCKS_PASSWORD = "hev_tun_socks_password"
private const val EXTRA_HEV_TUN_MTU = "hev_tun_mtu"
private const val EXTRA_HEV_TUN_IPV4_ADDRESS = "hev_tun_ipv4_address"
private const val EXTRA_HEV_TUN_IPV6_ADDRESS = "hev_tun_ipv6_address"
private const val EXTRA_HEV_TUN_TUNNEL_NAME = "hev_tun_tunnel_name"
private const val EXTRA_HEV_TUN_MULTI_QUEUE = "hev_tun_multi_queue"
private const val EXTRA_HEV_TUN_TCP_FAST_OPEN = "hev_tun_tcp_fast_open"
private const val EXTRA_HEV_TUN_TCP_RW_TIMEOUT = "hev_tun_tcp_rw_timeout"
private const val EXTRA_HEV_TUN_UDP_RW_TIMEOUT = "hev_tun_udp_rw_timeout"
private const val EXTRA_HEV_TUN_LOG_LEVEL = "hev_tun_log_level"
