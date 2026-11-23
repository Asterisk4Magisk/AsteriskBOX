// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.settings

import app.AppState

// Global connection-test preferences used by the delay test, and as the
// url_test fallback when a group has no URL of its own. Mirrors the husi
// `connectionTest*` settings.
const val DefaultConnectionTestUrl = "http://cp.cloudflare.com/"
const val DefaultConnectionTestConcurrent = 5
const val DefaultConnectionTestTimeoutMillis = 5000
const val DefaultConnectionTestUnifiedDelay = true
const val DefaultConnectionTestIgnoreHandshakeTime = false

data class ConnectionTestIntBounds(val min: Int, val max: Int)
val ConnectionTestConcurrentBounds = ConnectionTestIntBounds(min = 1, max = 32)
val ConnectionTestTimeoutBounds = ConnectionTestIntBounds(min = 1000, max = 8000)
const val ConnectionTestTimeoutStepMillis = 500

fun isValidConnectionTestUrl(url: String): Boolean {
    val uri = runCatching { java.net.URI(url.trim()) }.getOrNull() ?: return false
    return uri.scheme?.lowercase() in setOf("http", "https") && !uri.host.isNullOrBlank()
}

fun AppState.withNormalizedConnectionTestSettings(): AppState = copy(
    connectionTestUrl = connectionTestUrl.trim()
        .takeIf(::isValidConnectionTestUrl)
        ?: DefaultConnectionTestUrl,
    connectionTestConcurrent = connectionTestConcurrent.coerceIn(
        ConnectionTestConcurrentBounds.min,
        ConnectionTestConcurrentBounds.max,
    ),
    connectionTestTimeoutMillis = connectionTestTimeoutMillis.coerceIn(
        ConnectionTestTimeoutBounds.min,
        ConnectionTestTimeoutBounds.max,
    ),
)
