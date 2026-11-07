// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package engine.singbox.config

import android.content.Context
import app.AppState
import app.withCompatibleProxyAppListMode
import app.modes.ProxyAppListModeBlacklist
import app.modes.ProxyAppListModeGlobal
import app.modes.ProxyAppListModeWhitelist
import engine.root.config.resolveRootProxyApplicationUids
import utils.toTrimmedNonEmptyDistinctList

internal const val SingBoxTunDevice = "asterisk0"

internal data class RootInboundUidPolicy(
    val includeUids: List<Int> = emptyList(),
    val excludeUids: List<Int> = emptyList(),
)

internal fun normalizeTunSharedNetworkInterfaces(values: Iterable<String>): List<String> =
    values.toTrimmedNonEmptyDistinctList()

internal fun buildRootInboundUidPolicy(
    mode: Int,
    resolvedUids: List<Int>,
): RootInboundUidPolicy {
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
    val mode = appState.withCompatibleProxyAppListMode().proxyAppListMode.toSupportedProxyAppListMode()
    val resolvedUids = if (mode == ProxyAppListModeGlobal) {
        emptyList()
    } else {
        resolveRootProxyApplicationUids(selectedAppKeys)
    }
    return buildRootInboundUidPolicy(mode, resolvedUids)
}

private val RootProxyAppWhitelistSystemUids = listOf(0, 1052)

private fun Int.toSupportedProxyAppListMode(): Int = when (this) {
    ProxyAppListModeBlacklist,
    ProxyAppListModeWhitelist,
    ProxyAppListModeGlobal,
    -> this
    else -> ProxyAppListModeGlobal
}

internal fun isSingBoxSharedNetworkInterface(value: String): Boolean =
    value != "lo" && value.matches(Regex("[A-Za-z0-9_.-]{1,15}"))
