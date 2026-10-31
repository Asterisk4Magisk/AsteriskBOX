// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package engine.vpn

import android.content.Context
import android.content.Intent
import android.net.VpnService
import app.modes.RunModeVpnService
import app.R
import engine.proxy.mode.AndroidModeProxyEngine
import engine.proxy.LocalProxyRuntime
import engine.proxy.ProxyEngineStartRequest
import engine.proxy.ProxyEngineStatus

internal class VpnSingBoxEngine(
    private val context: Context,
    private val requestVpnPermission: suspend (Intent) -> Boolean,
    private val runtimeRunning: () -> Boolean = { AsteriskVpnService.isRunning(context) },
) : AndroidModeProxyEngine {
    override val runMode: Int = RunModeVpnService

    override suspend fun start(request: ProxyEngineStartRequest): ProxyEngineStatus {
        return startWithPolicy(request, explicitRestart = false)
    }

    suspend fun restart(request: ProxyEngineStartRequest): ProxyEngineStatus {
        return startWithPolicy(request, explicitRestart = true)
    }

    private suspend fun startWithPolicy(
        request: ProxyEngineStartRequest,
        explicitRestart: Boolean,
    ): ProxyEngineStatus {
        if (!shouldLaunchVpnService(runtimeRunning(), explicitRestart)) return status()
        launch(request)
        return status()
    }

    private suspend fun launch(request: ProxyEngineStartRequest) {
        val prepareIntent = VpnService.prepare(context)
        if (prepareIntent != null && !requestVpnPermission(prepareIntent)) {
            error(context.getString(R.string.error_vpn_permission_denied))
        }
        val config = VpnSingBoxConfigFactory.create(context, request)
        AsteriskVpnService.start(
            context = context,
            config = config,
        )
        // The tunnel owns the local proxy now, and the service process cannot publish that to
        // this process, so record it here for the HTTP fetches this process performs.
        if (config.localProxyOptions.port > 0) {
            LocalProxyRuntime.update(config.localProxyOptions)
        } else {
            LocalProxyRuntime.clear()
        }
    }

    override suspend fun stop(): ProxyEngineStatus {
        AsteriskVpnService.stop(context)
        LocalProxyRuntime.clear()
        return status()
    }

    /**
     * Reapplies the configuration file that [VpnSingBoxConfigFactory] just rewrote to the running
     * tunnel. The tunnel and its foreground notification stay up; the caller reconnects its
     * command session afterwards.
     */
    suspend fun reload() {
        AsteriskVpnService.reload(context)
    }

    override suspend fun status(): ProxyEngineStatus {
        return ProxyEngineStatus(
            running = runtimeRunning(),
            runMode = runMode,
        )
    }
}

internal fun shouldLaunchVpnService(isRunning: Boolean, explicitRestart: Boolean): Boolean {
    return explicitRestart || !isRunning
}
