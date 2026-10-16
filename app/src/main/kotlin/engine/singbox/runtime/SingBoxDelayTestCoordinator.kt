// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package engine.singbox.runtime

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.milliseconds

internal data class SingBoxDelayTestPlan(
    // Outbound tags submitted to the daemon, one measurement each — the
    // delay test walks the tree down to its leaf outbounds, husi style.
    val leafTargetNames: List<String>,
    // Every name the UI awaits, including nested groups; a nested group
    // resolves through its selected leaf's URL test history.
    val targetNames: Set<String>,
    val baselineTimes: Map<String, Long>,
)

internal data class SingBoxDelayCommandSubmissions(
    val successfulNames: Set<String>,
    val failedNames: Set<String>,
)

internal enum class SingBoxDelayAwaitCompletion {
    Resolved,
    SoftTimedOut,
    HardTimedOut,
}

internal data class SingBoxDelayAwaitResult(
    val proxies: SingBoxProxiesState,
    val completion: SingBoxDelayAwaitCompletion,
    val freshDelays: Map<String, Int>,
)

internal class SingBoxDelayTestRunGate {
    private val lock = Any()
    private var owner: Any? = null

    fun acquire(): SingBoxDelayTestRunLease = synchronized(lock) {
        check(owner == null) { "sing-box delay test is already running" }
        val token = Any()
        owner = token
        SingBoxDelayTestRunLease(this, token)
    }

    internal fun release(token: Any) {
        synchronized(lock) {
            if (owner === token) owner = null
        }
    }
}

internal class SingBoxDelayTestRunLease internal constructor(
    private val gate: SingBoxDelayTestRunGate,
    private val token: Any,
) {
    private val lock = Any()
    private var released = false

    fun release() {
        synchronized(lock) {
            if (released) return
            released = true
            gate.release(token)
        }
    }
}

internal inline fun <T> runDelayTestCatching(block: () -> T): Result<T> =
    runCatching(block).onFailure { error ->
        if (error is CancellationException) throw error
    }

internal fun buildSingBoxDelayTestPlan(
    proxies: SingBoxProxiesState,
    rootGroupName: String,
): SingBoxDelayTestPlan {
    val groupsByName = proxies.groups.associateBy(SingBoxProxyGroup::name)
    require(groupsByName.containsKey(rootGroupName)) {
        "sing-box group is unavailable: $rootGroupName"
    }

    val visitedGroupNames = linkedSetOf<String>()
    val leafTargetNames = linkedSetOf<String>()
    val targetNames = linkedSetOf<String>()

    fun isGroup(name: String): Boolean {
        return name in groupsByName || proxies.nodeByName[name]?.type.isSingBoxDelayGroupType()
    }

    fun visit(groupName: String) {
        if (!visitedGroupNames.add(groupName)) return
        val group = groupsByName[groupName] ?: return
        group.all.forEach { memberName ->
            targetNames += memberName
            if (isGroup(memberName)) {
                visit(memberName)
            } else {
                leafTargetNames += memberName
            }
        }
    }

    visit(rootGroupName)

    return SingBoxDelayTestPlan(
        leafTargetNames = leafTargetNames.toList(),
        targetNames = targetNames,
        baselineTimes = targetNames.associateWith { name ->
            proxies.nodeByName[name]?.delayUpdatedAtEpochSeconds ?: Long.MIN_VALUE
        },
    )
}

internal fun buildSingBoxProxyDelayTestPlan(
    proxies: SingBoxProxiesState,
    proxyName: String,
): SingBoxDelayTestPlan {
    if (proxies.groups.any { group -> group.name == proxyName }) {
        return buildSingBoxDelayTestPlan(proxies, proxyName)
    }
    val proxy = requireNotNull(proxies.nodeByName[proxyName]) {
        "sing-box proxy is unavailable: $proxyName"
    }
    return SingBoxDelayTestPlan(
        leafTargetNames = listOf(proxyName),
        targetNames = setOf(proxyName),
        baselineTimes = mapOf(
            proxyName to (proxy.delayUpdatedAtEpochSeconds ?: Long.MIN_VALUE),
        ),
    )
}

internal fun SingBoxDelayTestPlan.freshnessBaselines(
    failureBaselines: Map<String, Long>,
): Map<String, Long> = targetNames.associateWith { name ->
    maxOf(
        baselineTimes[name] ?: Long.MIN_VALUE,
        failureBaselines[name] ?: Long.MIN_VALUE,
    )
}

internal suspend fun submitSingBoxDelayCommandsAsync(
    submissionNames: List<String>,
    concurrency: Int,
    onCompleted: suspend () -> Unit = {},
    submit: suspend (String) -> Unit,
): SingBoxDelayCommandSubmissions {
    val distinctNames = submissionNames.distinct()
    if (distinctNames.isEmpty()) {
        return SingBoxDelayCommandSubmissions(
            successfulNames = emptySet(),
            failedNames = emptySet(),
        )
    }
    val semaphore = Semaphore(concurrency.coerceAtLeast(1))
    val outcomes = coroutineScope {
        distinctNames.map { name ->
            async {
                val succeeded = runCatching {
                    // The callback blocks on the daemon-side measurement;
                    // keep it off the caller's thread (UI/Default) and let
                    // the semaphore bound how many measurements run at once.
                    semaphore.withPermit { withContext(Dispatchers.IO) { submit(name) } }
                }.onFailure { error ->
                    if (error is CancellationException) throw error
                }.isSuccess
                // Progress hook: fires once per finished name, success or
                // failure; skipped when the run was cancelled.
                onCompleted()
                succeeded
            }
        }.awaitAll()
    }
    val successfulNames = linkedSetOf<String>()
    val failedNames = linkedSetOf<String>()
    distinctNames.forEachIndexed { index, name ->
        if (outcomes[index]) successfulNames += name else failedNames += name
    }
    return SingBoxDelayCommandSubmissions(
        successfulNames = successfulNames,
        failedNames = failedNames,
    )
}

internal fun SingBoxDelayTestPlan.knownSubmissionFailures(
    submissions: SingBoxDelayCommandSubmissions,
): Set<String> = submissions.failedNames

internal fun SingBoxDelayTestPlan.deadlineMillis(
    connectionTestTimeoutMillis: Int,
    concurrency: Int,
): Long {
    // Submissions block until each measurement completes; the await phase
    // only has to catch the remaining stream pushes, so budget one pass
    // over the leaves at the configured concurrency plus a grace margin.
    val perTest = connectionTestTimeoutMillis.coerceAtLeast(1_000).toLong() +
        SingBoxDelayPerTestOverheadMillis
    val workers = concurrency.coerceAtLeast(1)
    val rounds = (leafTargetNames.size.coerceAtLeast(1) + workers - 1) / workers
    return rounds * perTest + SingBoxDelayDeadlineGraceMillis
}

internal fun SingBoxDelayTestPlan.freshDelays(
    proxies: SingBoxProxiesState,
    baselineTimes: Map<String, Long>,
): Map<String, Int> = targetNames.mapNotNull { name ->
    val node = proxies.nodeByName[name] ?: return@mapNotNull null
    val updatedAt = node.delayUpdatedAtEpochSeconds ?: return@mapNotNull null
    val baseline = baselineTimes[name] ?: Long.MIN_VALUE
    node.delay
        ?.takeIf { delay -> delay >= 0 && updatedAt > baseline }
        ?.let { delay -> name to delay }
}.toMap()

@OptIn(ExperimentalCoroutinesApi::class)
internal suspend fun awaitSingBoxDelayTestSnapshot(
    runtimeStates: Flow<SingBoxRuntimeState>,
    plan: SingBoxDelayTestPlan,
    baselineTimes: Map<String, Long>,
    knownFailures: Set<String>,
    idleTimeoutMillis: Long,
    hardTimeoutMillis: Long,
): SingBoxDelayAwaitResult {
    var latestProxies = SingBoxProxiesState()
    var observedFreshDelays = emptyMap<String, Int>()
    val completed = withTimeoutOrNull(hardTimeoutMillis.milliseconds) {
        runtimeStates
            .map { runtime ->
                check(runtime.running) { "sing-box API disconnected during delay test" }
                latestProxies = runtime.proxies
                observedFreshDelays = observedFreshDelays +
                    plan.freshDelays(runtime.proxies, baselineTimes)
                SingBoxDelayObservation(
                    proxies = runtime.proxies,
                    freshDelays = observedFreshDelays,
                )
            }
            .distinctUntilChangedBy { observation -> observation.freshDelays.keys }
            .transformLatest { observation ->
                val resolvedTargets = observation.freshDelays.keys + knownFailures
                if (resolvedTargets.containsAll(plan.targetNames)) {
                    emit(
                        SingBoxDelayAwaitResult(
                            proxies = observation.proxies,
                            completion = SingBoxDelayAwaitCompletion.Resolved,
                            freshDelays = observation.freshDelays,
                        ),
                    )
                } else {
                    delay(idleTimeoutMillis.milliseconds)
                    emit(
                        SingBoxDelayAwaitResult(
                            proxies = observation.proxies,
                            completion = SingBoxDelayAwaitCompletion.SoftTimedOut,
                            freshDelays = observation.freshDelays,
                        ),
                    )
                }
            }
            .first()
    }
    return completed
        ?.copy(proxies = latestProxies, freshDelays = observedFreshDelays)
        ?: SingBoxDelayAwaitResult(
            proxies = latestProxies,
            completion = SingBoxDelayAwaitCompletion.HardTimedOut,
            freshDelays = observedFreshDelays,
        )
}

private data class SingBoxDelayObservation(
    val proxies: SingBoxProxiesState,
    val freshDelays: Map<String, Int>,
)

internal fun SingBoxDelayTestPlan.finish(
    delays: Map<String, Int>,
): SingBoxDelayResult = SingBoxDelayResult(
    delays = delays,
    failedTargets = targetNames - delays.keys,
)

internal fun mergeSingBoxDelayFailures(
    currentFailureBaselines: Map<String, Long>,
    result: SingBoxDelayResult,
    runBaselines: Map<String, Long>,
): Map<String, Long> {
    val merged = currentFailureBaselines.toMutableMap()
    result.delays.keys.forEach(merged::remove)
    result.failedTargets.forEach { name ->
        merged[name] = maxOf(
            currentFailureBaselines[name] ?: Long.MIN_VALUE,
            runBaselines[name] ?: Long.MIN_VALUE,
        )
    }
    return merged
}

private fun String?.isSingBoxDelayGroupType(): Boolean {
    val normalized = this.orEmpty()
        .trim()
        .lowercase()
        .replace("-", "")
        .replace("_", "")
        .replace(" ", "")
    return normalized in SingBoxDelayGroupTypes
}

private val SingBoxDelayGroupTypes = setOf(
    "select",
    "selector",
    "urltest",
    "fallback",
)

private const val SingBoxDelayPerTestOverheadMillis = 2_000L
private const val SingBoxDelayDeadlineGraceMillis = 2_000L
