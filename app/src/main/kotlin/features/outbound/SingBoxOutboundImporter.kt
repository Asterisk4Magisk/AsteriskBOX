// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.outbound

import app.AppState
import app.ManagedSingBoxTagPrefix
import app.OutboundState
import app.managedOutboundGroupSelectorTag
import app.managedOutboundTag
import app.withRemovedManagedOutboundTags
import engine.singbox.config.APP_DIRECT_OUTBOUND
import engine.singbox.config.APP_GLOBAL_SELECTOR
import engine.singbox.config.SingBoxDeprecatedConfigValidator
import engine.singbox.config.SingBoxJson
import engine.singbox.config.parseSingBoxJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

internal data class ImportedSingBoxOutbound(
    val sourceTag: String = "",
    val remarks: String,
    val type: String,
    val json: String,
)

internal object SingBoxOutboundImporter {
    fun parseConfiguration(content: String): List<ImportedSingBoxOutbound> {
        val root = parseSingBoxJson(content)
        val outbounds = root["outbounds"] as? JsonArray
            ?: throw IllegalArgumentException("sing-box configuration must contain an outbounds array")
        return parseOutboundArray(root, outbounds)
    }

    fun parseImport(content: String): List<ImportedSingBoxOutbound> {
        if (content.isBlank()) {
            throw IllegalArgumentException("Outbound import content is empty")
        }
        return when (val element = SingBoxJson.parseToJsonElement(content)) {
            is JsonObject -> {
                val outbounds = element["outbounds"] as? JsonArray
                if (outbounds != null) {
                    parseOutboundArray(element, outbounds)
                } else {
                    parseStandaloneOutbounds(JsonArray(listOf(element)))
                }
            }
            is JsonArray -> parseStandaloneOutbounds(element)
            else -> throw IllegalArgumentException("Outbound import must be a JSON object or array")
        }
    }

    private fun parseStandaloneOutbounds(outbounds: JsonArray): List<ImportedSingBoxOutbound> {
        val root = buildJsonObject { put("outbounds", outbounds) }
        return parseOutboundArray(root, outbounds)
    }

    private fun parseOutboundArray(
        root: JsonObject,
        outbounds: JsonArray,
    ): List<ImportedSingBoxOutbound> {
        SingBoxDeprecatedConfigValidator.validate(root)
        val imported = outbounds.mapIndexedNotNull { index, element ->
            val outbound = element as? JsonObject
                ?: throw IllegalArgumentException("Outbound at index $index must be a JSON object")
            val type = outbound.stringField("type")
                ?: throw IllegalArgumentException("Outbound at index $index has no type")
            if (type !in SupportedSingBoxProxyOutboundTypes) return@mapIndexedNotNull null
            val tag = outbound.stringField("tag").orEmpty()
            val remarks = tag
                .takeUnless { value -> value.startsWith(ManagedSingBoxTagPrefix) }
                ?.takeIf(String::isNotBlank)
                ?: "$type-${index + 1}"
            ImportedSingBoxOutbound(
                sourceTag = tag,
                remarks = remarks,
                type = type,
                json = SingBoxJson.encodeToString(JsonElement.serializer(), outbound),
            )
        }
        if (imported.isEmpty()) {
            throw IllegalArgumentException("No supported proxy outbounds found")
        }
        return imported
    }
}

internal fun createManualOutbound(
    type: String,
    remarks: String,
    server: String,
    serverPort: Int,
    username: String,
    password: String,
): ImportedSingBoxOutbound {
    require(type in ManualSingBoxOutboundTypes) {
        "Manual outbound type is not supported: $type"
    }
    require(remarks.isNotBlank()) { "Outbound remarks are required" }
    require(server.isNotBlank()) { "Outbound server is required" }
    require(serverPort in 1..65535) { "Outbound server port is invalid" }
    val outbound = buildJsonObject {
        put("type", type)
        put("tag", remarks.trim())
        put("server", server.trim())
        put("server_port", serverPort)
        username.trim().takeIf(String::isNotBlank)?.let { value -> put("username", value) }
        password.takeIf(String::isNotBlank)?.let { value -> put("password", value) }
    }
    val root = buildJsonObject { put("outbounds", JsonArray(listOf(outbound))) }
    SingBoxDeprecatedConfigValidator.validate(root)
    return ImportedSingBoxOutbound(
        sourceTag = remarks.trim(),
        remarks = remarks.trim(),
        type = type,
        json = SingBoxJson.encodeToString(JsonElement.serializer(), outbound),
    )
}

internal fun OutboundState.withIdentity(
    groupId: Int,
    remarks: String,
): OutboundState {
    require(remarks.isNotBlank()) { "Outbound remarks are required" }
    val outbound = SingBoxJson.parseToJsonElement(json) as? JsonObject
        ?: throw IllegalArgumentException("Stored outbound is not a JSON object")
    val normalized = JsonObject(outbound + ("tag" to JsonPrimitive(tag)))
    return copy(
        groupId = groupId,
        remarks = remarks.trim(),
        json = SingBoxJson.encodeToString(JsonElement.serializer(), normalized),
    )
}

internal fun outboundJsonWithoutManagedIdentity(json: String): String {
    val outbound = SingBoxJson.parseToJsonElement(json) as? JsonObject
        ?: throw IllegalArgumentException("Stored outbound is not a JSON object")
    return SingBoxJson.encodeToString(
        JsonElement.serializer(),
        JsonObject(outbound - "tag"),
    )
}

internal fun AppState.withImportedOutbounds(
    groupId: Int,
    imported: List<ImportedSingBoxOutbound>,
    replaceGroup: Boolean,
): AppState {
    require(outboundGroups.any { group -> group.id == groupId }) {
        "Outbound group does not exist: $groupId"
    }
    if (imported.isEmpty()) return this

    val previousGroup = outbounds.filter { outbound -> replaceGroup && outbound.groupId == groupId }
    val reusableIds = previousGroup
        .groupBy { outbound -> outbound.remarks to outbound.type }
        .mapValues { (_, values) -> ArrayDeque(values.map(OutboundState::id)) }
    val usedIds = outbounds.mapTo(mutableSetOf()) { outbound -> outbound.id }
    var candidate = nextOutboundId.coerceAtLeast(1)
    val assigned = imported.map { item ->
        val reusable = reusableIds[item.remarks.trim() to item.type]
            ?.removeFirstOrNull()
        val id = reusable ?: run {
            while (candidate in usedIds) candidate += 1
            candidate.also { candidate += 1 }
        }
        usedIds += id
        id to item
    }
    val sourceTags = buildMap {
        assigned.forEach { (id, item) ->
            item.sourceTag
                .takeIf(String::isNotBlank)
                ?.let { sourceTag -> putIfAbsent(sourceTag, managedOutboundTag(id)) }
        }
    }
    val retainedOutbounds = outbounds.filterNot { outbound ->
        replaceGroup && outbound.groupId == groupId
    }
    val enabledGroupIds = outboundGroups
        .filter { group -> group.enabled }
        .mapTo(mutableSetOf()) { group -> group.id }
    val detourTags = buildSet {
        add(APP_DIRECT_OUTBOUND)
        add(APP_GLOBAL_SELECTOR)
        addAll(
            retainedOutbounds
                .filter { outbound -> outbound.groupId in enabledGroupIds }
                .map(OutboundState::tag),
        )
        addAll(endpoints.map { endpoint -> endpoint.tag })
        addAll(
            selectors
                .filter { selector -> selector.outbounds.isNotEmpty() }
                .map { selector -> selector.tag },
        )
        addAll(
            outboundGroups
                .filter { group ->
                    group.id in enabledGroupIds &&
                        (
                            group.id == groupId ||
                                retainedOutbounds.any { outbound -> outbound.groupId == group.id }
                            )
                }
                .map { group -> managedOutboundGroupSelectorTag(group.id) },
        )
        addAll(assigned.map { (id, _) -> managedOutboundTag(id) })
    }
    val dnsResolverTags = dnsServers.mapTo(mutableSetOf()) { server -> server.tag }
    val additions = assigned.map { (id, item) ->
        val source = SingBoxJson.parseToJsonElement(item.json) as? JsonObject
            ?: throw IllegalArgumentException("Imported outbound is not a JSON object")
        val normalized = source.toMutableMap().apply {
            put("tag", JsonPrimitive(managedOutboundTag(id)))
            val detour = (get("detour") as? JsonPrimitive)?.contentOrNull.orEmpty()
            if (detour.isNotBlank()) {
                val replacement = sourceTags[detour] ?: detour.takeIf(detourTags::contains)
                if (replacement == null) {
                    remove("detour")
                } else {
                    put("detour", JsonPrimitive(replacement))
                }
            }
            val domainResolver =
                (get("domain_resolver") as? JsonPrimitive)?.contentOrNull.orEmpty()
            if (domainResolver.isNotBlank() && domainResolver !in dnsResolverTags) {
                remove("domain_resolver")
            }
        }
        OutboundState(
            id = id,
            groupId = groupId,
            remarks = item.remarks.trim(),
            type = item.type,
            json = SingBoxJson.encodeToString(
                JsonElement.serializer(),
                JsonObject(normalized),
            ),
        )
    }
    val updated = copy(
        outbounds = (
            if (replaceGroup) outbounds.filterNot { outbound -> outbound.groupId == groupId }
            else outbounds
        ) + additions,
        nextOutboundId = candidate,
    )
    val retainedIds = additions.mapTo(mutableSetOf(), OutboundState::id)
    val removedTags = previousGroup
        .filterNot { outbound -> outbound.id in retainedIds }
        .mapTo(mutableSetOf(), OutboundState::tag)
    return updated.withRemovedManagedOutboundTags(removedTags)
}

private fun JsonObject.stringField(name: String): String? =
    (get(name) as? JsonPrimitive)?.contentOrNull

internal val SupportedSingBoxProxyOutboundTypes = linkedSetOf(
    "socks",
    "http",
    "shadowsocks",
    "vmess",
    "trojan",
    "hysteria",
    "vless",
    "shadowtls",
    "tuic",
    "hysteria2",
    "anytls",
    "snell",
    "ssh",
)

internal val ManualSingBoxOutboundTypes = SupportedSingBoxProxyOutboundTypes.toList()
