// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.subscription.usecase

import app.SubscriptionInfo
import features.subscription.runtime.AndroidSubscriptionPreparation
import features.subscription.runtime.AndroidSubscriptionPreparer
import features.subscription.runtime.AndroidSubscriptionFetchOptions

internal enum class SubscriptionSyncStage {
    Downloading,
    Decrypting,
    Verifying,
}

internal sealed interface SubscriptionPreparation {
    data class Success(
        val content: String,
        val subscriptionInfo: SubscriptionInfo,
        val updateIntervalMillis: Long? = null,
    ) : SubscriptionPreparation

    data class Failure(
        val stage: SubscriptionSyncStage,
        val error: Throwable,
        val content: String? = null,
        val subscriptionInfo: SubscriptionInfo? = null,
        val updateIntervalMillis: Long? = null,
    ) : SubscriptionPreparation
}

internal suspend fun prepareSubscription(
    sourceUrl: String,
    userAgent: String,
    ageSecretKey: String,
    localContent: String?,
    subscriptionPreparer: AndroidSubscriptionPreparer,
    fetchOptions: AndroidSubscriptionFetchOptions,
    verifyConfiguration: Boolean = true,
    onStage: (SubscriptionSyncStage) -> Unit = {},
): SubscriptionPreparation {
    return when (
        val result = subscriptionPreparer.prepare(
            sourceContent = localContent,
            sourceUrl = sourceUrl,
            userAgent = userAgent,
            ageSecretKey = ageSecretKey,
            fetchOptions = fetchOptions,
            verifyConfiguration = verifyConfiguration,
            onStage = onStage,
        )
    ) {
        is AndroidSubscriptionPreparation.Success -> SubscriptionPreparation.Success(
            content = result.content,
            subscriptionInfo = result.subscriptionInfo,
            updateIntervalMillis = result.updateIntervalMillis,
        )

        is AndroidSubscriptionPreparation.Failure -> SubscriptionPreparation.Failure(
            stage = result.stage,
            error = result.error,
            content = result.content,
            subscriptionInfo = result.subscriptionInfo,
            updateIntervalMillis = result.updateIntervalMillis,
        )
    }
}
