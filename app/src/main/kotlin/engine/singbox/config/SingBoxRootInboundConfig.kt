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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
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

internal fun JsonObject.withRootInboundSelfBypass(uid: Int): JsonObject {
    val inbounds = this["inbounds"] as? JsonArray ?: return this
    return updated("inbounds", JsonArray(inbounds.map { value ->
        val inbound = value as? JsonObject ?: return@map value
        when ((inbound["type"] as? JsonPrimitive)?.contentOrNull) {
            "tun" -> inbound.withExcludedUid(uid)
            "ebpf" -> inbound.withEbpfLocalSelfBypass(uid)
            else -> inbound
        }
    }))
}

private fun JsonObject.withEbpfLocalSelfBypass(uid: Int): JsonObject {
    val local = this["local"] as? JsonObject ?: JsonObject(emptyMap())
    val shared = this["shared"] as? JsonObject
    val localEnabled = (local["enabled"] as? JsonPrimitive)?.booleanOrNull
    val sharedEnabled = (shared?.get("enabled") as? JsonPrimitive)?.booleanOrNull
    val interceptsLocally = localEnabled ?: (sharedEnabled == null)
    return if (interceptsLocally) updated("local", local.withExcludedUid(uid)) else this
}

private fun JsonObject.withExcludedUid(uid: Int): JsonObject {
    val excludedUids = when (val value = this["exclude_uid"]) {
        null, JsonNull -> emptyList()
        is JsonArray -> value
        else -> listOf(value)
    }
    return updated("exclude_uid", JsonArray((excludedUids + JsonPrimitive(uid)).distinct()))
}
