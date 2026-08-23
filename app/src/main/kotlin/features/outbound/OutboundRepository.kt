// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.outbound

import app.AppState
import app.OutboundGroupState
import app.OutboundState
import app.managedOutboundGroupSelectorTag
import app.withRemovedManagedOutbound
import app.withRemovedManagedOutboundTags
import app.withReplacedManagedTag
import features.importing.importFingerprint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal interface OutboundStateGateway {
    fun snapshot(): AppState

    suspend fun commitPreparedAndAwaitPersistence(
        expected: AppState,
        updated: AppState,
    ): Result<Boolean>
}

internal sealed interface OutboundCommandResult {
    data class Saved(val outbound: OutboundState) : OutboundCommandResult

    data object Deleted : OutboundCommandResult

    data object Reordered : OutboundCommandResult

    data class GroupSaved(val group: OutboundGroupState) : OutboundCommandResult

    data object GroupDeleted : OutboundCommandResult

    data object GroupEnabledChanged : OutboundCommandResult

    data object GroupsReordered : OutboundCommandResult

    data object ImportPersisted : OutboundCommandResult

    data object Conflict : OutboundCommandResult

    data class Invalid(val error: Throwable) : OutboundCommandResult

    data class PersistenceFailed(val error: Throwable) : OutboundCommandResult
}

internal class OutboundRepository(
    private val gateway: OutboundStateGateway,
    private val validate: suspend (AppState) -> Unit,
    private val onOutboundChanged: (Int, String) -> Unit = { _, _ -> },
    private val onOutboundRemoved: (Int) -> Unit = {},
    private val reportRuntimeCallbackFailure: (String, Throwable) -> Unit = { _, _ -> },
) {
    suspend fun save(
        expected: OutboundState?,
        draft: OutboundDraft,
    ): OutboundCommandResult {
        currentCoroutineContext().ensureActive()
        val snapshot = gateway.snapshot()
        val mutation = snapshot.withSavedOutbound(expected, draft)
        if (mutation is OutboundStateMutation.Conflict) {
            return OutboundCommandResult.Conflict
        }
        val applied = mutation as OutboundStateMutation.Applied
        validateCandidate(applied.state)?.let { return it }

        return commitPreparedAndRunPostEffects(snapshot, applied.state) {
            runPostPersistenceCallback(SaveRuntimeInvalidateOperation) {
                onOutboundChanged(applied.outbound.id, applied.outbound.json)
            }
            OutboundCommandResult.Saved(applied.outbound)
        }
    }

    suspend fun delete(outboundId: Int): OutboundCommandResult {
        currentCoroutineContext().ensureActive()
        repeat(MaxCommitAttempts) {
            val snapshot = gateway.snapshot()
            if (snapshot.outbounds.none { outbound -> outbound.id == outboundId }) {
                return OutboundCommandResult.Conflict
            }
            val updated = snapshot.withRemovedManagedOutbound(outboundId)
            when (
                val result = commitPreparedAndRunPostEffects(snapshot, updated) {
                    runPostPersistenceCallback(DeleteRuntimeRemoveOperation) {
                        onOutboundRemoved(outboundId)
                    }
                    OutboundCommandResult.Deleted
                }
            ) {
                OutboundCommandResult.Conflict -> Unit
                else -> return result
            }
        }
        return OutboundCommandResult.Conflict
    }

    suspend fun reorder(
        groupId: Int,
        orderedIds: List<Int>,
    ): OutboundCommandResult {
        currentCoroutineContext().ensureActive()
        repeat(MaxCommitAttempts) {
            val snapshot = gateway.snapshot()
            val currentIds = snapshot.outbounds
                .filter { outbound -> outbound.groupId == groupId }
                .map(OutboundState::id)
            if (
                snapshot.outboundGroups.none { group -> group.id == groupId } ||
                orderedIds.size != currentIds.size ||
                orderedIds.toSet().size != orderedIds.size ||
                orderedIds.toSet() != currentIds.toSet()
            ) {
                return OutboundCommandResult.Conflict
            }
            val updated = snapshot.withReorderedOutboundIds(groupId, orderedIds)
            if (updated === snapshot) {
                return commitPreparedAndRunPostEffects(snapshot, snapshot) {
                    OutboundCommandResult.Reordered
                }
            }
            when (
                val result = commitPreparedAndRunPostEffects(snapshot, updated) {
                    OutboundCommandResult.Reordered
                }
            ) {
                OutboundCommandResult.Conflict -> Unit
                else -> return result
            }
        }
        return OutboundCommandResult.Conflict
    }

    suspend fun persistImport(
        expected: AppState,
        planState: AppState,
    ): OutboundCommandResult {
        currentCoroutineContext().ensureActive()
        if (gateway.snapshot() !== expected) return OutboundCommandResult.Conflict

        val previousById = expected.outbounds.associateBy(OutboundState::id)
        val plannedById = planState.outbounds.associateBy(OutboundState::id)
        val disappeared = expected.outbounds.filter { outbound -> outbound.id !in plannedById }
        var candidate = expected.outbounds
            .asSequence()
            .mapNotNull { previous ->
                plannedById[previous.id]
                    ?.takeIf { planned -> previous.tag != planned.tag }
                    ?.let { planned -> previous.tag to planned.tag }
            }
            .fold(planState) { updated, (previousTag, replacementTag) ->
                updated.withReplacedManagedTag(previousTag, replacementTag)
            }
        candidate = candidate.withRemovedManagedOutboundTags(
            disappeared.mapTo(mutableSetOf(), OutboundState::tag),
        )
        candidate = candidate.copy(
            outbounds = candidate.outbounds.map { planned ->
                previousById[planned.id]
                    ?.takeIf { previous -> previous.semanticallyMatches(planned) }
                    ?: planned
            },
        )
        validateCandidate(candidate)?.let { return it }

        return commitPreparedAndRunPostEffects(expected, candidate) {
            candidate.outbounds.forEach { outbound ->
                val previous = previousById[outbound.id]
                if (previous == null || previous.json != outbound.json) {
                    runPostPersistenceCallback(ImportRuntimeInvalidateOperation) {
                        onOutboundChanged(outbound.id, outbound.json)
                    }
                }
            }
            disappeared.forEach { outbound ->
                runPostPersistenceCallback(ImportRuntimeRemoveOperation) {
                    onOutboundRemoved(outbound.id)
                }
            }
            OutboundCommandResult.ImportPersisted
        }
    }

    suspend fun saveGroup(
        expected: OutboundGroupState?,
        replacement: OutboundGroupState,
    ): OutboundCommandResult {
        currentCoroutineContext().ensureActive()
        val snapshot = gateway.snapshot()
        val current = snapshot.outboundGroups.firstOrNull { group -> group.id == replacement.id }
        if (current != expected) return OutboundCommandResult.Conflict
        val updated = snapshot.withSavedOutboundGroup(replacement)
        validateCandidate(updated)?.let { return it }
        val savedId = current?.id ?: updated.outboundGroups.last().id
        val saved = updated.outboundGroups.first { group -> group.id == savedId }
        return commitPreparedAndRunPostEffects(snapshot, updated) {
            OutboundCommandResult.GroupSaved(saved)
        }
    }

    suspend fun deleteGroup(groupId: Int): OutboundCommandResult {
        currentCoroutineContext().ensureActive()
        repeat(MaxCommitAttempts) {
            val snapshot = gateway.snapshot()
            val group = snapshot.outboundGroups.firstOrNull { item -> item.id == groupId }
                ?: return OutboundCommandResult.Conflict
            val removedOutbounds = snapshot.outbounds.filter { outbound -> outbound.groupId == groupId }
            val removedTags = removedOutbounds.mapTo(mutableSetOf(), OutboundState::tag).apply {
                add(managedOutboundGroupSelectorTag(group.id, group.name))
            }
            val updated = snapshot.copy(
                outboundGroups = snapshot.outboundGroups.filterNot { item -> item.id == groupId },
                outbounds = snapshot.outbounds.filterNot { outbound -> outbound.groupId == groupId },
            ).withRemovedManagedOutboundTags(removedTags)
            when (
                val result = commitPreparedAndRunPostEffects(snapshot, updated) {
                    removedOutbounds.forEach { outbound ->
                        runPostPersistenceCallback(DeleteGroupRuntimeRemoveOperation) {
                            onOutboundRemoved(outbound.id)
                        }
                    }
                    OutboundCommandResult.GroupDeleted
                }
            ) {
                OutboundCommandResult.Conflict -> Unit
                else -> return result
            }
        }
        return OutboundCommandResult.Conflict
    }

    suspend fun setGroupEnabled(
        groupId: Int,
        enabled: Boolean,
    ): OutboundCommandResult {
        currentCoroutineContext().ensureActive()
        repeat(MaxCommitAttempts) {
            val snapshot = gateway.snapshot()
            val group = snapshot.outboundGroups.firstOrNull { item -> item.id == groupId }
                ?: return OutboundCommandResult.Conflict
            if (group.enabled == enabled) {
                return commitPreparedAndRunPostEffects(snapshot, snapshot) {
                    OutboundCommandResult.GroupEnabledChanged
                }
            }
            val updated = snapshot.withSavedOutboundGroup(group.copy(enabled = enabled))
            when (
                val result = commitPreparedAndRunPostEffects(snapshot, updated) {
                    OutboundCommandResult.GroupEnabledChanged
                }
            ) {
                OutboundCommandResult.Conflict -> Unit
                else -> return result
            }
        }
        return OutboundCommandResult.Conflict
    }

    suspend fun reorderGroups(orderedIds: List<Int>): OutboundCommandResult {
        currentCoroutineContext().ensureActive()
        repeat(MaxCommitAttempts) {
            val snapshot = gateway.snapshot()
            val currentIds = snapshot.outboundGroups.map(OutboundGroupState::id)
            if (
                orderedIds.size != currentIds.size ||
                orderedIds.toSet().size != orderedIds.size ||
                orderedIds.toSet() != currentIds.toSet()
            ) {
                return OutboundCommandResult.Conflict
            }
            if (orderedIds == currentIds) {
                return commitPreparedAndRunPostEffects(snapshot, snapshot) {
                    OutboundCommandResult.GroupsReordered
                }
            }
            val groupsById = snapshot.outboundGroups.associateBy(OutboundGroupState::id)
            val updated = snapshot.copy(
                outboundGroups = orderedIds.map { id -> groupsById.getValue(id) },
            )
            when (
                val result = commitPreparedAndRunPostEffects(snapshot, updated) {
                    OutboundCommandResult.GroupsReordered
                }
            ) {
                OutboundCommandResult.Conflict -> Unit
                else -> return result
            }
        }
        return OutboundCommandResult.Conflict
    }

    private suspend fun validateCandidate(candidate: AppState): OutboundCommandResult.Invalid? {
        return try {
            validate(candidate)
            null
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            OutboundCommandResult.Invalid(error)
        }
    }

    private suspend fun commitPreparedAndRunPostEffects(
        expected: AppState,
        updated: AppState,
        success: () -> OutboundCommandResult,
    ): OutboundCommandResult {
        currentCoroutineContext().ensureActive()
        return withContext(NonCancellable) {
            mapPersistence(
                gateway.commitPreparedAndAwaitPersistence(expected, updated),
                success,
            )
        }
    }

    private inline fun mapPersistence(
        result: Result<Boolean>,
        success: () -> OutboundCommandResult,
    ): OutboundCommandResult {
        val error = result.exceptionOrNull()
        if (error != null) return OutboundCommandResult.PersistenceFailed(error)
        return if (result.getOrThrow()) success() else OutboundCommandResult.Conflict
    }

    private inline fun runPostPersistenceCallback(
        operation: String,
        callback: () -> Unit,
    ) {
        val callbackError = try {
            callback()
            null
        } catch (error: Throwable) {
            error
        } ?: return
        try {
            reportRuntimeCallbackFailure(operation, callbackError)
        } catch (_: Throwable) {
            // Persistence and publication already succeeded; reporting must not reverse the command result.
        }
    }

    private fun OutboundState.semanticallyMatches(other: OutboundState): Boolean {
        if (groupId != other.groupId) return false
        return runCatching {
            importFingerprint(type, remarks, json) ==
                importFingerprint(other.type, other.remarks, other.json)
        }.getOrDefault(false)
    }

    private companion object {
        const val MaxCommitAttempts = 2
        const val SaveRuntimeInvalidateOperation = "save_runtime_invalidate"
        const val DeleteRuntimeRemoveOperation = "delete_runtime_remove"
        const val DeleteGroupRuntimeRemoveOperation = "delete_group_runtime_remove"
        const val ImportRuntimeInvalidateOperation = "import_runtime_invalidate"
        const val ImportRuntimeRemoveOperation = "import_runtime_remove"
    }
}
