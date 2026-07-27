// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package data

import app.CustomResourceFileState
import app.SingBoxDnsRuleActions
import app.SingBoxDnsRuleMatchers
import app.SingBoxDnsRuleState
import app.SingBoxDnsServerState
import app.SingBoxDnsServerTypes
import app.SingBoxRouteRuleActionReject
import app.SingBoxRouteRuleActionRoute
import app.SingBoxRouteRuleState
import app.sanitizeCustomResourceFileName
import features.logs.AndroidAppLogger
import kotlinx.serialization.Serializable
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

internal object SingBoxDnsServerListJson {
    fun encode(values: List<SingBoxDnsServerState>): String =
        appStateJson.encodeToString(values)

    fun decode(payload: String): List<SingBoxDnsServerState> {
        return runCatching {
            appStateJson.decodeFromString<List<SingBoxDnsServerState>>(payload)
                .filter { server -> server.type in SingBoxDnsServerTypes }
        }.onFailure { error ->
            AndroidAppLogger.warn(LogTag, "Failed to decode persisted DNS servers", error)
        }.getOrDefault(emptyList())
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
                .filter { rule ->
                    rule.id > 0 &&
                        rule.matches.all { match -> match.field in SingBoxDnsRuleMatchers } &&
                        rule.action in SingBoxDnsRuleActions
                }
                .distinctBy(SingBoxDnsRuleState::id)
        }.onFailure { error ->
            AndroidAppLogger.warn(LogTag, "Failed to decode persisted DNS rules", error)
        }.getOrDefault(emptyList())
    }

    private const val LogTag = "AppStateJson"
}

internal object SingBoxDnsRuleJson {
    fun encode(value: SingBoxDnsRuleState): String = appStateJson.encodeToString(value)

    fun decode(payload: String): SingBoxDnsRuleState =
        appStateJson.decodeFromString(payload)
}

internal object SingBoxRouteRuleListJson {
    fun encode(values: List<SingBoxRouteRuleState>): String =
        appStateJson.encodeToString(values)

    fun decode(payload: String): List<SingBoxRouteRuleState> {
        return runCatching {
            appStateJson.decodeFromString<List<SingBoxRouteRuleState>>(payload)
                .filter { rule ->
                    rule.id > 0 &&
                        rule.action in setOf(
                            SingBoxRouteRuleActionRoute,
                            SingBoxRouteRuleActionReject,
                        )
                }
                .distinctBy(SingBoxRouteRuleState::id)
        }.onFailure { error ->
            AndroidAppLogger.warn(LogTag, "Failed to decode persisted route rules", error)
        }.getOrDefault(emptyList())
    }

    private const val LogTag = "AppStateJson"
}

internal object SingBoxRouteRuleJson {
    fun encode(value: SingBoxRouteRuleState): String = appStateJson.encodeToString(value)

    fun decode(payload: String): SingBoxRouteRuleState =
        appStateJson.decodeFromString(payload)
}

internal object CustomResourceFileListJson {
    fun encode(values: List<CustomResourceFileState>): String {
        return appStateJson.encodeToString(values.map(PersistedCustomResourceFile::from))
    }

    fun decode(payload: String): List<CustomResourceFileState> {
        return runCatching {
            appStateJson.decodeFromString<List<PersistedCustomResourceFile>>(payload)
                .mapNotNull(PersistedCustomResourceFile::toStateOrNull)
        }.onFailure { error ->
            AndroidAppLogger.warn(LogTag, "Failed to decode persisted custom resource files", error)
        }.getOrDefault(emptyList())
    }

    private const val LogTag = "AppStateJson"
}

@Serializable
private data class PersistedCustomResourceFile(
    val id: Int,
    val name: String,
    val url: String,
) {
    fun toStateOrNull(): CustomResourceFileState? {
        val fileName = sanitizeCustomResourceFileName(name, fallback = "")
        val updateUrl = url.trim()
        if (id <= 0 || fileName.isBlank()) return null
        return CustomResourceFileState(
            id = id,
            name = fileName,
            url = updateUrl,
        )
    }

    companion object {
        fun from(state: CustomResourceFileState): PersistedCustomResourceFile {
            return PersistedCustomResourceFile(
                id = state.id,
                name = state.name,
                url = state.url,
            )
        }
    }
}
