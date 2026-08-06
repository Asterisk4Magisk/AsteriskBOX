// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.endpoint

import app.SupportedSingBoxEndpointTypes
import app.managedEndpointTag
import engine.singbox.config.SingBoxJson
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal fun endpointEditorShowsProperties(
    editorFocused: Boolean,
    imeVisible: Boolean,
): Boolean = !editorFocused || !imeVisible

internal fun formatEndpointJson(json: String): String {
    val endpoint = SingBoxJson.parseToJsonElement(json.trim()) as? JsonObject
        ?: throw IllegalArgumentException("Endpoint must be a JSON object")
    return SingBoxJson.encodeToString(JsonElement.serializer(), endpoint)
}

internal fun newEndpointJson(type: String): String {
    require(type in SupportedSingBoxEndpointTypes)
    return SingBoxJson.encodeToString(
        JsonElement.serializer(),
        buildJsonObject {
            put("type", type)
        },
    )
}

internal fun endpointJsonForEditing(json: String): String {
    val endpoint = SingBoxJson.parseToJsonElement(json) as? JsonObject
        ?: throw IllegalArgumentException("Endpoint must be a JSON object")
    return SingBoxJson.encodeToString(
        JsonElement.serializer(),
        JsonObject(endpoint - setOf("tag", "detour", "domain_resolver")),
    )
}

internal fun endpointManagedReference(json: String, field: String): String {
    val endpoint = SingBoxJson.parseToJsonElement(json) as? JsonObject ?: return ""
    return (endpoint[field] as? JsonPrimitive)?.content.orEmpty()
}

internal fun endpointJsonWithManagedReferences(
    json: String,
    detour: String,
    domainResolver: String,
): String {
    val endpoint = SingBoxJson.parseToJsonElement(json) as? JsonObject
        ?: throw IllegalArgumentException("Endpoint must be a JSON object")
    val values = endpoint.toMutableMap().apply {
        if (detour.isBlank()) remove("detour") else put("detour", JsonPrimitive(detour))
        if (domainResolver.isBlank()) {
            remove("domain_resolver")
        } else {
            put("domain_resolver", JsonPrimitive(domainResolver))
        }
    }
    return SingBoxJson.encodeToString(JsonElement.serializer(), JsonObject(values))
}

internal fun endpointJsonForStorage(
    endpointId: Int,
    type: String,
    remarks: String,
    json: String,
    detour: String,
    domainResolver: String,
): String {
    val endpoint = SingBoxJson.parseToJsonElement(
        endpointJsonWithManagedReferences(json, detour, domainResolver),
    ) as JsonObject
    return SingBoxJson.encodeToString(
        JsonElement.serializer(),
        JsonObject(
            endpoint.toMutableMap().apply {
                put("type", JsonPrimitive(type))
                put("tag", JsonPrimitive(managedEndpointTag(endpointId, remarks)))
            },
        ),
    )
}

internal fun validateEndpointDraft(
    type: String,
    remarks: String,
    json: String,
): ImportedSingBoxEndpoint {
    require(type in SupportedSingBoxEndpointTypes) { "Endpoint type is not supported: $type" }
    val endpoint = SingBoxEndpointImporter.parseImport(json).single()
    require(endpoint.type == type) { "Endpoint type does not match the editor" }
    return endpoint.copy(remarks = remarks.trim()).withIdentity(type = type, remarks = remarks)
}
