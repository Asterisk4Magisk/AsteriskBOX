// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package engine.ebpf

import android.content.Context
import app.AppState
import app.modes.ProxyAppListModeBlacklist
import app.modes.ProxyAppListModeGlobal
import app.modes.ProxyAppListModeWhitelist
import engine.root.RootProxyAppWhitelistSystemUids
import engine.root.resolveRootProxyApplicationUids
import engine.root.toRootProxyAppListMode
import utils.toTrimmedNonEmptyDistinctList

internal const val EbpfRedirectIpv4Prefix = "127.128.0.0/9"
internal const val EbpfRedirectIpv6Prefix = "fd53:696e:672d:626f::/64"

internal data class EbpfUidPolicy(
    val includeUids: List<Int> = emptyList(),
    val excludeUids: List<Int> = emptyList(),
)

internal fun normalizeEbpfSharedNetworkInterfaces(values: Iterable<String>): List<String> {
    return values.toTrimmedNonEmptyDistinctList()
}

internal fun buildEbpfUidPolicy(
    mode: Int,
    hasSelectedApps: Boolean,
    resolvedUids: List<Int>,
): EbpfUidPolicy {
    if (!hasSelectedApps) return EbpfUidPolicy()
    return when (mode.toRootProxyAppListMode()) {
        ProxyAppListModeWhitelist -> EbpfUidPolicy(
            includeUids = (resolvedUids + RootProxyAppWhitelistSystemUids).distinct().sorted(),
        )
        ProxyAppListModeBlacklist -> EbpfUidPolicy(
            excludeUids = resolvedUids.distinct().sorted(),
        )
        ProxyAppListModeGlobal -> EbpfUidPolicy()
        else -> EbpfUidPolicy()
    }
}

internal fun Context.resolveEbpfUidPolicy(appState: AppState): EbpfUidPolicy {
    val selectedAppKeys = appState.proxyAppListSelectedApps.toTrimmedNonEmptyDistinctList()
    val mode = if (selectedAppKeys.isEmpty()) {
        ProxyAppListModeGlobal
    } else {
        appState.proxyAppListMode.toRootProxyAppListMode()
    }
    val resolvedUids = if (mode == ProxyAppListModeGlobal) {
        emptyList()
    } else {
        resolveRootProxyApplicationUids(selectedAppKeys)
    }
    return buildEbpfUidPolicy(
        mode = mode,
        hasSelectedApps = selectedAppKeys.isNotEmpty(),
        resolvedUids = resolvedUids,
    )
}
