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

internal data class EbpfUidPolicy(
    val includeUids: List<Int> = emptyList(),
    val excludeUids: List<Int> = emptyList(),
)

internal fun normalizeEbpfSharedNetworkInterfaces(values: Iterable<String>): List<String> =
    values.toTrimmedNonEmptyDistinctList()

internal fun buildEbpfUidPolicy(
    mode: Int,
    hasSelectedApps: Boolean,
    resolvedUids: List<Int>,
): EbpfUidPolicy {
    if (!hasSelectedApps) return EbpfUidPolicy()
    return when (mode.toSupportedProxyAppListMode()) {
        ProxyAppListModeWhitelist -> EbpfUidPolicy(
            includeUids = (resolvedUids + RootProxyAppWhitelistSystemUids).distinct().sorted(),
        )
        ProxyAppListModeBlacklist -> EbpfUidPolicy(excludeUids = resolvedUids.distinct().sorted())
        ProxyAppListModeGlobal -> EbpfUidPolicy()
        else -> EbpfUidPolicy()
    }
}

internal fun Context.resolveEbpfUidPolicy(appState: AppState): EbpfUidPolicy {
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
    return buildEbpfUidPolicy(mode, selectedAppKeys.isNotEmpty(), resolvedUids)
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
