// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package engine.singbox.runtime

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.transformLatest
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
    val selectedLeaves: Map<String, String> = emptyMap(),
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

/**
 * Single-node tests share the scheduler, including an already running automatic probe.
 * One group batch may run alongside them; starting that batch still preempts the probe.
 */
internal class SingBoxDelayTestRunGate {
    private val lock = Any()
    private data class Entry(val nodeName: String?, val probe: Job?)
    private val owners = mutableMapOf<Any, Entry>()

    suspend fun acquire(nodeName: String? = null): SingBoxDelayTestRunLease {
        while (true) {
            val probe = synchronized(lock) {
                check(owners.values.none { it.nodeName == nodeName }) { "sing-box delay test is already running" }
                val automatic = owners.values.firstNotNullOfOrNull(Entry::probe)
                if (nodeName != null || automatic == null) return take(nodeName, probe = null)
                automatic
            }
            probe.cancelAndJoin()
        }
    }

    /** Takes the gate for [probe] only when it is free; the probe never waits or preempts. */
    fun tryAcquirePreemptible(probe: Job, nodeName: String): SingBoxDelayTestRunLease? = synchronized(lock) {
        if (owners.isNotEmpty()) null else take(nodeName, probe)
    }

    private fun take(nodeName: String?, probe: Job?): SingBoxDelayTestRunLease {
        val token = Any()
        owners[token] = Entry(nodeName, probe)
        return SingBoxDelayTestRunLease(this, token)
    }

    internal fun release(token: Any) {
        synchronized(lock) {
            owners.remove(token)
        }
    }
}

/**
 * Rate limit for background probes, per target: the same tag is probed at most once per
 * [intervalMillis], while a different tag is always let through. Lives with the runtime, not the
 * UI, so recreating the activity does not reset it.
 */
internal class SingBoxDelayProbeThrottle(private val intervalMillis: Long) {
    private val lock = Any()
    private var lastTarget: String? = null
    private var lastAtMillis = 0L

    fun take(target: String, nowMillis: Long): Boolean = synchronized(lock) {
        if (target == lastTarget && nowMillis - lastAtMillis < intervalMillis) return false
        lastTarget = target
        lastAtMillis = nowMillis
        true
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
        selectedLeaves = targetNames.mapNotNull { name -> proxies.delayTestLeaf(name)?.let { name to it } }.toMap(),
    )
}

internal fun buildSingBoxProxyDelayTestPlan(
    proxies: SingBoxProxiesState,
    proxyName: String,
): SingBoxDelayTestPlan {
    val leaf = requireNotNull(proxies.delayTestLeaf(proxyName)) {
        "sing-box proxy is unavailable: $proxyName"
    }
    require(proxies.nodeByName.containsKey(leaf)) { "sing-box proxy is unavailable: $leaf" }
    val targets = setOf(proxyName, leaf)
    return SingBoxDelayTestPlan(
        leafTargetNames = listOf(leaf),
        targetNames = targets,
        baselineTimes = targets.associateWith { proxies.nodeByName[it]?.delayUpdatedAtEpochSeconds ?: Long.MIN_VALUE },
        selectedLeaves = mapOf(proxyName to leaf),
    )
}

internal fun SingBoxProxiesState.delayTestLeaf(name: String): String? {
    var current = name
    val visited = mutableSetOf<String>()
    repeat(groups.size + 1) {
        val group = groups.firstOrNull { it.name == current } ?: return current
        if (!visited.add(group.name) || group.now.isBlank()) return null
        current = group.now
    }
    return null
}

internal fun SingBoxDelayTestPlan.freshnessBaselines(
    failureBaselines: Map<String, Long>,
): Map<String, Long> = targetNames.associateWith { name ->
    maxOf(
        baselineTimes[name] ?: Long.MIN_VALUE,
        failureBaselines[name] ?: Long.MIN_VALUE,
    )
}

internal fun singBoxDelayTimestampWaitMillis(baselineSeconds: Long, nowMillis: Long): Long {
    val nowSeconds = nowMillis / 1_000L
    return if (baselineSeconds < nowSeconds) 0L else (nowSeconds + 1L) * 1_000L - nowMillis + 1L
}

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
): SingBoxDelayResult {
    val resolved = targetNames.mapNotNull { name ->
        (delays[name] ?: selectedLeaves[name]?.let(delays::get))?.let { name to it }
    }.toMap()
    return SingBoxDelayResult(delays = resolved, failedTargets = targetNames - resolved.keys)
}

internal fun SingBoxDelayTestPlan.leafResult(result: SingBoxDelayResult): SingBoxDelayResult {
    val measured = result.delays.keys + result.failedTargets
    val targets = targetNames.filterTo(linkedSetOf()) { (selectedLeaves[it] ?: it) in measured }
    val delays = targets.mapNotNull { name ->
        (result.delays[name] ?: selectedLeaves[name]?.let(result.delays::get))?.let { name to it }
    }.toMap()
    return SingBoxDelayResult(delays, targets - delays.keys)
}

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
