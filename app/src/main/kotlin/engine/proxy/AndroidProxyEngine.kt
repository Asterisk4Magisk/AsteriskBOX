// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package engine.proxy

import android.content.Context
import android.content.Intent
import app.modes.RunModeVpnService
import engine.proxy.mode.AndroidModeProxyEngine
import engine.root.RootModeEngine
import engine.stats.SingBoxTrafficStatsNotificationService
import engine.stats.toSingBoxTrafficStatsRuntime
import engine.singbox.withResolvedSingBoxControlPort
import engine.singbox.SingBoxConfigFactory
import engine.vpn.VpnSingBoxEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import system.AndroidRootShellGateway

internal class AndroidProxyEngine(
    context: Context,
    rootAccess: AndroidRootShellGateway,
    requestVpnPermission: suspend (Intent) -> Boolean,
) {
    private val appContext = context.applicationContext
    private val vpnSingBoxEngine = VpnSingBoxEngine(appContext, requestVpnPermission)
    private val rootEngines = RootModeEngine.createAll(appContext, rootAccess)
    private val rootEnginesByRunMode = rootEngines.associateBy(RootModeEngine::runMode)
    private val operationMutex = Mutex()
    private var activeEngine: AndroidModeProxyEngine? = null

    suspend fun start(request: ProxyEngineStartRequest): ProxyEngineStatus = operationMutex.withLock {
        startUnlocked(request)
    }

    suspend fun stop(preferredRunMode: Int? = null): ProxyEngineStatus = operationMutex.withLock {
        stopUnlocked(preferredRunMode)
    }

    suspend fun stopCurrentRunMode(runMode: Int): ProxyEngineStatus = operationMutex.withLock {
        stopRunModeUnlocked(runMode)
    }

    suspend fun restart(request: ProxyEngineStartRequest): ProxyEngineStatus = operationMutex.withLock {
        startUnlocked(request, explicitRestart = true)
    }

    suspend fun status(
        preferredRunMode: Int? = null,
        appState: app.AppState? = null,
    ): ProxyEngineStatus = operationMutex.withLock {
        statusUnlocked(preferredRunMode, appState)
    }

    private suspend fun startUnlocked(
        request: ProxyEngineStartRequest,
        explicitRestart: Boolean = false,
    ): ProxyEngineStatus = withContext(Dispatchers.Default) {
        // Build once before any notification, engine replacement, VPN permission,
        // Root command, or routing change. This makes an explicit restart atomic
        // with respect to invalid or deprecated sing-box JSON.
        SingBoxConfigFactory.buildConfigBytes(appContext, request.appState)
        SingBoxTrafficStatsNotificationService.reconcile(appContext, null)
        val requestedEngine = request.appState.runMode.engine()
        if (shouldResumeRootBeforeResolvingPorts(explicitRestart, activeEngine != null, requestedEngine is RootModeEngine)) {
            requestedEngine as RootModeEngine
            requestedEngine.resumeIfRunning(request)?.let { status ->
                activeEngine = requestedEngine
                val resumed = status.copy(appState = request.appState)
                SingBoxTrafficStatsNotificationService.reconcile(
                    appContext,
                    request.appState.toSingBoxTrafficStatsRuntime(status.runMode ?: request.appState.runMode),
                )
                return@withContext resumed
            }
        }
        val resolvedRequest = request.copy(
            appState = request.appState
                .withResolvedDynamicLocalProxyPort()
                .withResolvedSingBoxControlPort(),
        )
        val nextEngine = resolvedRequest.appState.runMode.engine()
        val currentEngine = activeEngine ?: findEngineToStop(resolvedRequest.appState.runMode)
        val rootToRootRestart = explicitRestart && currentEngine is RootModeEngine && nextEngine is RootModeEngine
        if (currentEngine != null && currentEngine !== nextEngine && !rootToRootRestart) {
            currentEngine.stop()
        }
        activeEngine = nextEngine
        try {
            val status = if (explicitRestart && nextEngine is RootModeEngine) {
                nextEngine.restart(resolvedRequest)
            } else {
                nextEngine.start(resolvedRequest)
            }
                .copy(
                    appState = resolvedRequest.appState,
                )
            val runtime = if (status.running) {
                resolvedRequest.appState.toSingBoxTrafficStatsRuntime(
                    runMode = status.runMode ?: resolvedRequest.appState.runMode,
                )
            } else {
                null
            }
            SingBoxTrafficStatsNotificationService.reconcile(appContext, runtime)
            status
        } catch (error: Throwable) {
            SingBoxTrafficStatsNotificationService.reconcile(appContext, null)
            throw error
        }
    }

    private suspend fun stopUnlocked(preferredRunMode: Int? = null): ProxyEngineStatus = withContext(Dispatchers.Default) {
        val engine = findEngineToStop(preferredRunMode)
        val stoppedMode = engine?.runMode
        engine?.stop()
        activeEngine = null
        SingBoxTrafficStatsNotificationService.reconcile(appContext, null)
        ProxyEngineStatus(running = false, runMode = stoppedMode)
    }

    private suspend fun stopRunModeUnlocked(runMode: Int): ProxyEngineStatus = withContext(Dispatchers.Default) {
        val engine = runMode.engine()
        activeEngine
            ?.takeIf { active -> active !== engine }
            ?.stop()
        val status = engine.stop()
        activeEngine = null
        SingBoxTrafficStatsNotificationService.reconcile(appContext, null)
        status
    }

    private suspend fun findEngineToStop(preferredRunMode: Int?): AndroidModeProxyEngine? {
        val preferredEngine = preferredRunMode?.engine()
        return activeEngine
            ?: preferredEngine?.takeIf { it.status().running }
            ?: preferredEngine?.takeIf { it.ownsRootRuntime() }
            ?: rootEngines.firstOrNull { engine -> engine.status().running }
            ?: vpnSingBoxEngine.takeIf { it.status().running }
            ?: rootEngines.firstOrNull { engine -> engine.ownsRuntime() }
    }

    private suspend fun statusUnlocked(
        preferredRunMode: Int? = null,
        appState: app.AppState? = null,
    ): ProxyEngineStatus = withContext(Dispatchers.Default) {
        val activeStatus = activeEngine?.status()
        if (activeStatus?.running == true) {
            return@withContext activeStatus
                .withTrafficStatsReconciled(appState)
        }

        var fallbackStatus = activeStatus
        preferredRunMode?.engine()?.let { preferredEngine ->
            val preferredStatus = preferredEngine.status()
            if (preferredStatus.running) {
                activeEngine = preferredEngine
                return@withContext preferredStatus
                    .withTrafficStatsReconciled(appState)
            }
            if (preferredStatus.rootSnapshot != null || fallbackStatus?.rootSnapshot == null) {
                fallbackStatus = preferredStatus
            }
        }

        (rootEngines + vpnSingBoxEngine)
            .filterNot { engine -> engine.runMode == preferredRunMode }
            .forEach { engine ->
                val status = engine.status()
                if (status.running) {
                    activeEngine = engine
                    return@withContext status
                        .withTrafficStatsReconciled(appState)
                }
                if (status.rootSnapshot != null && fallbackStatus?.rootSnapshot == null) {
                    fallbackStatus = status
                }
            }

        activeEngine = null
        (fallbackStatus ?: ProxyEngineStatus(running = false, runMode = preferredRunMode))
            .withTrafficStatsReconciled(appState)
    }

    private fun Int.engine(): AndroidModeProxyEngine {
        return rootEnginesByRunMode[this] ?: vpnSingBoxEngine
    }

    private suspend fun AndroidModeProxyEngine.ownsRootRuntime(): Boolean {
        return this is RootModeEngine && ownsRuntime()
    }

    private fun ProxyEngineStatus.withTrafficStatsReconciled(appState: app.AppState?): ProxyEngineStatus {
        if (!running) {
            SingBoxTrafficStatsNotificationService.reconcile(appContext, null)
            return this
        }
        val activeRunMode = runMode ?: appState?.runMode
        if (activeRunMode != RunModeVpnService) {
            SingBoxTrafficStatsNotificationService.reconcile(appContext, null)
            return this
        }
        if (appState == null) {
            return this
        }
        val runtime = appState.toSingBoxTrafficStatsRuntime(activeRunMode)
        SingBoxTrafficStatsNotificationService.reconcile(appContext, runtime)
        return this
    }
}

internal fun shouldResumeRootBeforeResolvingPorts(
    explicitRestart: Boolean,
    hasActiveEngine: Boolean,
    requestedIsRoot: Boolean,
): Boolean = !explicitRestart && !hasActiveEngine && requestedIsRoot
