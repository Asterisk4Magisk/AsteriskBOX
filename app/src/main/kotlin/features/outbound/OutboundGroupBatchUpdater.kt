// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.outbound

import app.OutboundGroupState
import features.importing.ImportStage
import features.importing.ImportResultPresentation
import kotlinx.coroutines.CancellationException

internal sealed interface OutboundGroupUpdateResult {
    val isSuccess: Boolean

    data class Success(
        val outboundCount: Int,
        val presentation: ImportResultPresentation? = null,
        val notModified: Boolean = false,
    ) : OutboundGroupUpdateResult {
        override val isSuccess: Boolean = true
    }

    data class Failure(
        val stage: ImportStage,
        val error: Throwable,
        val presentation: ImportResultPresentation? = null,
    ) : OutboundGroupUpdateResult {
        override val isSuccess: Boolean = false
    }
}

internal data class OutboundGroupBatchResult(
    val updatedCount: Int,
    val failedCount: Int,
)

internal data class OutboundGroupBatchProgress(
    val groupId: Int,
    val groupName: String,
    val currentIndex: Int,
    val totalCount: Int,
    val completedCount: Int,
    val stage: ImportStage,
) {
    val fraction: Float
        get() = if (totalCount == 0) 0f else completedCount.toFloat() / totalCount
}

internal fun List<OutboundGroupState>.outboundSubscriptionGroups(): List<OutboundGroupState> =
    filter { group -> group.url.isNotBlank() }

internal suspend fun updateOutboundGroupsSequentially(
    groups: List<OutboundGroupState>,
    updateGroup: suspend (
        OutboundGroupState,
        onStage: (ImportStage) -> Unit,
    ) -> OutboundGroupUpdateResult,
    onGroupStarted: (OutboundGroupState, currentIndex: Int, totalCount: Int) -> Unit =
        { _, _, _ -> },
    onStage: (OutboundGroupState, ImportStage) -> Unit = { _, _ -> },
    onGroupCompleted: (OutboundGroupState, OutboundGroupUpdateResult) -> Unit =
        { _, _ -> },
): OutboundGroupBatchResult {
    var updatedCount = 0
    var failedCount = 0

    groups.forEachIndexed { index, group ->
        onGroupStarted(group, index + 1, groups.size)
        var stage = ImportStage.DOWNLOAD
        val result = try {
            updateGroup(group) { nextStage ->
                stage = nextStage
                onStage(group, nextStage)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            OutboundGroupUpdateResult.Failure(stage = stage, error = error)
        }
        when (result) {
            is OutboundGroupUpdateResult.Success -> updatedCount += 1
            is OutboundGroupUpdateResult.Failure -> failedCount += 1
        }
        onGroupCompleted(group, result)
    }

    return OutboundGroupBatchResult(
        updatedCount = updatedCount,
        failedCount = failedCount,
    )
}
