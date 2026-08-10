// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.singbox

import app.modes.SingBoxProxyLayoutAuto
import app.modes.SingBoxProxyLayoutDouble
import app.modes.SingBoxProxyLayoutMultiple
import app.modes.SingBoxProxyLayoutSingle
import app.modes.SingBoxProxySortDefault
import app.modes.SingBoxProxySortDelay
import app.modes.SingBoxProxySortName
import engine.singbox.config.APP_GLOBAL_SELECTOR
import engine.singbox.runtime.SingBoxDelayResult
import engine.singbox.runtime.SingBoxProxiesState
import engine.singbox.runtime.SingBoxProxyGroup

internal enum class SingBoxProxyDelayStatus {
    NotTested,
    Testing,
    Measured,
    Failed,
}

internal fun didSingBoxProxyDelayTestSucceed(result: SingBoxDelayResult): Boolean {
    return result.firstDelay != null
}

internal fun resolveSingBoxProxyDelayStatus(
    nodeName: String,
    delay: Int?,
    delayUpdatedAtEpochSeconds: Long?,
    testingBaselines: Map<String, Long>,
    failedNodes: Set<String>,
): SingBoxProxyDelayStatus {
    val testingBaseline = testingBaselines[nodeName]
    val hasFreshMeasuredDelay =
        delay != null &&
            delay >= 0 &&
            delayUpdatedAtEpochSeconds != null &&
            testingBaseline != null &&
            delayUpdatedAtEpochSeconds > testingBaseline
    return when {
        hasFreshMeasuredDelay -> SingBoxProxyDelayStatus.Measured
        testingBaseline != null -> SingBoxProxyDelayStatus.Testing
        nodeName in failedNodes -> SingBoxProxyDelayStatus.Failed
        delay != null && delay >= 0 -> SingBoxProxyDelayStatus.Measured
        delay != null -> SingBoxProxyDelayStatus.Failed
        else -> SingBoxProxyDelayStatus.NotTested
    }
}

internal fun prioritizeGlobalSingBoxProxyGroup(
    proxies: SingBoxProxiesState,
): SingBoxProxiesState {
    val (globalSelector, remainingGroups) = proxies.groups.partition { group ->
        group.name == APP_GLOBAL_SELECTOR
    }
    return proxies.copy(groups = globalSelector + remainingGroups)
}

internal fun reduceSingBoxProxyNodeNames(
    group: SingBoxProxyGroup?,
    proxies: SingBoxProxiesState,
    query: String,
    sort: Int,
    displayNames: Map<String, String> = emptyMap(),
): List<String> {
    val keyword = query.trim()
    return group?.all
        ?.filter { nodeName ->
            val node = proxies.node(nodeName)
            val displayType = node.type.displaySingBoxProtocolName()
            keyword.isEmpty() ||
                node.name.contains(keyword, ignoreCase = true) ||
                displayNames[nodeName]?.contains(keyword, ignoreCase = true) == true ||
                node.type.contains(keyword, ignoreCase = true) ||
                displayType.contains(keyword, ignoreCase = true)
        }
        ?.sortSingBoxProxyNodeNames(
            proxies = proxies,
            sort = resolveSingBoxProxySort(sort),
            displayNames = displayNames,
        )
        .orEmpty()
}

internal fun resolveSingBoxProxyLayout(layout: Int, isWideScreen: Boolean): Int {
    return when (layout) {
        SingBoxProxyLayoutSingle, SingBoxProxyLayoutDouble, SingBoxProxyLayoutMultiple -> layout
        SingBoxProxyLayoutAuto -> if (isWideScreen) SingBoxProxyLayoutMultiple else SingBoxProxyLayoutDouble
        else -> if (isWideScreen) SingBoxProxyLayoutMultiple else SingBoxProxyLayoutDouble
    }
}

internal fun resolveSingBoxProxyColumns(layout: Int): Int {
    return when (layout) {
        SingBoxProxyLayoutSingle -> 1
        SingBoxProxyLayoutMultiple -> 3
        else -> 2
    }
}

internal fun resolveSingBoxProxySort(sort: Int): Int {
    return when (sort) {
        SingBoxProxySortName, SingBoxProxySortDelay -> sort
        else -> SingBoxProxySortDefault
    }
}

internal fun isSingBoxProxyNodeCurrent(
    group: SingBoxProxyGroup,
    nodeName: String,
    pendingSelections: Map<String, String>,
): Boolean {
    return (pendingSelections[group.name] ?: group.now) == nodeName
}

internal data class SingBoxProxySelectionBehavior(
    val canSelect: Boolean,
    val cardEnabled: Boolean,
)

internal fun resolveSingBoxProxySelectionBehavior(
    group: SingBoxProxyGroup,
    runtimeAvailable: Boolean,
): SingBoxProxySelectionBehavior {
    return when (group.type.normalizedSingBoxGroupType()) {
        "select", "selector", "fallback" -> SingBoxProxySelectionBehavior(
            canSelect = true,
            cardEnabled = runtimeAvailable,
        )
        "urltest" -> SingBoxProxySelectionBehavior(
            canSelect = false,
            cardEnabled = true,
        )
        else -> SingBoxProxySelectionBehavior(
            canSelect = false,
            cardEnabled = false,
        )
    }
}

internal fun isSingBoxProxyGroupSelectable(group: SingBoxProxyGroup): Boolean {
    return resolveSingBoxProxySelectionBehavior(
        group = group,
        runtimeAvailable = true,
    ).canSelect
}

internal fun isSingBoxProxySelectionPersistent(group: SingBoxProxyGroup): Boolean {
    return when (group.type.normalizedSingBoxGroupType()) {
        "select", "selector" -> true
        else -> false
    }
}

private fun List<String>.sortSingBoxProxyNodeNames(
    proxies: SingBoxProxiesState,
    sort: Int,
    displayNames: Map<String, String>,
): List<String> {
    return when (sort) {
        SingBoxProxySortName -> sortedWith(
            compareBy(String.CASE_INSENSITIVE_ORDER) { nodeName ->
                displayNames[nodeName] ?: proxies.node(nodeName).name
            },
        )
        SingBoxProxySortDelay -> sortedWith(
            compareBy<String> { nodeName ->
                proxies.node(nodeName).delay.toSingBoxProxyDelaySortValue()
            }.thenBy(String.CASE_INSENSITIVE_ORDER) { nodeName ->
                displayNames[nodeName] ?: proxies.node(nodeName).name
            },
        )
        else -> this
    }
}

private fun Int?.toSingBoxProxyDelaySortValue(): Int {
    return when {
        this == null -> Int.MAX_VALUE
        this < 0 -> Int.MAX_VALUE - 1
        else -> this
    }
}

private fun String.normalizedSingBoxGroupType(): String {
    return trim().lowercase().replace("-", "").replace("_", "").replace(" ", "")
}
