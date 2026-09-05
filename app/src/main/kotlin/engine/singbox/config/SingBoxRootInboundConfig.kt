// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package engine.singbox.config

import android.content.Context
import android.os.Process
import app.AppState
import app.modes.ProxyAppListModeBlacklist
import app.modes.ProxyAppListModeGlobal
import app.modes.ProxyAppListModeWhitelist
import system.ANDROID_USER_UID_RANGE
import system.getApplicationInfoCompat
import system.toAndroidAppId
import system.toAndroidUserId
import utils.toTrimmedNonEmptyDistinctList

internal const val SingBoxTunDevice = "asterisk0"

internal data class RootInboundUidPolicy(
    val includeUids: List<Int> = emptyList(),
    val excludeUids: List<Int> = emptyList(),
)

internal fun normalizeEbpfSharedNetworkInterfaces(values: Iterable<String>): List<String> =
    values.toTrimmedNonEmptyDistinctList()

internal fun buildRootInboundUidPolicy(
    mode: Int,
    hasSelectedApps: Boolean,
    resolvedUids: List<Int>,
): RootInboundUidPolicy {
    if (!hasSelectedApps) return RootInboundUidPolicy()
    return when (mode.toSupportedProxyAppListMode()) {
        ProxyAppListModeWhitelist -> RootInboundUidPolicy(
            includeUids = (resolvedUids + RootProxyAppWhitelistSystemUids).distinct().sorted(),
        )
        ProxyAppListModeBlacklist -> RootInboundUidPolicy(excludeUids = resolvedUids.distinct().sorted())
        ProxyAppListModeGlobal -> RootInboundUidPolicy()
        else -> RootInboundUidPolicy()
    }
}

internal fun Context.resolveRootInboundUidPolicy(appState: AppState): RootInboundUidPolicy {
    val selectedAppKeys = appState.proxyAppListSelectedApps.toTrimmedNonEmptyDistinctList()
    val mode = if (selectedAppKeys.isEmpty()) {
        ProxyAppListModeGlobal
    } else {
        appState.proxyAppListMode.toSupportedProxyAppListMode()
    }
    val resolvedUids = if (mode == ProxyAppListModeGlobal) {
        emptyList()
    } else {
        resolveApplicationUids(selectedAppKeys)
    }
    return buildRootInboundUidPolicy(mode, selectedAppKeys.isNotEmpty(), resolvedUids)
}

private val RootProxyAppWhitelistSystemUids = listOf(0, 1052)

private fun Int.toSupportedProxyAppListMode(): Int = when (this) {
    ProxyAppListModeBlacklist,
    ProxyAppListModeWhitelist,
    ProxyAppListModeGlobal,
    -> this
    else -> ProxyAppListModeGlobal
}

private fun Context.resolveApplicationUids(packageKeys: List<String>): List<Int> {
    val defaultUserId = Process.myUid().toAndroidUserId()
    val appIds = mutableMapOf<String, Int?>()
    return packageKeys.mapNotNull { key ->
        val separator = key.indexOf(':')
        val packageName = key.substring(separator + 1).trim().takeIf(String::isNotEmpty) ?: return@mapNotNull null
        val userId = if (separator < 0) defaultUserId else key.substring(0, separator).toIntOrNull()
            ?: return@mapNotNull null
        val appId = appIds.getOrPut(packageName) {
            runCatching { packageManager.getApplicationInfoCompat(packageName).uid.toAndroidAppId() }.getOrNull()
        } ?: return@mapNotNull null
        userId * ANDROID_USER_UID_RANGE + appId
    }.distinct()
}

internal fun isSingBoxSharedNetworkInterface(value: String): Boolean =
    value != "lo" && value.matches(Regex("[A-Za-z0-9_.-]{1,15}"))
