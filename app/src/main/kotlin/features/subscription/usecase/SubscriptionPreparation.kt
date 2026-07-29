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
        val etag: String = "",
        val lastModified: String = "",
    ) : SubscriptionPreparation

    data class NotModified(
        val subscriptionInfo: SubscriptionInfo,
        val etag: String,
        val lastModified: String,
    ) : SubscriptionPreparation

    data class Failure(
        val stage: SubscriptionSyncStage,
        val error: Throwable,
        val content: String? = null,
        val subscriptionInfo: SubscriptionInfo? = null,
        val etag: String = "",
        val lastModified: String = "",
    ) : SubscriptionPreparation
}

internal suspend fun prepareSubscription(
    sourceUrl: String,
    userAgent: String,
    ageSecretKey: String,
    localContent: String?,
    subscriptionPreparer: AndroidSubscriptionPreparer,
    fetchOptions: AndroidSubscriptionFetchOptions,
    etag: String = "",
    lastModified: String = "",
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
            etag = etag,
            lastModified = lastModified,
            verifyConfiguration = verifyConfiguration,
            onStage = onStage,
        )
    ) {
        is AndroidSubscriptionPreparation.Success -> SubscriptionPreparation.Success(
            content = result.content,
            subscriptionInfo = result.subscriptionInfo,
            etag = result.etag,
            lastModified = result.lastModified,
        )

        is AndroidSubscriptionPreparation.NotModified -> SubscriptionPreparation.NotModified(
            subscriptionInfo = result.subscriptionInfo,
            etag = result.etag,
            lastModified = result.lastModified,
        )

        is AndroidSubscriptionPreparation.Failure -> SubscriptionPreparation.Failure(
            stage = result.stage,
            error = result.error,
            content = result.content,
            subscriptionInfo = result.subscriptionInfo,
            etag = result.etag,
            lastModified = result.lastModified,
        )
    }
}
