// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.outbound

internal sealed interface OutboundShareUrlResult

internal data class AvailableOutboundShareUrl(
    val url: String,
) : OutboundShareUrlResult {
    init {
        require(url.isNotBlank()) { "Outbound share URL is required" }
    }
}

internal data object UnavailableOutboundShareUrl : OutboundShareUrlResult

internal enum class OutboundShareAction {
    QR_CODE,
    URL,
    JSON,
}

internal fun outboundShareActions(
    result: OutboundShareUrlResult,
): List<OutboundShareAction> = if (result is AvailableOutboundShareUrl) {
    listOf(
        OutboundShareAction.QR_CODE,
        OutboundShareAction.URL,
        OutboundShareAction.JSON,
    )
} else {
    listOf(OutboundShareAction.JSON)
}

internal fun outboundShareUrlPayload(
    action: OutboundShareAction,
    result: OutboundShareUrlResult,
): String? = (result as? AvailableOutboundShareUrl)
    ?.url
    ?.takeIf {
        action == OutboundShareAction.QR_CODE || action == OutboundShareAction.URL
    }
