// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.resources

import engine.singbox.config.SingBoxJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

internal enum class JsonRuleSetValidationError {
    Empty,
    InvalidJson,
    InvalidRoot,
    InvalidVersion,
    InvalidRules,
}

internal class InvalidJsonRuleSetStructureException(
    val reason: JsonRuleSetValidationError,
) : IllegalArgumentException(reason.name)

internal class InvalidSingBoxJsonRuleSetException(
    cause: Throwable,
) : IllegalArgumentException(cause.message, cause)

internal sealed interface ResourceJsonFileOrigin {
    data object Missing : ResourceJsonFileOrigin

    data class Existing(
        val content: String,
    ) : ResourceJsonFileOrigin
}

internal data class ResourceJsonEditorSnapshot(
    val content: String,
    val origin: ResourceJsonFileOrigin,
) {
    val isDraft: Boolean
        get() = origin == ResourceJsonFileOrigin.Missing
}

internal val DefaultJsonRuleSetDraft = """
    {
      "version": 5,
      "rules": []
    }
""".trimIndent() + "\n"

internal fun resourceJsonEditorSnapshot(existingContent: String?): ResourceJsonEditorSnapshot {
    val origin = existingContent
        ?.let(ResourceJsonFileOrigin::Existing)
        ?: ResourceJsonFileOrigin.Missing
    return ResourceJsonEditorSnapshot(
        content = (origin as? ResourceJsonFileOrigin.Existing)?.content
            ?: DefaultJsonRuleSetDraft,
        origin = origin,
    )
}

internal fun validateJsonRuleSetStructure(content: String): JsonRuleSetValidationError? {
    if (content.isBlank()) return JsonRuleSetValidationError.Empty

    val root = runCatching { SingBoxJson.parseToJsonElement(content) }
        .getOrElse { return JsonRuleSetValidationError.InvalidJson }
        as? JsonObject
        ?: return JsonRuleSetValidationError.InvalidRoot

    val version = (root["version"] as? JsonPrimitive)
        ?.takeUnless(JsonPrimitive::isString)
        ?.intOrNull
    if (version == null || version !in SupportedRuleSetVersions) {
        return JsonRuleSetValidationError.InvalidVersion
    }

    val rules = root["rules"] as? JsonArray
        ?: return JsonRuleSetValidationError.InvalidRules
    if (rules.any { it !is JsonObject }) {
        return JsonRuleSetValidationError.InvalidRules
    }

    return null
}

internal fun requireValidJsonRuleSetStructure(content: String) {
    val reason = validateJsonRuleSetStructure(content) ?: return
    throw InvalidJsonRuleSetStructureException(reason)
}

internal fun formatJsonRuleSet(content: String): String {
    val element = SingBoxJson.parseToJsonElement(content)
    return SingBoxJson.encodeToString(JsonElement.serializer(), element)
        .trimEnd('\r', '\n') + "\n"
}

private val SupportedRuleSetVersions = 1..5
