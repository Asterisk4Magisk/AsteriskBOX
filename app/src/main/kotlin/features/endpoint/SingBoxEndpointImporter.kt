// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.endpoint

import app.AppState
import app.ManagedSingBoxTagPrefix
import app.SingBoxEndpointState
import app.SupportedSingBoxEndpointTypes
import app.managedEndpointTag
import app.managedOutboundGroupSelectorTag
import engine.singbox.config.APP_DIRECT_OUTBOUND
import engine.singbox.config.APP_GLOBAL_SELECTOR
import engine.singbox.config.SingBoxDeprecatedConfigValidator
import engine.singbox.config.SingBoxJson
import features.importing.ImportIssue
import features.importing.ImportIssueReason
import features.importing.ImportIssueSeverity
import features.importing.ImportLimitException
import features.importing.ImportMutation
import features.importing.ImportMutationCode
import features.importing.ImportOutcome
import features.importing.ImportStage
import features.importing.IndexedImportCandidate
import features.importing.deduplicateImportCandidates
import features.importing.importFingerprint
import features.importing.requireImportCandidateCount
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull

internal data class ImportedSingBoxEndpoint(
    val sourceIndex: Int? = null,
    val sourceTag: String = "",
    val remarks: String,
    val type: String,
    val json: String,
) {
    constructor(remarks: String, type: String, json: String) : this(
        sourceIndex = null,
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
        val outcome = parseImportOutcome(content)
        require(outcome.accepted.isNotEmpty()) {
            outcome.issues.firstOrNull()?.message ?: "No supported endpoints found"
        }
        return outcome.accepted
    }

    fun parseImportOutcome(content: String): ImportOutcome<ImportedSingBoxEndpoint> {
        require(content.isNotBlank()) { "Endpoint import content is empty" }
        val element = SingBoxJson.parseToJsonElement(content)
        val mutations = mutableListOf<ImportMutation>()
        val endpoints = when (element) {
            is JsonArray -> element
            is JsonObject -> if ("endpoints" in element) {
                element.keys.filterNot { it == "endpoints" }.forEach {
                    mutations += ImportMutation(
                        code = ImportMutationCode.IGNORED_SECTION,
                        message = "Ignored a top-level sing-box section",
                    )
                }
                element["endpoints"] as? JsonArray
                    ?: throw IllegalArgumentException(
                        "sing-box configuration must contain an endpoints array",
                    )
            } else {
                JsonArray(listOf(element))
            }
            else -> throw IllegalArgumentException("Endpoint import must be a JSON object or array")
        }
        try {
            requireImportCandidateCount(endpoints.size)
        } catch (error: ImportLimitException) {
            return ImportOutcome(
                format = EndpointImportFormat.JSON,
                detectedCount = endpoints.size,
                accepted = emptyList(),
                issues = listOf(
                    ImportIssue(
                        reason = error.reason,
                        severity = ImportIssueSeverity.ERROR,
                        stage = ImportStage.PARSE,
                        message = error.message ?: "Endpoint import contains too many candidates",
                    ),
                ),
                mutations = mutations,
            )
        }
        val issues = mutableListOf<ImportIssue>()
        val imported = endpoints.mapIndexedNotNull { index, item ->
            val endpoint = item as? JsonObject
            if (endpoint == null) {
                issues += endpointIssue(
                    index,
                    ImportIssueReason.INVALID_ENTRY,
                    "Endpoint entry must be a JSON object",
                )
                return@mapIndexedNotNull null
            }
            val type = endpoint.stringField("type")
            if (type.isNullOrBlank()) {
                issues += endpointIssue(
                    index,
                    ImportIssueReason.INVALID_FIELD,
                    "Endpoint entry has no type",
                )
                return@mapIndexedNotNull null
            }
            if (type !in SupportedSingBoxEndpointTypes) {
                issues += endpointIssue(
                    index,
                    ImportIssueReason.UNSUPPORTED_TYPE,
                    "Endpoint type is not supported",
                    type,
                )
                return@mapIndexedNotNull null
            }
            runCatching {
                SingBoxDeprecatedConfigValidator.validate(
                    buildJsonObject { put("endpoints", JsonArray(listOf(endpoint))) },
                )
            }.onFailure {
                issues += endpointIssue(
                    index,
                    ImportIssueReason.UNSUPPORTED_OPTION,
                    "Endpoint entry uses deprecated or unsupported options",
                    type,
                )
            }.getOrNull() ?: return@mapIndexedNotNull null
            val sourceTag = endpoint.stringField("tag")?.trim().orEmpty()
            val remarks = sourceTag
                .takeUnless { value -> value.startsWith(ManagedSingBoxTagPrefix) }
                ?.takeIf(String::isNotBlank)
                ?: "$type-${index + 1}"
            ImportedSingBoxEndpoint(
                sourceIndex = index,
                sourceTag = sourceTag,
                remarks = remarks,
                type = type,
                json = SingBoxJson.encodeToString(JsonElement.serializer(), endpoint),
            ).withIdentity()
        }
        if (endpoints.isEmpty()) {
            issues += ImportIssue(
                reason = ImportIssueReason.NO_SUPPORTED_ITEMS,
                severity = ImportIssueSeverity.ERROR,
                stage = ImportStage.PARSE,
                message = "No endpoints were found in the JSON document",
            )
        }
        return ImportOutcome(
            format = EndpointImportFormat.JSON,
            detectedCount = endpoints.size,
            accepted = imported,
            issues = issues,
            mutations = mutations,
        )
    }
}

private fun endpointIssue(
    sourceIndex: Int,
    reason: ImportIssueReason,
    message: String,
    detectedType: String? = null,
) = ImportIssue(
    reason = reason,
    severity = ImportIssueSeverity.ERROR,
    stage = ImportStage.PARSE,
    sourceIndex = sourceIndex,
    detectedType = detectedType,
    message = message,
)

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

internal data class EndpointImportPlan(
    val state: AppState,
    val outcome: ImportOutcome<ImportedSingBoxEndpoint>,
    val committed: Boolean,
)

internal fun AppState.planEndpointImport(
    parsed: ImportOutcome<ImportedSingBoxEndpoint>,
): EndpointImportPlan {
    val existing = endpoints.mapTo(mutableSetOf()) { endpoint ->
        importFingerprint(endpoint.type, endpoint.remarks, endpoint.json)
    }
    val deduplicated = deduplicateImportCandidates(
        candidates = parsed.accepted.map { endpoint ->
            IndexedImportCandidate(endpoint.sourceIndex, endpoint)
        },
        existingFingerprints = existing,
    ) { endpoint ->
        importFingerprint(endpoint.type, endpoint.remarks, endpoint.json)
    }
    val outcome = ImportOutcome(
        format = parsed.format,
        detectedCount = parsed.detectedCount,
        accepted = deduplicated.accepted,
        duplicateCount = parsed.duplicateCount + deduplicated.duplicateCount,
        issues = parsed.issues,
        mutations = parsed.mutations + deduplicated.mutations,
        priorOmittedDetailCount = parsed.omittedDetailCount,
    )
    if (outcome.accepted.isEmpty()) {
        return EndpointImportPlan(this, outcome, committed = false)
    }
    val applied = withImportedEndpoints(outcome.accepted)
    val stored = applied.endpoints.takeLast(outcome.accepted.size)
    val referenceMutations = outcome.accepted.zip(stored).flatMap { (source, target) ->
        val sourceJson = SingBoxJson.parseToJsonElement(source.json) as? JsonObject
            ?: return@flatMap emptyList()
        val targetJson = SingBoxJson.parseToJsonElement(target.json) as? JsonObject
            ?: return@flatMap emptyList()
        buildList {
            if (sourceJson.nonBlankString("detour") && !targetJson.nonBlankString("detour")) {
                add(
                    ImportMutation(
                        code = ImportMutationCode.REMOVED_DETOUR,
                        sourceIndex = source.sourceIndex,
                        message = "Removed an unresolved endpoint detour",
                    ),
                )
            }
            if (
                sourceJson.nonBlankString("domain_resolver") &&
                !targetJson.nonBlankString("domain_resolver")
            ) {
                add(
                    ImportMutation(
                        code = ImportMutationCode.REMOVED_DOMAIN_RESOLVER,
                        sourceIndex = source.sourceIndex,
                        message = "Removed an unresolved endpoint domain resolver",
                    ),
                )
            }
        }
    }
    return EndpointImportPlan(
        state = applied,
        outcome = ImportOutcome(
            format = outcome.format,
            detectedCount = outcome.detectedCount,
            accepted = outcome.accepted,
            duplicateCount = outcome.duplicateCount,
            issues = outcome.issues,
            mutations = outcome.mutations + referenceMutations,
            priorOmittedDetailCount = outcome.omittedDetailCount,
        ),
        committed = true,
    )
}

private fun JsonObject.nonBlankString(field: String): Boolean =
    (get(field) as? JsonPrimitive)?.contentOrNull?.isNotBlank() == true

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
