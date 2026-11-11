// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.singbox

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import ui.isInDarkTheme

/**
 * Traffic-light tint for a measured proxy delay, shared by the proxy list and the home hero card.
 * Negative values come from the core as "unreachable".
 */
@Composable
internal fun singBoxDelayTint(delay: Int?): Color {
    val darkTheme = isInDarkTheme()
    val red = if (darkTheme) Color(0xFFF12522) else Color(0xFFE94634)
    return when {
        delay == null -> MaterialTheme.colorScheme.onSurfaceVariant
        delay < 0 -> red
        delay < 300 -> if (darkTheme) Color(0xFF6BD58A) else Color(0xFF128A3C)
        delay < 600 -> if (darkTheme) Color(0xFFFFC857) else Color(0xFFD18A00)
        delay < 900 -> if (darkTheme) Color(0xFFFF9B63) else Color(0xFFE06400)
        else -> red
    }
}
