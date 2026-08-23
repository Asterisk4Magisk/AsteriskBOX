// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.subscription.usecase

import app.AppState
import app.OutboundGroupState
import app.OutboundGroupUpdateStatus
import features.importing.ImportOutcome
import features.importing.ImportStage
import features.importing.sanitizePersistedImportSummary
import features.outbound.ImportedSingBoxOutbound
import features.outbound.planOutboundImport
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

internal enum class SubscriptionUpdateTrigger {
    MANUAL,
    BATCH,
    BACKGROUND,
}

internal sealed interface OutboundSubscriptionUpdateResult {
    val isSuccessfulCheck: Boolean

    data class Success(
        val outcome: ImportOutcome<ImportedSingBoxOutbound>,
    ) : OutboundSubscriptionUpdateResult {
        override val isSuccessfulCheck: Boolean = true
    }

    data class Partial(
        val outcome: ImportOutcome<ImportedSingBoxOutbound>,
    ) : OutboundSubscriptionUpdateResult {
        override val isSuccessfulCheck: Boolean = true
    }

    data object NotModified : OutboundSubscriptionUpdateResult {
        override val isSuccessfulCheck: Boolean = true
    }

    data class Failed(
        val stage: ImportStage,
        val error: Throwable,
        val outcome: ImportOutcome<ImportedSingBoxOutbound>? = null,
    ) : OutboundSubscriptionUpdateResult {
        override val isSuccessfulCheck: Boolean = false
    }

    data class Cancelled(
        val reason: String,
    ) : OutboundSubscriptionUpdateResult {
        override val isSuccessfulCheck: Boolean = false
    }
}

internal interface SubscriptionStateGateway {
    fun snapshot(): AppState

    suspend fun compareAndSet(expected: AppState, updated: AppState): Result<Boolean>
}

internal class OutboundSubscriptionUpdater(
    private val stateGateway: SubscriptionStateGateway,
    private val prepare: suspend (
        group: OutboundGroupState,
        state: AppState,
    ) -> SubscriptionPreparation,
    private val parse: (String) -> ImportOutcome<ImportedSingBoxOutbound>,
    private val validate: suspend (AppState) -> Unit,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val preparePermits: Semaphore = Semaphore(2),
    private val validationCommitMutex: Mutex = Mutex(),
) {
    private val groupMutexes = ConcurrentHashMap<Int, Mutex>()

    suspend fun update(
        groupId: Int,
        trigger: SubscriptionUpdateTrigger,
        onStage: (ImportStage) -> Unit = {},
    ): OutboundSubscriptionUpdateResult {
        val mutex = groupMutexes.computeIfAbsent(groupId) { Mutex() }
        return mutex.withLock {
            updateSingleFlight(groupId, trigger, onStage)
        }
    }

    private suspend fun updateSingleFlight(
        groupId: Int,
        trigger: SubscriptionUpdateTrigger,
        onStage: (ImportStage) -> Unit,
    ): OutboundSubscriptionUpdateResult {
        repeat(MaxFetchAttempts) { fetchAttempt ->
            val initialState = stateGateway.snapshot()
            val group = initialState.outboundGroups.firstOrNull { it.id == groupId }
                ?: return OutboundSubscriptionUpdateResult.Cancelled("Subscription group was deleted")
            if (trigger == SubscriptionUpdateTrigger.BACKGROUND && !group.enabled) {
                return OutboundSubscriptionUpdateResult.Cancelled(
                    "Disabled subscription group was not updated",
                )
            }
            val prepared = try {
                onStage(ImportStage.DOWNLOAD)
                preparePermits.withPermit {
                    prepare(group, initialState)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                return recordFailure(
                    groupId = groupId,
                    trigger = trigger,
                    stage = ImportStage.DOWNLOAD,
                    error = error,
                )
            }

            when (prepared) {
                is SubscriptionPreparation.Failure -> {
                    return recordFailure(
                        groupId = groupId,
                        trigger = trigger,
                        stage = prepared.stage.toImportStage(),
                        error = prepared.error,
                    )
                }

                is SubscriptionPreparation.NotModified -> {
                    val commit = validationCommitMutex.withLock {
                        commitNotModified(
                            groupId = groupId,
                            trigger = trigger,
                            fetchedGroup = group,
                        )
                    }
                    if (commit === CommitResult.Refetch && fetchAttempt + 1 < MaxFetchAttempts) {
                        return@repeat
                    }
                    return commit.toPublicResult()
                }

                is SubscriptionPreparation.Success -> {
                    val outcome = try {
                        onStage(ImportStage.PARSE)
                        preparePermits.withPermit {
                            parse(prepared.content)
                        }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Throwable) {
                        return recordFailure(
                            groupId = groupId,
                            trigger = trigger,
                            stage = ImportStage.PARSE,
                            error = error,
                        )
                    }
                    val commit = validationCommitMutex.withLock {
                        commitParsed(
                            groupId = groupId,
                            trigger = trigger,
                            fetchedGroup = group,
                            prepared = prepared,
                            outcome = outcome,
                            onStage = onStage,
                        )
                    }
                    if (commit === CommitResult.Refetch && fetchAttempt + 1 < MaxFetchAttempts) {
                        return@repeat
                    }
                    return commit.toPublicResult()
                }
            }
        }
        return OutboundSubscriptionUpdateResult.Cancelled(
            "Subscription settings changed during update",
        )
    }

    private suspend fun commitNotModified(
        groupId: Int,
        trigger: SubscriptionUpdateTrigger,
        fetchedGroup: OutboundGroupState,
    ): CommitResult {
        repeat(MaxCommitAttempts) {
            val snapshot = stateGateway.snapshot()
            val currentGroup = snapshot.currentGroupOrCancelled(groupId, trigger)
                ?: return CommitResult.Cancelled
            if (currentGroup.fetchIdentity() != fetchedGroup.fetchIdentity()) {
                return CommitResult.Refetch
            }
            val now = nowMillis()
            val candidate = snapshot.withUpdatedGroup(groupId) { group ->
                group.copy(
                    lastUpdateAttemptAtMillis = now,
                    lastUpdatedAtMillis = now,
                    lastUpdateStatus = OutboundGroupUpdateStatus.NOT_MODIFIED,
                    lastUpdateImportedCount = 0,
                    lastUpdateSkippedCount = 0,
                    lastUpdateDuplicateCount = 0,
                    consecutiveUpdateFailures = 0,
                    lastUpdateErrorSummary = "",
                )
            }
            when (val commit = commitState(snapshot, candidate)) {
                PersistenceAttempt.Persisted -> return CommitResult.NotModified
                PersistenceAttempt.Conflict -> Unit
                is PersistenceAttempt.Failed -> {
                    return CommitResult.Failed(
                        stage = ImportStage.COMMIT,
                        error = commit.error,
                    )
                }
            }
        }
        return CommitResult.Failed(
            stage = ImportStage.COMMIT,
            error = IllegalStateException(StateChangedMessage),
        )
    }

    private suspend fun commitParsed(
        groupId: Int,
        trigger: SubscriptionUpdateTrigger,
        fetchedGroup: OutboundGroupState,
        prepared: SubscriptionPreparation.Success,
        outcome: ImportOutcome<ImportedSingBoxOutbound>,
        onStage: (ImportStage) -> Unit,
    ): CommitResult {
        repeat(MaxCommitAttempts) {
            val snapshot = stateGateway.snapshot()
            val currentGroup = snapshot.currentGroupOrCancelled(groupId, trigger)
                ?: return CommitResult.Cancelled
            if (currentGroup.fetchIdentity() != fetchedGroup.fetchIdentity()) {
                return CommitResult.Refetch
            }
            val plan = snapshot.planOutboundImport(
                groupId = groupId,
                parsed = outcome,
                replaceGroup = true,
                strict = currentGroup.strictImport,
            )
            if (!plan.committed) {
                val message = plan.outcome.issues.lastOrNull()?.message
                    ?: "No supported proxy outbounds were accepted"
                val failed = snapshot.failureCandidate(
                    groupId = groupId,
                    now = nowMillis(),
                    message = message,
                    skippedCount = plan.outcome.skippedCount,
                    duplicateCount = plan.outcome.duplicateCount,
                )
                when (val commit = commitState(snapshot, failed)) {
                    PersistenceAttempt.Persisted -> {
                        return CommitResult.Failed(
                            stage = ImportStage.VALIDATE,
                            error = IllegalStateException(message),
                            outcome = plan.outcome,
                        )
                    }
                    PersistenceAttempt.Conflict -> return@repeat
                    is PersistenceAttempt.Failed -> {
                        return CommitResult.Failed(
                            stage = ImportStage.COMMIT,
                            error = commit.error,
                            outcome = plan.outcome,
                        )
                    }
                }
            }

            val isPartial = plan.outcome.skippedCount > 0
            val now = nowMillis()
            val summary = if (isPartial) {
                plan.outcome.issues.firstOrNull()?.message.orEmpty()
            } else {
                ""
            }
            val candidate = plan.state.withUpdatedGroup(groupId) { group ->
                group.copy(
                    lastUpdateAttemptAtMillis = now,
                    lastUpdatedAtMillis = now,
                    lastUpdateStatus = if (isPartial) {
                        OutboundGroupUpdateStatus.PARTIAL
                    } else {
                        OutboundGroupUpdateStatus.SUCCESS
                    },
                    lastUpdateImportedCount = plan.outcome.accepted.size,
                    lastUpdateSkippedCount = plan.outcome.skippedCount,
                    lastUpdateDuplicateCount = plan.outcome.duplicateCount,
                    consecutiveUpdateFailures = 0,
                    lastUpdateErrorSummary = sanitizePersistedImportSummary(summary),
                    subscriptionEtag = prepared.etag,
                    subscriptionLastModified = prepared.lastModified,
                )
            }
            try {
                onStage(ImportStage.VALIDATE)
                validate(candidate)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                val failed = snapshot.failureCandidate(
                    groupId = groupId,
                    now = now,
                    message = error.message ?: "Subscription validation failed",
                    skippedCount = plan.outcome.skippedCount,
                    duplicateCount = plan.outcome.duplicateCount,
                )
                when (val commit = commitState(snapshot, failed)) {
                    PersistenceAttempt.Persisted -> {
                        return CommitResult.Failed(
                            stage = ImportStage.VALIDATE,
                            error = error,
                            outcome = plan.outcome,
                        )
                    }
                    PersistenceAttempt.Conflict -> return@repeat
                    is PersistenceAttempt.Failed -> {
                        return CommitResult.Failed(
                            stage = ImportStage.COMMIT,
                            error = commit.error,
                            outcome = plan.outcome,
                        )
                    }
                }
            }
            onStage(ImportStage.COMMIT)
            when (val commit = commitState(snapshot, candidate)) {
                PersistenceAttempt.Persisted -> {
                    return if (isPartial) {
                        CommitResult.Partial(plan.outcome)
                    } else {
                        CommitResult.Success(plan.outcome)
                    }
                }
                PersistenceAttempt.Conflict -> Unit
                is PersistenceAttempt.Failed -> {
                    return CommitResult.Failed(
                        stage = ImportStage.COMMIT,
                        error = commit.error,
                        outcome = plan.outcome,
                    )
                }
            }
        }
        return CommitResult.Failed(
            stage = ImportStage.COMMIT,
            error = IllegalStateException(StateChangedMessage),
            outcome = outcome,
        )
    }

    private suspend fun recordFailure(
        groupId: Int,
        trigger: SubscriptionUpdateTrigger,
        stage: ImportStage,
        error: Throwable,
        skippedCount: Int = 0,
        duplicateCount: Int = 0,
    ): OutboundSubscriptionUpdateResult = validationCommitMutex.withLock {
        val message = error.message ?: "Subscription update failed"
        repeat(MaxCommitAttempts) {
            val snapshot = stateGateway.snapshot()
            if (snapshot.currentGroupOrCancelled(groupId, trigger) == null) {
                return@withLock OutboundSubscriptionUpdateResult.Cancelled(
                    "Subscription group is no longer eligible for update",
                )
            }
            val candidate = snapshot.failureCandidate(
                groupId = groupId,
                now = nowMillis(),
                message = message,
                skippedCount = skippedCount,
                duplicateCount = duplicateCount,
            )
            when (val commit = commitState(snapshot, candidate)) {
                PersistenceAttempt.Persisted -> {
                    return@withLock OutboundSubscriptionUpdateResult.Failed(
                        stage = stage,
                        error = error,
                    )
                }
                PersistenceAttempt.Conflict -> Unit
                is PersistenceAttempt.Failed -> {
                    return@withLock OutboundSubscriptionUpdateResult.Failed(
                        stage = ImportStage.COMMIT,
                        error = commit.error,
                    )
                }
            }
        }
        OutboundSubscriptionUpdateResult.Failed(
            stage = ImportStage.COMMIT,
            error = IllegalStateException(StateChangedMessage),
        )
    }

    private fun AppState.currentGroupOrCancelled(
        groupId: Int,
        trigger: SubscriptionUpdateTrigger,
    ): OutboundGroupState? {
        val group = outboundGroups.firstOrNull { it.id == groupId } ?: return null
        if (trigger == SubscriptionUpdateTrigger.BACKGROUND && !group.enabled) return null
        return group
    }

    private suspend fun commitState(
        expected: AppState,
        candidate: AppState,
    ): PersistenceAttempt {
        val result = stateGateway.compareAndSet(expected, candidate)
        val error = result.exceptionOrNull()
        if (error != null) return PersistenceAttempt.Failed(error)
        return if (result.getOrThrow()) {
            PersistenceAttempt.Persisted
        } else {
            PersistenceAttempt.Conflict
        }
    }

    private fun AppState.withUpdatedGroup(
        groupId: Int,
        transform: (OutboundGroupState) -> OutboundGroupState,
    ): AppState = copy(
        outboundGroups = outboundGroups.map { group ->
            if (group.id == groupId) transform(group) else group
        },
    )

    private fun AppState.failureCandidate(
        groupId: Int,
        now: Long,
        message: String,
        skippedCount: Int,
        duplicateCount: Int,
    ): AppState = withUpdatedGroup(groupId) { group ->
        group.copy(
            lastUpdateAttemptAtMillis = now,
            lastUpdateStatus = OutboundGroupUpdateStatus.FAILED,
            lastUpdateImportedCount = 0,
            lastUpdateSkippedCount = skippedCount,
            lastUpdateDuplicateCount = duplicateCount,
            consecutiveUpdateFailures = group.consecutiveUpdateFailures + 1,
            lastUpdateErrorSummary = sanitizePersistedImportSummary(message),
        )
    }

    private fun OutboundGroupState.fetchIdentity(): FetchIdentity = FetchIdentity(
        url = url,
        userAgent = userAgent,
        hwid = hwid,
        updateViaProxy = updateViaProxy,
        ageSecretKey = ageSecretKey,
        etag = subscriptionEtag,
        lastModified = subscriptionLastModified,
    )

    private fun SubscriptionSyncStage.toImportStage(): ImportStage = when (this) {
        SubscriptionSyncStage.Downloading -> ImportStage.DOWNLOAD
        SubscriptionSyncStage.Decrypting -> ImportStage.DECRYPT
        SubscriptionSyncStage.Verifying -> ImportStage.VALIDATE
    }

    private sealed interface CommitResult {
        data class Success(
            val outcome: ImportOutcome<ImportedSingBoxOutbound>,
        ) : CommitResult

        data class Partial(
            val outcome: ImportOutcome<ImportedSingBoxOutbound>,
        ) : CommitResult

        data object NotModified : CommitResult
        data object Refetch : CommitResult
        data object Cancelled : CommitResult

        data class Failed(
            val stage: ImportStage,
            val error: Throwable,
            val outcome: ImportOutcome<ImportedSingBoxOutbound>? = null,
        ) : CommitResult
    }

    private sealed interface PersistenceAttempt {
        data object Persisted : PersistenceAttempt
        data object Conflict : PersistenceAttempt
        data class Failed(val error: Throwable) : PersistenceAttempt
    }

    private fun CommitResult.toPublicResult(): OutboundSubscriptionUpdateResult = when (this) {
        is CommitResult.Success -> OutboundSubscriptionUpdateResult.Success(outcome)
        is CommitResult.Partial -> OutboundSubscriptionUpdateResult.Partial(outcome)
        CommitResult.NotModified -> OutboundSubscriptionUpdateResult.NotModified
        CommitResult.Refetch -> OutboundSubscriptionUpdateResult.Cancelled(
            "Subscription settings changed during update",
        )
        CommitResult.Cancelled -> OutboundSubscriptionUpdateResult.Cancelled(
            "Subscription group is no longer eligible for update",
        )
        is CommitResult.Failed -> OutboundSubscriptionUpdateResult.Failed(
            stage = stage,
            error = error,
            outcome = outcome,
        )
    }

    private data class FetchIdentity(
        val url: String,
        val userAgent: String,
        val hwid: String,
        val updateViaProxy: Boolean,
        val ageSecretKey: String,
        val etag: String,
        val lastModified: String,
    )

    private companion object {
        const val MaxFetchAttempts = 2
        const val MaxCommitAttempts = 2
        const val StateChangedMessage = "Application state changed during subscription update"
    }
}
