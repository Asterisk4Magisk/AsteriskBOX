// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package engine.stats

internal data class TrafficStatsNotificationContent(
    val title: String,
    val summary: String,
    val expandedText: String,
)

internal fun trafficStatsNotificationContent(
    appName: String,
    speedLine: String,
    trafficLine: String,
): TrafficStatsNotificationContent {
    return TrafficStatsNotificationContent(
        title = appName,
        summary = speedLine,
        expandedText = "$speedLine\n$trafficLine",
    )
}
