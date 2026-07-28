// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package engine.singbox.runtime

import android.content.Context
import app.AppState
import app.modes.RunModeVpnService
import engine.proxy.ProxyEngineStartRequest
import engine.root.prepareRootConfigBuildContext
import engine.root.writeRootConfigFile
import engine.singbox.singBoxControlConfig
import engine.singbox.singBoxModeName
import engine.vpn.VpnSingBoxConfigFactory
import features.logs.AndroidAppLogger
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.StatusMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import system.AndroidRootShellGateway
import system.ShellExecOptions
import utils.shellQuote
import kotlin.time.Duration.Companion.milliseconds

internal class SingBoxRuntimeRepository(
    private val appScope: CoroutineScope,
    context: Context,
) {
    private val appContext = context.applicationContext
    private val mutableState = MutableStateFlow(SingBoxRuntimeState())
    private val trafficHistory = SingBoxTrafficHistoryBuffer(MaxTrafficHistorySize)
    private val trafficHistoryLock = Any()
    private val sessionLock = Any()
    private val delayTestRunGate = SingBoxDelayTestRunGate()
    @Volatile
    private var session: SingBoxCommandClient? = null
    private var sessionTarget: SingBoxCommandTarget? = null
    private var connectJob: Job? = null
    private var generation = 0L
    @Volatile
    private var latestConnections = SingBoxConnectionsState()

    val state: StateFlow<SingBoxRuntimeState> = mutableState.asStateFlow()

    init {
        refreshDeviceState()
    }

    internal fun trafficHistorySnapshot(limit: Int): List<SingBoxTrafficSample> =
        synchronized(trafficHistoryLock) { trafficHistory.snapshot(limit) }

    fun start(appState: AppState) {
        if (!appState.proxyRunning) {
            stop(resetSnapshots = false)
            return
        }
        val target = runCatching { appState.commandTarget() }.getOrElse { error ->
            stop(resetSnapshots = false)
            mutableState.update { current -> current.copy(lastError = error.message.orEmpty()) }
            return
        }
        synchronized(sessionLock) {
            if (sessionTarget == target && (session != null || connectJob?.isActive == true)) {
                return
            }
        }
        replaceSession(appState, target)
    }

    fun stop(resetSnapshots: Boolean = false) {
        val previous = synchronized(sessionLock) {
            generation += 1L
            connectJob?.cancel()
            connectJob = null
            sessionTarget = null
            session.also { session = null }
        }
        if (previous != null) {
            appScope.launch(Dispatchers.IO) { previous.disconnect() }
        }
        latestConnections = SingBoxConnectionsState()
        if (resetSnapshots) {
            synchronized(trafficHistoryLock) { trafficHistory.clear() }
        }
        mutableState.update { current ->
            if (resetSnapshots) {
                SingBoxRuntimeState(device = current.device)
            } else {
                current.copy(
                    running = false,
                    traffic = current.traffic.copy(connected = false),
                    proxiesRefreshing = false,
                    delayTestingTarget = null,
                    delayTestingNodes = emptySet(),
                    lastError = "",
                )
            }
        }
    }

    suspend fun refresh(appState: AppState): Result<Unit> = runCatching {
        requireActiveSession(appState)
    }

    suspend fun refreshProxies(appState: AppState): Result<Unit> = runCatching {
        requireActiveSession(appState)
        require(state.value.proxies.updatedAtMillis > 0L) { "sing-box proxy groups are not available" }
    }

    suspend fun getConnections(appState: AppState): Result<SingBoxConnectionsState> = runCatching {
        requireActiveSession(appState)
        latestConnections
    }

    suspend fun getConnectionCount(appState: AppState): Result<Int> =
        getConnections(appState).map { it.connections.size }

    suspend fun closeConnection(appState: AppState, connectionId: String): Result<Boolean> = runCatching {
        val active = requireActiveSession(appState)
        active.closeConnection(connectionId)
        true
    }

    suspend fun closeAllConnections(appState: AppState): Result<Unit> = runCatching {
        requireActiveSession(appState).closeConnections()
    }

    suspend fun patchMode(appState: AppState): Result<Unit> = runCatching {
        if (appState.runMode == RunModeVpnService) {
            requireActiveSession(appState).setMode(appState.singBoxModeName())
        } else {
            // The standard core's stable API service does not register ClashServer; enabling it
            // through experimental.clash_api is intentionally forbidden by this application.
            reloadConfiguration(appState)
        }
    }

    suspend fun patchLogLevel(appState: AppState): Result<Unit> = runCatching {
        reloadConfiguration(appState)
    }

    private suspend fun reloadConfiguration(appState: AppState) {
        if (!appState.proxyRunning) return
        val active = requireActiveSession(appState)
        withContext(Dispatchers.IO) {
            if (appState.runMode == RunModeVpnService) {
                VpnSingBoxConfigFactory.create(appContext, ProxyEngineStartRequest(appState))
                active.reloadService()
            } else {
                val root = appContext
                    .prepareRootConfigBuildContext(ProxyEngineStartRequest(appState))
                    .buildRootStartConfig()
                writeRootConfigFile(root)
                val result = AndroidRootShellGateway().exec(
                    command = "kill -HUP \"$(cat ${root.runtimeLayout.pidPath.shellQuote()})\"",
                    options = ShellExecOptions(logFailure = false),
                )
                check(result.errno == 0) {
                    result.stderr.ifBlank { "Failed to reload ROOT sing-box configuration" }
                }
                delay(RootReloadWaitMillis.milliseconds)
                replaceSession(appState, appState.commandTarget())
            }
        }
    }

    suspend fun selectProxy(
        appState: AppState,
        groupName: String,
        proxyName: String,
    ): Result<Unit> = runCatching {
        requireActiveSession(appState).selectOutbound(groupName, proxyName)
        mutableState.update { current ->
            current.copy(
                proxies = current.proxies.copy(
                    groups = current.proxies.groups.map { group ->
                        if (group.name == groupName) group.copy(now = proxyName) else group
                    },
                ),
            )
        }
    }

    suspend fun testGroupDelay(
        appState: AppState,
        groupName: String,
    ): Result<SingBoxDelayResult> = runDelayTest(appState, groupName)

    fun refreshConnectivity() {
        refreshDeviceState()
    }

    suspend fun refreshMemoryNow(appState: AppState): Long? {
        return runCatching {
            requireActiveSession(appState)
            state.value.memory.inUseBytes.takeIf { it > 0L }
        }.getOrNull()
    }

    private fun replaceSession(appState: AppState, target: SingBoxCommandTarget) {
        val old: SingBoxCommandClient?
        val nextGeneration: Long
        lateinit var next: SingBoxCommandClient
        synchronized(sessionLock) {
            generation += 1L
            nextGeneration = generation
            connectJob?.cancel()
            old = session
            session = null
            sessionTarget = target
            next = SingBoxCommandClient(
                target,
                commandListener(nextGeneration, appState, target),
            )
            mutableState.update { current ->
                current.copy(
                    running = false,
                    control = target.control,
                    traffic = current.traffic.copy(connected = false),
                    proxiesRefreshing = true,
                    lastError = "",
                )
            }
            connectJob = appScope.launch(Dispatchers.IO) {
                old?.disconnect()
                var lastError: Throwable? = null
                repeat(ConnectAttempts) { attempt ->
                    if (!isCurrent(nextGeneration, target)) return@launch
                    val result = runCatching { next.connect() }
                    if (result.isSuccess) {
                        synchronized(sessionLock) {
                            if (isCurrentLocked(nextGeneration, target)) {
                                session = next
                            }
                        }
                        if (appState.runMode == RunModeVpnService) {
                            runCatching {
                                next.setMode(appState.singBoxModeName())
                            }.onFailure { error ->
                                AndroidAppLogger.warn(
                                    LogTag,
                                    "Failed to restore sing-box Clash mode",
                                    error,
                                )
                            }
                        }
                        return@launch
                    }
                    lastError = result.exceptionOrNull()
                    if (attempt + 1 < ConnectAttempts) {
                        delay(ConnectRetryMillis.milliseconds)
                    }
                }
                if (isCurrent(nextGeneration, target)) {
                    mutableState.update { current ->
                        current.copy(
                            running = false,
                            traffic = current.traffic.copy(connected = false),
                            proxiesRefreshing = false,
                            lastError = lastError?.message.orEmpty(),
                        )
                    }
                }
            }
        }
    }

    private fun commandListener(
        listenerGeneration: Long,
        appState: AppState,
        target: SingBoxCommandTarget,
    ): SingBoxCommandListener =
        object : SingBoxCommandListener {
            override fun onConnected() {
                updateIfCurrent(listenerGeneration) { current ->
                    current.copy(
                        running = true,
                        version = SingBoxVersionState(Libbox.version()),
                        proxiesRefreshing = false,
                        lastError = "",
                    )
                }
            }

            override fun onDisconnected(message: String) {
                val reconnect = synchronized(sessionLock) {
                    if (!isCurrentLocked(listenerGeneration, target) || session == null) {
                        false
                    } else {
                        session = null
                        true
                    }
                }
                updateIfCurrent(listenerGeneration) { current ->
                    current.copy(
                        running = false,
                        traffic = current.traffic.copy(connected = false),
                        proxiesRefreshing = false,
                        delayTestingTarget = null,
                        delayTestingNodes = emptySet(),
                        lastError = message,
                    )
                }
                if (reconnect) {
                    appScope.launch(Dispatchers.IO) {
                        delay(ReconnectDelayMillis.milliseconds)
                        if (isCurrent(listenerGeneration, target)) {
                            replaceSession(appState, target)
                        }
                    }
                }
            }

            override fun onStatus(status: StatusMessage) {
                val sample = SingBoxTrafficSample(
                    up = status.uplink,
                    down = status.downlink,
                    totalUp = status.uplinkTotal,
                    totalDown = status.downlinkTotal,
                )
                synchronized(trafficHistoryLock) { trafficHistory.append(sample) }
                updateIfCurrent(listenerGeneration) { current ->
                    current.copy(
                        running = true,
                        traffic = SingBoxTrafficState(
                            latest = sample,
                            totalUp = status.uplinkTotal,
                            totalDown = status.downlinkTotal,
                            connected = status.trafficAvailable,
                        ),
                        memory = current.memory.copy(inUseBytes = status.memory),
                    )
                }
            }

            override fun onProxies(proxies: SingBoxProxiesState) {
                updateIfCurrent(listenerGeneration) { current ->
                    current.copy(
                        running = true,
                        proxies = proxies,
                        proxiesRefreshing = false,
                        delayFailureBaselines = retainUnresolvedSingBoxDelayFailures(
                            failedTargetBaselines = current.delayFailureBaselines,
                            proxies = proxies,
                        ),
                        lastError = "",
                    )
                }
            }

            override fun onConnections(connections: SingBoxConnectionsState) {
                if (!isGenerationCurrent(listenerGeneration)) return
                latestConnections = connections
            }
        }

    private suspend fun requireActiveSession(appState: AppState): SingBoxCommandClient {
        require(appState.proxyRunning) { "Proxy service is not running" }
        start(appState)
        session?.let { return it }
        withTimeout(SessionWaitMillis.milliseconds) {
            state.first { runtime -> runtime.running || runtime.lastError.isNotBlank() }
        }
        state.value.lastError.takeIf(String::isNotBlank)?.let(::error)
        return session ?: error("sing-box API is not connected")
    }

    private suspend fun runDelayTest(
        appState: AppState,
        target: String,
    ): Result<SingBoxDelayResult> = runCatching {
        delayTestRunGate.runExclusive {
            val active = requireActiveSession(appState)
            val before = state.value
            val plan = buildSingBoxDelayTestPlan(before.proxies, target)
            val baselineTimes = plan.freshnessBaselines(
                failureBaselines = before.delayFailureBaselines,
            )
            mutableState.update { current ->
                current.copy(
                    delayTestingTarget = target,
                    delayTestingNodes = plan.targetNames,
                )
            }
            try {
                delay(DelayTestTimestampBoundaryWaitMillis.milliseconds)
                val submissions = submitSingBoxDelayCommands(
                    commandGroupNames = plan.commandGroupNames,
                    submit = active::urlTest,
                )
                if (submissions.successfulGroupNames.isEmpty()) {
                    mutableState.update { current ->
                        current.copy(
                            delayFailureBaselines = mergeSingBoxDelayFailures(
                                currentFailureBaselines = current.delayFailureBaselines,
                                result = SingBoxDelayResult(failedTargets = plan.targetNames),
                                runBaselines = baselineTimes,
                            ),
                        )
                    }
                    error("Failed to submit sing-box delay test commands")
                }
                val knownFailures = plan.knownSubmissionFailures(submissions)
                val completed = awaitSingBoxDelayTestSnapshot(
                    runtimeStates = state,
                    plan = plan,
                    baselineTimes = baselineTimes,
                    knownFailures = knownFailures,
                    timeoutMillis = plan.deadlineMillis(),
                )
                val result = plan.finish(
                    proxies = completed ?: state.value.proxies,
                    baselineTimes = baselineTimes,
                )
                mutableState.update { current ->
                    current.copy(
                        delayFailureBaselines = mergeSingBoxDelayFailures(
                            currentFailureBaselines = current.delayFailureBaselines,
                            result = result,
                            runBaselines = baselineTimes,
                        ),
                    )
                }
                result
            } finally {
                mutableState.update { current ->
                    if (current.delayTestingTarget == target) {
                        current.copy(
                            delayTestingTarget = null,
                            delayTestingNodes = emptySet(),
                        )
                    } else {
                        current
                    }
                }
            }
        }
    }

    private fun AppState.commandTarget(): SingBoxCommandTarget {
        val local = runMode == RunModeVpnService
        return SingBoxCommandTarget(local = local, control = singBoxControlConfig())
    }

    private fun refreshDeviceState() {
        mutableState.update { current -> current.copy(device = collectSingBoxDeviceState()) }
    }

    private fun isCurrent(generation: Long, target: SingBoxCommandTarget): Boolean =
        synchronized(sessionLock) { isCurrentLocked(generation, target) }

    private fun isCurrentLocked(generation: Long, target: SingBoxCommandTarget): Boolean =
        this.generation == generation && sessionTarget == target

    private fun isGenerationCurrent(generation: Long): Boolean =
        synchronized(sessionLock) { this.generation == generation }

    private fun updateIfCurrent(
        generation: Long,
        transform: (SingBoxRuntimeState) -> SingBoxRuntimeState,
    ) {
        if (!isGenerationCurrent(generation)) return
        mutableState.update(transform)
    }

    private companion object {
        const val MaxTrafficHistorySize = 48
        const val ConnectAttempts = 3
        const val ConnectRetryMillis = 750L
        const val ReconnectDelayMillis = 1_000L
        const val SessionWaitMillis = 8_000L
        const val DelayTestTimestampBoundaryWaitMillis = 1_100L
        const val RootReloadWaitMillis = 750L
        const val LogTag = "SingBoxRuntime"
    }
}
