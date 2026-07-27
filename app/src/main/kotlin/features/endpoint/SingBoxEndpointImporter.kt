// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.endpoint

import app.AppState
import app.ManagedSingBoxTagPrefix
import app.SingBoxEndpointState
import app.SupportedSingBoxEndpointTypes
import app.managedEndpointTag
import app.managedOutboundGroupSelectorTag
import engine.singbox.config.SingBoxDeprecatedConfigValidator
import engine.singbox.config.SingBoxJson
import engine.singbox.config.APP_DIRECT_OUTBOUND
import engine.singbox.config.APP_GLOBAL_SELECTOR
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

internal data class ImportedSingBoxEndpoint(
    val sourceTag: String = "",
    val remarks: String,
    val type: String,
    val json: String,
) {
    constructor(remarks: String, type: String, json: String) : this(
        sourceTag = remarks,
        remarks = remarks,
        type = type,
        json = json,
    )

    fun withIdentity(
        type: String = this.type,
        remarks: String = this.remarks,
    ): ImportedSingBoxEndpoint {
        val parsed = SingBoxJson.parseToJsonElement(json) as? JsonObject
            ?: throw IllegalArgumentException("Endpoint must be a JSON object")
        val normalized = JsonObject(
            buildMap {
                putAll(parsed)
                put("type", JsonPrimitive(type))
            },
        )
        return copy(
            type = type,
            remarks = remarks.trim(),
            json = SingBoxJson.encodeToString(JsonElement.serializer(), normalized),
        )
    }
}

internal object SingBoxEndpointImporter {
    fun parseImport(content: String): List<ImportedSingBoxEndpoint> {
        require(content.isNotBlank()) { "Endpoint import content is empty" }
        val element = SingBoxJson.parseToJsonElement(content)
        val endpoints = when (element) {
            is JsonArray -> element
            is JsonObject -> (element["endpoints"] as? JsonArray) ?: JsonArray(listOf(element))
            else -> throw IllegalArgumentException("Endpoint import must be a JSON object or array")
        }
        val root = buildJsonObject { put("endpoints", endpoints) }
        SingBoxDeprecatedConfigValidator.validate(root)
        val imported = endpoints.mapIndexed { index, item ->
            val endpoint = item as? JsonObject
                ?: throw IllegalArgumentException("Endpoint at index $index must be a JSON object")
            val type = endpoint.stringField("type")
                ?: throw IllegalArgumentException("Endpoint at index $index has no type")
            require(type in SupportedSingBoxEndpointTypes) {
                "Endpoint type is not supported: $type"
            }
            val sourceTag = endpoint.stringField("tag")?.trim().orEmpty()
            val remarks = sourceTag
                .takeUnless { value -> value.startsWith(ManagedSingBoxTagPrefix) }
                ?.takeIf(String::isNotBlank)
                ?: "$type-${index + 1}"
            ImportedSingBoxEndpoint(
                sourceTag = sourceTag,
                remarks = remarks,
                type = type,
                json = SingBoxJson.encodeToString(JsonElement.serializer(), endpoint),
            ).withIdentity()
        }
        require(imported.isNotEmpty()) { "No supported endpoints found" }
        return imported
    }
}

internal fun AppState.withImportedEndpoints(
    imported: List<ImportedSingBoxEndpoint>,
): AppState {
    val assigned = imported.mapIndexed { index, endpoint ->
        endpoint to (nextEndpointId + index)
    }
    val importedTagMap = buildMap {
        assigned.forEach { (endpoint, endpointId) ->
            endpoint.sourceTag
                .takeIf(String::isNotBlank)
                ?.let { sourceTag -> putIfAbsent(sourceTag, managedEndpointTag(endpointId)) }
        }
    }
    val detourReferences = buildSet {
        add(APP_DIRECT_OUTBOUND)
        add(APP_GLOBAL_SELECTOR)
        addAll(outboundGroups.map { group -> managedOutboundGroupSelectorTag(group.id) })
        addAll(outbounds.map { outbound -> outbound.tag })
        addAll(endpoints.map { endpoint -> endpoint.tag })
        addAll(selectors.map { selector -> selector.tag })
        addAll(assigned.map { (_, endpointId) -> managedEndpointTag(endpointId) })
    }
    val dnsServerReferences = buildSet {
        addAll(dnsServers.map { server -> server.tag })
    }
    val added = assigned.map { (endpoint, endpointId) ->
        val source = SingBoxJson.parseToJsonElement(endpoint.json) as? JsonObject
            ?: throw IllegalArgumentException("Endpoint must be a JSON object")
        val normalized = source.toMutableMap().apply {
            put("type", JsonPrimitive(endpoint.type))
            put("tag", JsonPrimitive(managedEndpointTag(endpointId)))
            rewriteManagedReference(
                field = "detour",
                importedTagMap = importedTagMap,
                availableReferences = detourReferences,
            )
            rewriteManagedReference(
                field = "domain_resolver",
                importedTagMap = emptyMap(),
                availableReferences = dnsServerReferences,
            )
        }
        SingBoxEndpointState(
            id = endpointId,
            remarks = endpoint.remarks.trim(),
            type = endpoint.type,
            json = SingBoxJson.encodeToString(
                JsonElement.serializer(),
                JsonObject(normalized),
            ),
        )
    }
    return copy(
        endpoints = endpoints + added,
        nextEndpointId = nextEndpointId + assigned.size,
    )
}

private fun MutableMap<String, JsonElement>.rewriteManagedReference(
    field: String,
    importedTagMap: Map<String, String>,
    availableReferences: Set<String>,
) {
    val sourceReference = (get(field) as? JsonPrimitive)?.contentOrNull.orEmpty()
    val managedReference = importedTagMap[sourceReference] ?: sourceReference
    when {
        managedReference.isBlank() -> remove(field)
        managedReference in availableReferences -> put(field, JsonPrimitive(managedReference))
        else -> remove(field)
    }
}

private fun JsonObject.stringField(name: String): String? =
    (get(name) as? JsonPrimitive)?.contentOrNull
