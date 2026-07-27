// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.monitoring

import engine.singbox.runtime.SingBoxConnectionsState
import features.monitoring.resource.ProcessStatsSample
import features.monitoring.resource.ProcessStatsSourceKind
import features.monitoring.network.LocalNetworkSnapshot
import features.monitoring.network.PublicNetworkProbeState
import features.monitoring.traffic.TrafficBytes

internal data class MonitoringResourceSummary(
    val cpuPercent: Double? = null,
    val memoryBytes: Long? = null,
    val source: ProcessStatsSourceKind? = null,
    val uptimeMillis: Long? = null,
    val processId: Int? = null,
    val memoryLimitBytes: Long? = null,
    val sampleIntervalMillis: Long? = null,
    val fifteenMinuteSamples: List<ProcessStatsSample> = emptyList(),
    val oneHourSamples: List<ProcessStatsSample> = emptyList(),
)

internal data class MonitoringResourceFocusState(
    val serviceRunning: Boolean,
    val cpuPercent: Double?,
    val memoryBytes: Long?,
)

internal fun buildMonitoringResourceFocusState(
    serviceRunning: Boolean,
    summary: MonitoringResourceSummary,
): MonitoringResourceFocusState = MonitoringResourceFocusState(
    serviceRunning = serviceRunning,
    cpuPercent = summary.cpuPercent,
    memoryBytes = summary.memoryBytes,
)

internal enum class ConnectionMonitorStatus {
    ServiceStopped,
    Loading,
    Available,
    Error,
}

internal data class MonitoringConnectionsSummary(
    val activeCount: Int? = null,
    val uploadBytesPerSecond: Long? = null,
    val downloadBytesPerSecond: Long? = null,
    val sessionUploadBytes: Long? = null,
    val sessionDownloadBytes: Long? = null,
    val snapshot: SingBoxConnectionsState = SingBoxConnectionsState(),
    val status: ConnectionMonitorStatus = ConnectionMonitorStatus.ServiceStopped,
    val error: String = "",
    val stale: Boolean = false,
)

internal data class MonitoringConnectionsFocusState(
    val status: ConnectionMonitorStatus,
    val activeCount: Int?,
    val proxyCount: Int,
    val directCount: Int,
    val error: String = "",
)

internal fun buildMonitoringConnectionsFocusState(
    summary: MonitoringConnectionsSummary,
): MonitoringConnectionsFocusState {
    var proxyCount = 0
    var directCount = 0
    summary.snapshot.connections.forEach { connection ->
        when (connection.resolvedConnectionRoute()) {
            ConnectionRouteKind.Proxy -> proxyCount += 1
            ConnectionRouteKind.Direct -> directCount += 1
            null -> Unit
        }
    }
    return MonitoringConnectionsFocusState(
        status = summary.status,
        activeCount = summary.activeCount,
        proxyCount = proxyCount,
        directCount = directCount,
        error = summary.error,
    )
}

internal data class MonitoringTrafficSpeedSample(
    val timestampMillis: Long,
    val uploadBytesPerSecond: Long,
    val downloadBytesPerSecond: Long,
)

internal data class MonitoringTrafficSummary(
    val uploadBytesPerSecond: Long? = null,
    val downloadBytesPerSecond: Long? = null,
    val sessionUploadBytes: Long? = null,
    val sessionDownloadBytes: Long? = null,
    val today: TrafficBytes = TrafficBytes(),
    val sevenDays: TrafficBytes = TrafficBytes(),
    val thirtyDays: TrafficBytes = TrafficBytes(),
    val dailyTotals: Map<String, TrafficBytes> = emptyMap(),
    val speedSamples: List<MonitoringTrafficSpeedSample> = emptyList(),
)

internal data class MonitoringNetworkSummary(
    val proxyConnected: Boolean = false,
    val local: LocalNetworkSnapshot = LocalNetworkSnapshot(),
    val publicProbe: PublicNetworkProbeState = PublicNetworkProbeState(),
)

internal data class MonitoringState(
    val serviceRunning: Boolean = false,
    val resource: MonitoringResourceSummary = MonitoringResourceSummary(),
    val connections: MonitoringConnectionsSummary = MonitoringConnectionsSummary(),
    val traffic: MonitoringTrafficSummary = MonitoringTrafficSummary(),
    val network: MonitoringNetworkSummary = MonitoringNetworkSummary(),
)
