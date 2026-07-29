// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.subscription.runtime

import android.content.Context
import androidx.core.content.edit
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import androidx.work.workDataOf
import app.OutboundGroupState
import features.subscription.SubscriptionSchedule
import features.subscription.parseSubscriptionSchedule
import java.util.concurrent.TimeUnit

internal enum class SubscriptionExistingWorkPolicy {
    UPDATE,
}

internal data class SubscriptionWorkSpec(
    val groupId: Int,
    val uniqueName: String,
    val repeatIntervalMillis: Long,
    val requiresConnectedNetwork: Boolean,
    val policy: SubscriptionExistingWorkPolicy,
    val backoffMillis: Long,
)

internal interface SubscriptionScheduleGateway {
    fun scheduledGroupIds(): Set<Int>

    fun enqueue(spec: SubscriptionWorkSpec)

    fun cancel(groupId: Int)

    fun storeScheduledGroupIds(groupIds: Set<Int>)
}

internal class OutboundSubscriptionScheduler(
    private val gateway: SubscriptionScheduleGateway,
) {
    fun reconcile(groups: List<OutboundGroupState>) {
        val desired = groups.mapNotNull { group ->
            val schedule = parseSubscriptionSchedule(group.updateInterval)
            if (
                !group.enabled ||
                group.url.isBlank() ||
                schedule !is SubscriptionSchedule.Enabled
            ) {
                null
            } else {
                SubscriptionWorkSpec(
                    groupId = group.id,
                    uniqueName = outboundSubscriptionWorkName(group.id),
                    repeatIntervalMillis = schedule.repeatIntervalMillis,
                    requiresConnectedNetwork = true,
                    policy = SubscriptionExistingWorkPolicy.UPDATE,
                    backoffMillis = MinimumSubscriptionBackoffMillis,
                )
            }
        }
        val desiredIds = desired.mapTo(mutableSetOf()) { it.groupId }
        (gateway.scheduledGroupIds() - desiredIds).forEach(gateway::cancel)
        desired.forEach(gateway::enqueue)
        gateway.storeScheduledGroupIds(desiredIds)
    }
}

internal class AndroidSubscriptionScheduleGateway(
    context: Context,
) : SubscriptionScheduleGateway {
    private val appContext = context.applicationContext
    private val workManager = WorkManager.getInstance(appContext)
    private val preferences = appContext.getSharedPreferences(
        SubscriptionSchedulePreferences,
        Context.MODE_PRIVATE,
    )

    override fun scheduledGroupIds(): Set<Int> =
        preferences.getStringSet(ScheduledGroupIdsKey, emptySet())
            .orEmpty()
            .mapNotNullTo(mutableSetOf(), String::toIntOrNull)

    override fun enqueue(spec: SubscriptionWorkSpec) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = PeriodicWorkRequest.Builder(
            OutboundSubscriptionWorker::class.java,
            spec.repeatIntervalMillis,
            TimeUnit.MILLISECONDS,
        )
            .setInputData(workDataOf(OutboundSubscriptionGroupIdKey to spec.groupId))
            .setConstraints(constraints)
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                spec.backoffMillis,
                TimeUnit.MILLISECONDS,
            )
            .addTag(OutboundSubscriptionWorkTag)
            .build()
        workManager.enqueueUniquePeriodicWork(
            spec.uniqueName,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    override fun cancel(groupId: Int) {
        workManager.cancelUniqueWork(outboundSubscriptionWorkName(groupId))
    }

    override fun storeScheduledGroupIds(groupIds: Set<Int>) {
        preferences.edit {
            putStringSet(ScheduledGroupIdsKey, groupIds.mapTo(mutableSetOf(), Int::toString))
        }
    }
}

internal fun outboundSubscriptionWorkName(groupId: Int): String =
    "outbound-subscription-update-$groupId"

internal const val OutboundSubscriptionGroupIdKey = "outbound_subscription_group_id"
private const val OutboundSubscriptionWorkTag = "outbound-subscription-update"
private const val SubscriptionSchedulePreferences = "subscription_schedule"
private const val ScheduledGroupIdsKey = "scheduled_group_ids"
private const val MinimumSubscriptionBackoffMillis = 15 * 60 * 1_000L
