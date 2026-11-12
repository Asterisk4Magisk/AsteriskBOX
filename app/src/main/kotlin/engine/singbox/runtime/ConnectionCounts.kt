// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package engine.singbox.runtime

import engine.singbox.config.APP_DIRECT_OUTBOUND

internal enum class ConnectionRouteKind {
    Proxy,
    Direct,
}

internal data class ConnectionCounts(
    val activeCount: Int,
    val directCount: Int,
    val proxyCount: Int,
)

internal fun List<SingBoxConnection>.connectionCounts(): ConnectionCounts {
    var directCount = 0
    var proxyCount = 0
    forEach { connection ->
        when (connection.resolvedConnectionRoute()) {
            ConnectionRouteKind.Direct -> directCount += 1
            ConnectionRouteKind.Proxy -> proxyCount += 1
            null -> Unit
        }
    }
    return ConnectionCounts(size, directCount, proxyCount)
}

internal fun SingBoxConnection.resolvedConnectionRoute(): ConnectionRouteKind? = when {
    outboundType.equals("direct", ignoreCase = true) -> ConnectionRouteKind.Direct
    outboundType.isNotBlank() -> ConnectionRouteKind.Proxy
    outbound == APP_DIRECT_OUTBOUND -> ConnectionRouteKind.Direct
    else -> null
}
