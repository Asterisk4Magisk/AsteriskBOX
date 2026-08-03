// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package engine.singbox.runtime

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
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

internal class SingBoxDelayTestRunGate {
    private val mutex = Mutex()

    suspend fun <T> runExclusive(block: suspend () -> T): T {
        check(mutex.tryLock()) { "sing-box delay test is already running" }
        return try {
            block()
        } finally {
            mutex.unlock()
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

internal fun SingBoxDelayTestPlan.isResolved(
    proxies: SingBoxProxiesState,
    baselineTimes: Map<String, Long>,
    knownFailures: Set<String>,
): Boolean {
    val resolvedTargets = freshDelays(proxies, baselineTimes).keys + knownFailures
    return resolvedTargets.containsAll(targetNames)
}

internal suspend fun awaitSingBoxDelayTestSnapshot(
    runtimeStates: Flow<SingBoxRuntimeState>,
    plan: SingBoxDelayTestPlan,
    baselineTimes: Map<String, Long>,
    knownFailures: Set<String>,
    timeoutMillis: Long,
): SingBoxProxiesState? = withTimeoutOrNull(timeoutMillis.milliseconds) {
    val runtime = runtimeStates.first { state ->
        !state.running || plan.isResolved(
            proxies = state.proxies,
            baselineTimes = baselineTimes,
            knownFailures = knownFailures,
        )
    }
    check(runtime.running) { "sing-box API disconnected during delay test" }
    runtime.proxies
}

internal fun SingBoxDelayTestPlan.finish(
    proxies: SingBoxProxiesState,
    baselineTimes: Map<String, Long>,
): SingBoxDelayResult {
    val delays = freshDelays(proxies, baselineTimes)
    return SingBoxDelayResult(
        delays = delays,
        failedTargets = targetNames - delays.keys,
    )
}

internal fun retainUnresolvedSingBoxDelayFailures(
    failedTargetBaselines: Map<String, Long>,
    proxies: SingBoxProxiesState,
): Map<String, Long> = failedTargetBaselines.filterTo(linkedMapOf()) { (name, baseline) ->
    val node = proxies.nodeByName[name]
    node?.delay?.let { delay -> delay >= 0 } != true ||
        node.delayUpdatedAtEpochSeconds?.let { updatedAt -> updatedAt > baseline } != true
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

private const val SingBoxDelayBatchSize = 10
private const val SingBoxDelayBatchTimeoutMillis = 15_000L
private const val SingBoxDelayDeadlineGraceMillis = 2_000L
