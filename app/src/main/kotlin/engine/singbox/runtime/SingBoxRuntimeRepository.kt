// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package engine.singbox.runtime

import engine.root.dns.withRootDnsMutation
import android.content.Context
import app.AppState
import app.DefaultConnectionTestUrl
import app.isValidConnectionTestUrl
import app.modes.RunModeVpnService
import engine.proxy.ProxyEngineStartRequest
import engine.singbox.singBoxControlConfig
import engine.singbox.singBoxModeName
import engine.vpn.AsteriskVpnService
import engine.vpn.VpnSingBoxConfigFactory
import features.logs.AndroidAppLogger
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.StatusMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.cancellation.CancellationException
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
    @Volatile
    private var desiredMode = "rule"
    private val modeMutex = Mutex()

    val state: StateFlow<SingBoxRuntimeState> = mutableState.asStateFlow()

    init {
        refreshDeviceState()
    }

    internal fun trafficHistorySnapshot(limit: Int): List<SingBoxTrafficSample> =
        synchronized(trafficHistoryLock) { trafficHistory.snapshot(limit) }

    fun start(appState: AppState) {
        desiredMode = appState.singBoxModeName()
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
        replaceSession(target, appState)
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
                    serviceStartedAtMillis = 0L,
                    traffic = current.traffic.copy(connected = false),
                    proxiesRefreshing = false,
                    delayTestingTarget = null,
                    delayTestingCompleted = 0,
                    delayTestingTotal = 0,
                    delayTestingBaselines = emptyMap(),
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
        val active = requireActiveSession(appState)
        modeMutex.withLock {
            appContext.withRootDnsMutation(appState) {
                active.setMode(appState.singBoxModeName())
            }
        }
    }

    suspend fun patchLogLevel(appState: AppState): Result<Unit> = runCatching {
        reloadConfiguration(appState)
    }

    /**
     * Recompiles [appState] and reloads it into the running VPN core through the Command API.
     * Throws when the reload is not possible, so the caller can fall back to a restart.
     */
    suspend fun reloadVpnConfiguration(appState: AppState) {
        reloadConfiguration(appState)
    }

    private suspend fun reloadConfiguration(appState: AppState) {
        if (!appState.proxyRunning) return
        val active = requireActiveSession(appState)
        val activeGeneration = synchronized(sessionLock) {
            check(session === active) { "sing-box API session changed before reload" }
            generation
        }
        withContext(Dispatchers.IO) {
            if (appState.runMode == RunModeVpnService) {
                VpnSingBoxConfigFactory.create(appContext, ProxyEngineStartRequest(appState))
                check(updateServiceStartedAtIfCurrent(activeGeneration, active, 0L)) {
                    "sing-box API session changed during reload"
                }
                try {
                    // The tunnel lives in its own process; only it can restart the core around
                    // the rewritten configuration file.
                    AsteriskVpnService.reload(appContext)
                } catch (error: Throwable) {
                    refreshServiceStartedAt(activeGeneration, active)
                    throw error
                }
                replaceSession(appState.commandTarget(), appState)
            } else {
                error("ROOT runtime configuration changes require a supervised restart")
            }
        }
    }

    suspend fun selectProxy(
        appState: AppState,
        groupName: String,
        proxyName: String,
    ): Result<Unit> = runCatching {
        val active = requireActiveSession(appState)
        appContext.withRootDnsMutation(appState) {
            active.selectOutbound(groupName, proxyName)
        }
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
    ): Result<SingBoxDelayResult> = runDelayTest(
        appState = appState,
        target = groupName,
        buildPlan = { proxies -> buildSingBoxDelayTestPlan(proxies, groupName) },
    )

    suspend fun testProxyDelay(
        appState: AppState,
        proxyName: String,
    ): Result<SingBoxDelayResult> = runDelayTest(
        appState = appState,
        target = proxyName,
        buildPlan = { proxies -> buildSingBoxProxyDelayTestPlan(proxies, proxyName) },
    )

    suspend fun refreshMemoryNow(appState: AppState): Long? {
        return runCatching {
            requireActiveSession(appState)
            state.value.memory.inUseBytes.takeIf { it > 0L }
        }.getOrNull()
    }

    private fun replaceSession(target: SingBoxCommandTarget, appState: AppState) {
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
                commandListener(nextGeneration, target, appState),
            )
            mutableState.update { current ->
                current.copy(
                    running = false,
                    serviceStartedAtMillis = 0L,
                    control = target.control,
                    traffic = current.traffic.copy(connected = false),
                    proxiesRefreshing = true,
                    delayTestingTarget = null,
                    delayTestingCompleted = 0,
                    delayTestingTotal = 0,
                    delayTestingBaselines = emptyMap(),
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
                        val installed = synchronized(sessionLock) {
                            if (isCurrentLocked(nextGeneration, target)) {
                                session = next
                                true
                            } else {
                                false
                            }
                        }
                        if (!installed) {
                            next.disconnect()
                            return@launch
                        }
                        refreshServiceStartedAt(nextGeneration, next)
                        runCatching {
                            // Read inside the lock: a reconnect must not restore its original snapshot.
                            modeMutex.withLock {
                                // A reconnect to an unchanged core must not withdraw and rebuild DNS enhancement.
                                if (!next.modeApplied(desiredMode)) {
                                    appContext.withRootDnsMutation(appState) {
                                        check(isCurrent(nextGeneration, target)) { "sing-box session changed before mode restore" }
                                        next.setMode(desiredMode)
                                    }
                                }
                            }
                        }.onFailure { error ->
                            if (error is CancellationException) throw error
                            AndroidAppLogger.warn(LogTag, "Failed to restore sing-box Clash mode", error)
                            updateIfCurrent(nextGeneration) { it.copy(lastError = error.message.orEmpty()) }
                        }
                        return@launch
                    }
                    lastError = result.exceptionOrNull()
                    if (attempt + 1 < ConnectAttempts) {
                        delay(ConnectRetryMillis.milliseconds)
                    }
                }
                updateIfCurrent(nextGeneration) { current ->
                    current.copy(
                        running = false,
                        serviceStartedAtMillis = 0L,
                        traffic = current.traffic.copy(connected = false),
                        proxiesRefreshing = false,
                        lastError = lastError?.message.orEmpty(),
                    )
                }
            }
        }
    }

    private fun commandListener(
        listenerGeneration: Long,
        target: SingBoxCommandTarget,
        appState: AppState,
    ): SingBoxCommandListener =
        object : SingBoxCommandListener {
            override fun onConnected() {
                updateIfCurrent(listenerGeneration) { current ->
                    current.copy(
                        running = true,
                        version = SingBoxVersionState(Libbox.version()),
                        // Keep refreshing until this session supplies its proxy snapshot.
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
                        serviceStartedAtMillis = 0L,
                        traffic = current.traffic.copy(connected = false),
                        proxiesRefreshing = false,
                        delayTestingTarget = null,
                        delayTestingCompleted = 0,
                        delayTestingTotal = 0,
                        delayTestingBaselines = emptyMap(),
                        lastError = message,
                    )
                }
                if (reconnect) {
                    appScope.launch(Dispatchers.IO) {
                        delay(ReconnectDelayMillis.milliseconds)
                        synchronized(sessionLock) {
                            if (isCurrentLocked(listenerGeneration, target)) {
                replaceSession(target, appState)
                            }
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
                    current.withProxySnapshot(proxies)
                }
            }

            override fun onConnections(connections: SingBoxConnectionsState) {
                synchronized(sessionLock) {
                    if (generation == listenerGeneration) {
                        latestConnections = connections
                    }
                }
            }
        }

    private fun refreshServiceStartedAt(
        expectedGeneration: Long,
        active: SingBoxCommandClient,
    ) {
        val startedAtMillis = runCatching {
            active.serviceStartedAtMillis().also { value ->
                require(value > 0L) { "sing-box returned an invalid service start timestamp: $value" }
            }
        }.onFailure { error ->
            AndroidAppLogger.warn(
                LogTag,
                "Failed to read sing-box service start timestamp",
                error,
            )
        }.getOrDefault(0L)
        updateServiceStartedAtIfCurrent(expectedGeneration, active, startedAtMillis)
    }

    private fun updateServiceStartedAtIfCurrent(
        expectedGeneration: Long,
        active: SingBoxCommandClient,
        startedAtMillis: Long,
    ): Boolean = synchronized(sessionLock) {
        if (generation != expectedGeneration || session !== active) {
            false
        } else {
            mutableState.update { current ->
                current.withServiceEpoch(startedAtMillis)
            }
            true
        }
    }

    private suspend fun requireActiveSession(appState: AppState): SingBoxCommandClient {
        require(appState.proxyRunning) { "Proxy service is not running" }
        start(appState)
        session?.let { return it }
        withTimeout(SessionWaitMillis.milliseconds) {
            state.first { runtime -> session != null || runtime.lastError.isNotBlank() }
        }
        state.value.lastError.takeIf(String::isNotBlank)?.let(::error)
        return session ?: error("sing-box API is not connected")
    }

    /**
     * Resolve the current command client for a non-delay-test consumer.
     *
     * Waits for a connected session when the service is starting but not yet
     * reporting `running`. Throws when the service is stopped, when the session
     * still fails to connect within [SessionWaitMillis], or when a fresh
     * `lastError` is observed. Used by the network quality executor; the delay
     * test path continues to use its own [requireActiveSession] gate to keep
     * the two code paths independent.
     */
    suspend fun activeCommandClient(appState: AppState): SingBoxCommandClient =
        requireActiveSession(appState)

    private suspend fun runDelayTest(
        appState: AppState,
        target: String,
        buildPlan: (SingBoxProxiesState) -> SingBoxDelayTestPlan,
    ): Result<SingBoxDelayResult> = runDelayTestCatching {
        val lease = delayTestRunGate.acquire()
        try {
            val active = requireActiveSession(appState)
            val runGeneration = synchronized(sessionLock) {
                check(session === active) { "sing-box API session changed during delay test" }
                generation
            }
            val before = state.value
            val plan = buildPlan(before.proxies)
            val baselineTimes = plan.freshnessBaselines(
                failureBaselines = before.delayFailureBaselines,
            )
            check(
                updateDelayTestIfGenerationCurrent(runGeneration) { current ->
                    current.startingDelayTest(
                        target = target,
                        baselines = baselineTimes,
                        targetNames = plan.targetNames,
                        totalCount = plan.leafTargetNames.size,
                    )
                },
            ) {
                "sing-box API session changed during delay test"
            }
            try {
                delay(DelayTestTimestampBoundaryWaitMillis.milliseconds)
                // One measurement per leaf outbound with the user's custom
                // URL test options (link/timeout/unified delay/handshake),
                // husi style; the submission pool bounds concurrency and
                // runs the blocking daemon calls on Dispatchers.IO. Each
                // completed submission bumps the visible progress counter
                // (under the session lock, so concurrent workers can't race).
                val submissions = submitSingBoxDelayCommandsAsync(
                    submissionNames = plan.leafTargetNames,
                    concurrency = appState.connectionTestConcurrent,
                    onCompleted = {
                        updateDelayTestIfGenerationCurrent(runGeneration) { current ->
                            if (current.delayTestingTarget == target) {
                                current.copy(
                                    delayTestingCompleted = current.delayTestingCompleted + 1,
                                )
                            } else {
                                current
                            }
                        }
                    },
                    submit = { leafName ->
                        synchronized(sessionLock) {
                            check(generation == runGeneration && session === active) {
                                "sing-box API session changed during delay test"
                            }
                        }
                        active.urlTestWithOptions(
                            outboundTag = leafName,
                            link = appState.connectionTestUrl.trim()
                                .takeIf(::isValidConnectionTestUrl)
                                ?: DefaultConnectionTestUrl,
                            timeoutMillis = appState.connectionTestTimeoutMillis,
                            unifiedDelay = appState.connectionTestUnifiedDelay,
                            ignoreHandshakeTime = appState.connectionTestIgnoreHandshakeTime,
                        )
                    },
                )
                if (submissions.successfulNames.isEmpty()) {
                    val failure = SingBoxDelayResult(failedTargets = plan.targetNames)
                    updateDelayTestIfGenerationCurrent(runGeneration) { current ->
                        current.finishingDelayTest(target, failure)
                    }
                    error("Failed to submit sing-box delay test commands")
                }
                val knownFailures = plan.knownSubmissionFailures(submissions)
                val completed = awaitSingBoxDelayTestSnapshot(
                    runtimeStates = runtimeStatesForGeneration(runGeneration),
                    plan = plan,
                    baselineTimes = baselineTimes,
                    knownFailures = knownFailures,
                    idleTimeoutMillis = DelayTestNoProgressTimeoutMillis,
                    hardTimeoutMillis = plan.deadlineMillis(
                        connectionTestTimeoutMillis = appState.connectionTestTimeoutMillis,
                        concurrency = appState.connectionTestConcurrent,
                    ),
                )
                val result = plan.finish(
                    delays = completed.freshDelays,
                )
                check(
                    updateDelayTestIfGenerationCurrent(runGeneration) { current ->
                        check(current.delayTestingTarget == target) {
                            "sing-box delay test is no longer active"
                        }
                        current.finishingDelayTest(target, result)
                    },
                ) {
                    "sing-box API session changed during delay test"
                }
                result
            } finally {
                updateDelayTestIfGenerationCurrent(runGeneration) { current ->
                    if (current.delayTestingTarget == target) {
                        current.copy(
                            delayTestingTarget = null,
                            delayTestingCompleted = 0,
                            delayTestingTotal = 0,
                            delayTestingBaselines = emptyMap(),
                        )
                    } else {
                        current
                    }
                }
            }
        } finally {
            lease.release()
        }
    }

    private fun AppState.commandTarget(): SingBoxCommandTarget {
        // The sing-box core never runs in this process: ROOT modes own it through asteriskd, and
        // the VPN Service hosts it in its own ":vpn" process. Both are reached over the command
        // API listener the core exposes, exactly like a remote client.
        return SingBoxCommandTarget(local = false, control = singBoxControlConfig())
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

    private fun runtimeStatesForGeneration(
        expectedGeneration: Long,
    ): Flow<SingBoxRuntimeState> = state.map { runtime ->
        if (isGenerationCurrent(expectedGeneration)) {
            runtime
        } else {
            runtime.copy(running = false)
        }
    }

    private fun updateDelayTestIfGenerationCurrent(
        expectedGeneration: Long,
        transform: (SingBoxRuntimeState) -> SingBoxRuntimeState,
    ): Boolean = synchronized(sessionLock) {
        if (generation != expectedGeneration) {
            false
        } else {
            mutableState.update(transform)
            true
        }
    }

    private fun updateIfCurrent(
        expectedGeneration: Long,
        transform: (SingBoxRuntimeState) -> SingBoxRuntimeState,
    ) {
        updateStateIfGenerationCurrent(
            lock = sessionLock,
            expectedGeneration = expectedGeneration,
            currentGeneration = { generation },
            state = mutableState,
            transform = transform,
        )
    }

    private companion object {
        const val MaxTrafficHistorySize = 48
        const val ConnectAttempts = 3
        const val ConnectRetryMillis = 750L
        const val ReconnectDelayMillis = 1_000L
        const val SessionWaitMillis = 8_000L
        const val DelayTestTimestampBoundaryWaitMillis = 1_100L
        const val DelayTestNoProgressTimeoutMillis = 5_000L
        const val LogTag = "SingBoxRuntime"
    }
}

internal fun <T> updateStateIfGenerationCurrent(
    lock: Any,
    expectedGeneration: Long,
    currentGeneration: () -> Long,
    state: MutableStateFlow<T>,
    transform: (T) -> T,
): Boolean = synchronized(lock) {
    if (currentGeneration() != expectedGeneration) {
        false
    } else {
        state.update(transform)
        true
    }
}

internal fun SingBoxRuntimeState.withProxySnapshot(
    proxies: SingBoxProxiesState,
): SingBoxRuntimeState = copy(
    running = true,
    proxies = proxies,
    proxiesRefreshing = false,
    lastError = "",
)

internal fun SingBoxRuntimeState.startingDelayTest(
    target: String,
    baselines: Map<String, Long>,
    targetNames: Set<String>,
    totalCount: Int,
): SingBoxRuntimeState = copy(
    delayTestingTarget = target,
    delayTestingCompleted = 0,
    delayTestingTotal = totalCount.coerceAtLeast(0),
    delayTestingBaselines = baselines,
    delayFailureBaselines = delayFailureBaselines - targetNames,
)

internal fun SingBoxRuntimeState.finishingDelayTest(
    target: String,
    result: SingBoxDelayResult,
): SingBoxRuntimeState {
    if (delayTestingTarget != target) return this
    return copy(
        delayTestingTarget = null,
        delayTestingCompleted = 0,
        delayTestingTotal = 0,
        delayTestingBaselines = emptyMap(),
        delayFailureBaselines = mergeSingBoxDelayFailures(
            currentFailureBaselines = delayFailureBaselines,
            result = result,
            runBaselines = delayTestingBaselines,
        ),
    )
}
