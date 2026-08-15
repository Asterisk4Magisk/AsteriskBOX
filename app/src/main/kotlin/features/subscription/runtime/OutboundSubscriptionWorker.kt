// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.subscription.runtime

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.AppState
import app.AsteriskApplication
import features.importing.ImportStage
import features.subscription.isTransientSubscriptionFailure
import features.subscription.usecase.OutboundSubscriptionUpdateResult
import features.subscription.usecase.SubscriptionUpdateTrigger

internal enum class SubscriptionWorkerResult {
    SUCCESS,
    RETRY,
    FAILURE,
}

internal class OutboundSubscriptionWorkerRunner(
    private val stateProvider: () -> AppState,
    private val update: suspend (Int) -> OutboundSubscriptionUpdateResult,
) {
    suspend fun run(groupId: Int): SubscriptionWorkerResult {
        val group = stateProvider().outboundGroups.firstOrNull { it.id == groupId }
            ?: return SubscriptionWorkerResult.SUCCESS
        if (!group.enabled || group.url.isBlank()) return SubscriptionWorkerResult.SUCCESS
        return when (val result = update(groupId)) {
            is OutboundSubscriptionUpdateResult.Success,
            is OutboundSubscriptionUpdateResult.Partial,
            OutboundSubscriptionUpdateResult.NotModified,
            is OutboundSubscriptionUpdateResult.Cancelled,
            -> SubscriptionWorkerResult.SUCCESS

            is OutboundSubscriptionUpdateResult.Failed ->
                if (
                    result.stage == ImportStage.DOWNLOAD &&
                    isTransientSubscriptionFailure(result.error)
                ) {
                    SubscriptionWorkerResult.RETRY
                } else {
                    SubscriptionWorkerResult.FAILURE
                }
        }
    }
}

internal class OutboundSubscriptionWorker(
    appContext: Context,
    workerParameters: WorkerParameters,
) : CoroutineWorker(appContext, workerParameters) {
    override suspend fun doWork(): Result {
        val groupId = inputData.getInt(OutboundSubscriptionGroupIdKey, MissingGroupId)
        if (groupId == MissingGroupId) return Result.success()
        val application = applicationContext as? AsteriskApplication ?: return Result.failure()
        val runner = OutboundSubscriptionWorkerRunner(
            stateProvider = { application.stateStore.state.value },
            update = { requestedGroupId ->
                application.outboundSubscriptionUpdater.update(
                    groupId = requestedGroupId,
                    trigger = SubscriptionUpdateTrigger.BACKGROUND,
                )
            },
        )
        return when (runner.run(groupId)) {
            SubscriptionWorkerResult.SUCCESS -> Result.success()
            SubscriptionWorkerResult.RETRY -> Result.retry()
            SubscriptionWorkerResult.FAILURE -> Result.failure()
        }
    }

    private companion object {
        const val MissingGroupId = Int.MIN_VALUE
    }
}
