// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.subscription

import app.DefaultOutboundSubscriptionUserAgent

internal const val V2rayNgSubscriptionUserAgent = "v2rayNG/2.2.6"
internal const val ClashMetaSubscriptionUserAgent = "clash.meta"

internal enum class SubscriptionUserAgentOption {
    SingBox,
    V2rayNg,
    ClashMeta,
    Custom,
}

internal val SubscriptionUserAgentOptions = listOf(
    SubscriptionUserAgentOption.SingBox,
    SubscriptionUserAgentOption.V2rayNg,
    SubscriptionUserAgentOption.ClashMeta,
    SubscriptionUserAgentOption.Custom,
)

internal fun SubscriptionUserAgentOption.userAgentOrNull(): String? =
    when (this) {
        SubscriptionUserAgentOption.SingBox -> DefaultOutboundSubscriptionUserAgent
        SubscriptionUserAgentOption.V2rayNg -> V2rayNgSubscriptionUserAgent
        SubscriptionUserAgentOption.ClashMeta -> ClashMetaSubscriptionUserAgent
        SubscriptionUserAgentOption.Custom -> null
    }

internal fun SubscriptionUserAgentOption.resolveUserAgent(customUserAgent: String): String =
    userAgentOrNull()
        ?: customUserAgent.trim().ifBlank { DefaultOutboundSubscriptionUserAgent }

internal fun subscriptionUserAgentOptionFor(userAgent: String): SubscriptionUserAgentOption {
    val normalizedUserAgent = userAgent.trim()
    return SubscriptionUserAgentOptions.firstOrNull { option ->
        option.userAgentOrNull() == normalizedUserAgent
    } ?: SubscriptionUserAgentOption.Custom
}
