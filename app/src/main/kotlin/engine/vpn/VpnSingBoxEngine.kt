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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
        try {
            AsteriskVpnService.start(
                context = context,
                config = config,
            )
        } catch (error: Throwable) {
            // Nothing of this attempt may be left behind: a stale local proxy would send this
            // process' own fetches to a port that no longer serves.
            LocalProxyRuntime.clear()
            throw error
        }
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

    override suspend fun status(): ProxyEngineStatus = withContext(Dispatchers.IO) {
        // `runtimeRunning` opens and locks the session lease, so it must not run on the caller's
        // thread: applyConfiguration reaches this from Dispatchers.Main.immediate.
        ProxyEngineStatus(
            running = runtimeRunning(),
            runMode = runMode,
        )
    }
}

internal fun shouldLaunchVpnService(isRunning: Boolean, explicitRestart: Boolean): Boolean {
    return explicitRestart || !isRunning
}
