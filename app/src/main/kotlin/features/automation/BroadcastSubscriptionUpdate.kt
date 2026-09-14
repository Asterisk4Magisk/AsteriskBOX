// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.automation

import app.AsteriskApplication
import features.logs.AndroidAppLogger
import features.subscription.usecase.SubscriptionUpdateTrigger
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal suspend fun AsteriskApplication.updateBroadcastSubscriptions(progress: BroadcastUpdateProgress): Boolean {
    val ids = stateStore.state.value.outboundGroups.filter { it.url.isNotBlank() }.map { it.id }
    var failures = 0
    for (id in ids) {
        currentCoroutineContext().ensureActive()
        if (progress.completed(id.toString())) continue
        val group = stateStore.state.value.outboundGroups.firstOrNull { it.id == id } ?: continue
        if (group.url.isBlank()) continue
        val result = outboundSubscriptionUpdater.update(id, SubscriptionUpdateTrigger.BATCH)
        currentCoroutineContext().ensureActive()
        progress.record(id.toString(), result.isSuccessfulCheck)
        if (!result.isSuccessfulCheck) failures++
        AndroidAppLogger.info("BroadcastControl", "Subscription item=$id success=${result.isSuccessfulCheck}")
    }
    return failures == 0 && progress.succeeded()
}
