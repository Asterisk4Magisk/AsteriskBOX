// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package engine.singbox.runtime

import engine.singbox.SingBoxControlConfig

internal data class SingBoxTrafficSample(
    val up: Long = 0L,
    val down: Long = 0L,
    val totalUp: Long? = null,
    val totalDown: Long? = null,
    val timestampMillis: Long = System.currentTimeMillis(),
) {
    val speed: Long
        get() = up + down
}

internal data class SingBoxTrafficState(
    val latest: SingBoxTrafficSample = SingBoxTrafficSample(),
    val totalUp: Long = 0L,
    val totalDown: Long = 0L,
    val connected: Boolean = false,
)

internal data class SingBoxMemoryState(
    val inUseBytes: Long = 0L,
    val osLimitBytes: Long = 0L,
)

internal data class SingBoxDeviceState(
    val intranetIp: String = "",
    val updatedAtMillis: Long = 0L,
)

internal data class SingBoxVersionState(
    val version: String = "",
)

internal data class SingBoxProxyNode(
    val name: String,
    val type: String,
    val udp: Boolean = false,
    val delay: Int? = null,
    val delayUpdatedAtEpochSeconds: Long? = null,
)

internal data class SingBoxProxyGroup(
    val name: String,
    val type: String,
    val displayName: String = name,
    val now: String = "",
    val all: List<String> = emptyList(),
    val hidden: Boolean = false,
    val icon: String = "",
    val testUrl: String = "",
)

internal data class SingBoxProxiesState(
    val groups: List<SingBoxProxyGroup> = emptyList(),
    val nodes: List<SingBoxProxyNode> = emptyList(),
    val nodeByName: Map<String, SingBoxProxyNode> = emptyMap(),
    val updatedAtMillis: Long = 0L,
) {
    fun node(name: String): SingBoxProxyNode {
        return nodeByName[name] ?: SingBoxProxyNode(name = name, type = "Proxy")
    }
}

internal data class SingBoxRuntimeState(
    val running: Boolean = false,
    val control: SingBoxControlConfig = SingBoxControlConfig(),
    val traffic: SingBoxTrafficState = SingBoxTrafficState(),
    val memory: SingBoxMemoryState = SingBoxMemoryState(),
    val device: SingBoxDeviceState = SingBoxDeviceState(),
    val version: SingBoxVersionState = SingBoxVersionState(),
    val proxies: SingBoxProxiesState = SingBoxProxiesState(),
    val proxiesRefreshing: Boolean = false,
    val delayTestingTarget: String? = null,
    val delayTestingNodes: Set<String> = emptySet(),
    val delayFailureBaselines: Map<String, Long> = emptyMap(),
    val lastError: String = "",
)

internal data class SingBoxDelayResult(
    val delays: Map<String, Int> = emptyMap(),
    val failedTargets: Set<String> = emptySet(),
) {
    val firstDelay: Int?
        get() = delays.values.firstOrNull()
}
