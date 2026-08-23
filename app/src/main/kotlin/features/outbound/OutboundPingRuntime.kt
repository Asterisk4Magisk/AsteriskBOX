// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.outbound

import app.OutboundState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlin.time.Duration.Companion.milliseconds

internal data class OutboundPingIdentity(
    val outboundId: Int,
    val json: String,
)

internal enum class OutboundPingStatus {
    Testing,
    Measured,
    Failed,
}

internal data class OutboundPingEntry(
    val identity: OutboundPingIdentity,
    val status: OutboundPingStatus,
    val latencyMillis: Long? = null,
    val batchId: Long,
)

internal data class OutboundPingRuntimeState(
    val entries: Map<Int, OutboundPingEntry> = emptyMap(),
    val runningIds: Set<Int> = emptySet(),
)

internal class OutboundPingRuntimeRepository(
    private val scope: CoroutineScope,
    private val pinger: OutboundPinger,
    private val concurrency: Int = 8,
    private val snapshotIntervalMillis: Long = 1_000L,
) {
    private val lock = Any()
    private val mutableState = MutableStateFlow(OutboundPingRuntimeState())
    private var nextBatchId = 0L
    private val activeBatchIds = mutableSetOf<Long>()
    private val pendingCompletions = mutableListOf<Completion>()
    private var nextPublisherToken = 0L
    private var publisherToken: Long? = null
    private var publisherJob: Job? = null
    private val semaphore: Semaphore

    val state: StateFlow<OutboundPingRuntimeState> = mutableState.asStateFlow()

    init {
        require(concurrency > 0) { "concurrency must be positive" }
        require(snapshotIntervalMillis >= 0L) { "snapshotIntervalMillis must not be negative" }
        semaphore = Semaphore(concurrency)
    }

    fun start(
        targets: List<OutboundState>,
        supersede: Boolean = false,
    ): Job? {
        val distinctTargets = targets.distinctBy(OutboundState::id)
        if (distinctTargets.isEmpty()) return null

        val plan = synchronized(lock) {
            val current = mutableState.value
            if (!supersede && distinctTargets.any { outbound -> outbound.id in current.runningIds }) {
                return null
            }
            val identities = distinctTargets.associate { outbound ->
                outbound.id to OutboundPingIdentity(outbound.id, outbound.json)
            }
            val batchId = ++nextBatchId
            val targetIds = identities.keys
            mutableState.value = current.copy(
                entries = current.entries + identities.mapValues { (outboundId, identity) ->
                    OutboundPingEntry(
                        identity = identity,
                        status = OutboundPingStatus.Testing,
                        batchId = batchId,
                    )
                },
                runningIds = current.runningIds + targetIds,
            )
            activeBatchIds += batchId
            RunPlan(
                batchId = batchId,
                targets = distinctTargets,
                identities = identities,
            )
        }

        return scope.launch {
            var cancelled = false

            try {
                supervisorScope {
                    plan.targets.map { outbound ->
                        launch {
                            val latencyMillis = semaphore.withPermit {
                                pingOrFailure { pinger.ping(outbound) }
                            }
                            recordCompletion(
                                Completion(
                                    identity = plan.identities.getValue(outbound.id),
                                    batchId = plan.batchId,
                                    latencyMillis = latencyMillis,
                                )
                            )
                        }
                    }.joinAll()
                }
            } catch (error: CancellationException) {
                cancelled = true
                throw error
            } finally {
                synchronized(lock) {
                    activeBatchIds -= plan.batchId
                    if (cancelled) {
                        publishPendingForBatchLocked(plan.batchId)
                        clearTestingEntriesLocked(plan)
                    }
                    if (activeBatchIds.isEmpty()) {
                        flushPendingLocked()
                    }
                }
            }
        }
    }

    fun invalidate(outboundId: Int, currentJson: String) {
        synchronized(lock) {
            val current = mutableState.value
            val entry = current.entries[outboundId] ?: return
            if (entry.identity.json == currentJson) return
            mutableState.value = current.copy(
                entries = current.entries - outboundId,
                runningIds = current.runningIds - outboundId,
            )
        }
    }

    fun remove(outboundId: Int) {
        synchronized(lock) {
            val current = mutableState.value
            if (outboundId !in current.entries && outboundId !in current.runningIds) return
            mutableState.value = current.copy(
                entries = current.entries - outboundId,
                runningIds = current.runningIds - outboundId,
            )
        }
    }

    fun reconcile(current: List<OutboundState>) {
        val currentJsonById = current.associate { outbound -> outbound.id to outbound.json }
        synchronized(lock) {
            val previous = mutableState.value
            val retainedEntries = previous.entries.filter { (outboundId, entry) ->
                currentJsonById[outboundId] == entry.identity.json
            }
            val retainedIds = retainedEntries.keys
            mutableState.value = previous.copy(
                entries = retainedEntries,
                runningIds = previous.runningIds.intersect(retainedIds),
            )
        }
    }

    private fun recordCompletion(completion: Completion) {
        var newPublisherToken: Long? = null
        synchronized(lock) {
            pendingCompletions += completion
            if (snapshotIntervalMillis == 0L) {
                publishPendingLocked()
            } else if (publisherToken == null) {
                newPublisherToken = ++nextPublisherToken
                publisherToken = newPublisherToken
            }
        }
        newPublisherToken?.let(::launchPublisher)
    }

    private fun launchPublisher(token: Long) {
        val job = scope.launch(start = CoroutineStart.LAZY) {
            delay(snapshotIntervalMillis.milliseconds)
            synchronized(lock) {
                if (publisherToken == token) {
                    publisherToken = null
                    publisherJob = null
                    publishPendingLocked()
                }
            }
        }
        synchronized(lock) {
            if (publisherToken == token && publisherJob == null) {
                publisherJob = job
                job.start()
            } else {
                job.cancel()
            }
        }
    }

    private fun flushPendingLocked() {
        publisherToken = null
        publisherJob?.cancel()
        publisherJob = null
        publishPendingLocked()
    }

    private fun publishPendingLocked() {
        if (pendingCompletions.isEmpty()) return
        val completions = pendingCompletions.toList()
        pendingCompletions.clear()
        publishCompletionsLocked(completions)
    }

    private fun publishPendingForBatchLocked(batchId: Long) {
        val completions = pendingCompletions.filter { completion ->
            completion.batchId == batchId
        }
        if (completions.isEmpty()) return
        pendingCompletions.removeAll(completions.toSet())
        publishCompletionsLocked(completions)
    }

    private fun publishCompletionsLocked(completions: List<Completion>) {
        val current = mutableState.value
        var entries = current.entries
        var runningIds = current.runningIds
        completions.forEach { completion ->
            val outboundId = completion.identity.outboundId
            val entry = entries[outboundId]
            if (entry?.batchId == completion.batchId && entry.identity == completion.identity) {
                entries = entries + (
                    outboundId to entry.copy(
                        status = if (completion.latencyMillis >= 0L) {
                            OutboundPingStatus.Measured
                        } else {
                            OutboundPingStatus.Failed
                        },
                        latencyMillis = completion.latencyMillis,
                    )
                )
                runningIds -= outboundId
            }
        }
        mutableState.value = current.copy(entries = entries, runningIds = runningIds)
    }

    private fun clearTestingEntriesLocked(plan: RunPlan) {
        val current = mutableState.value
        val testingIds = plan.identities
            .filter { (outboundId, identity) ->
                current.entries[outboundId]?.let { entry ->
                    entry.batchId == plan.batchId &&
                        entry.identity == identity &&
                        entry.status == OutboundPingStatus.Testing
                } == true
            }
            .keys
        if (testingIds.isNotEmpty()) {
            mutableState.value = current.copy(
                entries = current.entries - testingIds,
                runningIds = current.runningIds - testingIds,
            )
        }
    }

    private data class Completion(
        val identity: OutboundPingIdentity,
        val batchId: Long,
        val latencyMillis: Long,
    )

    private data class RunPlan(
        val batchId: Long,
        val targets: List<OutboundState>,
        val identities: Map<Int, OutboundPingIdentity>,
    )
}
