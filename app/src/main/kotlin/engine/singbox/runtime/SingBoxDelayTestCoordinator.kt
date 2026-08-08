// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package engine.singbox.runtime

import kotlinx.coroutines.ExperimentalCoroutinesApi
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
    val commandGroupNames: List<String>,
    val targetNames: Set<String>,
    val directTargetNamesByCommand: Map<String, Set<String>>,
    val maxDirectTargetCount: Int,
    val baselineTimes: Map<String, Long>,
)

internal data class SingBoxDelayCommandSubmissions(
    val successfulGroupNames: Set<String>,
    val failedGroupNames: Set<String>,
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

    val commandGroupNames = linkedSetOf<String>()
    val targetNames = linkedSetOf<String>()

    fun isGroup(name: String): Boolean {
        return name in groupsByName || proxies.nodeByName[name]?.type.isSingBoxDelayGroupType()
    }

    fun visit(groupName: String) {
        if (!commandGroupNames.add(groupName)) return
        val group = groupsByName[groupName] ?: return
        group.all.forEach { memberName ->
            targetNames += memberName
            if (isGroup(memberName)) {
                visit(memberName)
            }
        }
    }

    visit(rootGroupName)

    val directTargetNamesByCommand = commandGroupNames.associateWith { groupName ->
        buildSet {
            if (groupName in targetNames) add(groupName)
            groupsByName[groupName]?.all
                ?.filterNot(::isGroup)
                ?.let(::addAll)
        }
    }
    return SingBoxDelayTestPlan(
        commandGroupNames = commandGroupNames.toList(),
        targetNames = targetNames,
        directTargetNamesByCommand = directTargetNamesByCommand,
        maxDirectTargetCount = commandGroupNames.maxOfOrNull { groupName ->
            groupsByName[groupName]?.all?.count { memberName -> !isGroup(memberName) } ?: 0
        } ?: 0,
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
        commandGroupNames = listOf(proxyName),
        targetNames = setOf(proxyName),
        directTargetNamesByCommand = mapOf(proxyName to setOf(proxyName)),
        maxDirectTargetCount = 1,
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

internal fun submitSingBoxDelayCommands(
    commandGroupNames: List<String>,
    submit: (String) -> Unit,
): SingBoxDelayCommandSubmissions {
    val successfulGroupNames = linkedSetOf<String>()
    val failedGroupNames = linkedSetOf<String>()
    commandGroupNames.distinct().forEach { groupName ->
        runCatching { submit(groupName) }
            .onSuccess { successfulGroupNames += groupName }
            .onFailure { failedGroupNames += groupName }
    }
    return SingBoxDelayCommandSubmissions(
        successfulGroupNames = successfulGroupNames,
        failedGroupNames = failedGroupNames,
    )
}

internal fun SingBoxDelayTestPlan.knownSubmissionFailures(
    submissions: SingBoxDelayCommandSubmissions,
): Set<String> {
    val successfulCoverage = submissions.successfulGroupNames
        .flatMapTo(linkedSetOf()) { groupName ->
            directTargetNamesByCommand[groupName].orEmpty()
        }
    return submissions.failedGroupNames
        .flatMapTo(linkedSetOf()) { groupName ->
            directTargetNamesByCommand[groupName].orEmpty()
        }
        .minus(successfulCoverage)
}

internal fun SingBoxDelayTestPlan.deadlineMillis(): Long {
    val batches = (maxDirectTargetCount.coerceAtLeast(1) + SingBoxDelayBatchSize - 1) /
        SingBoxDelayBatchSize
    return batches * SingBoxDelayBatchTimeoutMillis + SingBoxDelayDeadlineGraceMillis
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

private const val SingBoxDelayBatchSize = 10
private const val SingBoxDelayBatchTimeoutMillis = 15_000L
private const val SingBoxDelayDeadlineGraceMillis = 2_000L
