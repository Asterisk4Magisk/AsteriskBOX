// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package data

import app.SingBoxDnsRuleActions
import app.SingBoxDnsRuleLogicalModeAnd
import app.SingBoxDnsRuleLogicalModeOr
import app.SingBoxDnsRuleMatchers
import app.SingBoxDnsRuleState
import app.SingBoxDnsRuleTypeDefault
import app.SingBoxDnsRuleTypeLogical
import app.SingBoxDnsServerState
import app.SingBoxRouteRuleState
import features.logs.AndroidAppLogger
import kotlinx.serialization.json.Json

private val appStateJson = Json {
    encodeDefaults = true
    ignoreUnknownKeys = true
}

internal object StringListJson {
    fun encode(values: List<String>): String {
        return appStateJson.encodeToString(values)
    }

    fun decode(payload: String): List<String> {
        return runCatching {
            appStateJson.decodeFromString<List<String>>(payload)
        }.onFailure { error ->
            AndroidAppLogger.warn(LogTag, "Failed to decode persisted string list", error)
        }.getOrDefault(emptyList())
    }

    private const val LogTag = "AppStateJson"
}

internal object StringMapJson {
    fun encode(values: Map<String, String>): String {
        return appStateJson.encodeToString(values)
    }

    fun decode(payload: String): Map<String, String> {
        return runCatching {
            appStateJson.decodeFromString<Map<String, String>>(payload)
        }.onFailure { error ->
            AndroidAppLogger.warn(LogTag, "Failed to decode persisted string map", error)
        }.getOrDefault(emptyMap())
    }

    private const val LogTag = "AppStateJson"
}

internal object SingBoxDnsServerJson {
    fun encode(value: SingBoxDnsServerState): String = appStateJson.encodeToString(value)

    fun decode(payload: String): SingBoxDnsServerState =
        appStateJson.decodeFromString(payload)
}

internal object SingBoxDnsRuleListJson {
    fun encode(values: List<SingBoxDnsRuleState>): String =
        appStateJson.encodeToString(values)

    fun decode(payload: String): List<SingBoxDnsRuleState> {
        return runCatching {
            appStateJson.decodeFromString<List<SingBoxDnsRuleState>>(payload)
                .filter(SingBoxDnsRuleState::isValidPersistedDnsRule)
                .distinctBy(SingBoxDnsRuleState::id)
        }.onFailure { error ->
            AndroidAppLogger.warn(LogTag, "Failed to decode persisted DNS rules", error)
        }.getOrDefault(emptyList())
    }

    private const val LogTag = "AppStateJson"
}

private fun SingBoxDnsRuleState.isValidPersistedDnsRule(): Boolean =
    id > 0 &&
        type in setOf(SingBoxDnsRuleTypeDefault, SingBoxDnsRuleTypeLogical) &&
        logicalMode in setOf(SingBoxDnsRuleLogicalModeAnd, SingBoxDnsRuleLogicalModeOr) &&
        matches.all { match -> match.field in SingBoxDnsRuleMatchers } &&
        action in SingBoxDnsRuleActions &&
        logicalRules.map(SingBoxDnsRuleState::id).distinct().size == logicalRules.size &&
        logicalRules.all(SingBoxDnsRuleState::isValidPersistedDnsRule)

internal object SingBoxDnsRuleJson {
    fun encode(value: SingBoxDnsRuleState): String = appStateJson.encodeToString(value)

    fun decode(payload: String): SingBoxDnsRuleState {
        val decoded = appStateJson.decodeFromString<SingBoxDnsRuleState>(payload)
        require(decoded.isValidPersistedDnsRule()) { "Invalid persisted DNS rule" }
        return decoded
    }
}

internal object SingBoxRouteRuleJson {
    fun encode(value: SingBoxRouteRuleState): String = appStateJson.encodeToString(value)

    fun decode(payload: String): SingBoxRouteRuleState =
        appStateJson.decodeFromString(payload)
}
