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
import features.importing.ImportIssue
import features.importing.ImportIssueReason
import features.importing.ImportIssueSeverity
import features.importing.IndexedImportCandidate
import features.importing.ImportMutation
import features.importing.ImportMutationCode
import features.importing.ImportOutcome
import features.importing.ImportStage
import features.importing.deduplicateImportCandidates
import features.importing.importFingerprint
import features.importing.requireImportCandidateCount
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

internal data class ImportedSingBoxOutbound(
    val sourceIndex: Int? = null,
    val sourceTag: String = "",
    val remarks: String,
    val type: String,
    val json: String,
)

private data class RawOutboundImportCandidate(
    val sourceIndex: Int,
    val outbound: JsonObject,
)

internal object SingBoxOutboundImporter {
    fun parseConfiguration(
        content: String,
        formatter: SingBoxOutboundConfigFormatter = LibboxSingBoxOutboundConfigFormatter,
    ): List<ImportedSingBoxOutbound> {
        val root = parseSingBoxJson(content)
        val outbounds = root["outbounds"] as? JsonArray
            ?: throw IllegalArgumentException("sing-box configuration must contain an outbounds array")
        return parseRawOutboundArray(outbounds, formatter)
    }

    fun parseImport(
        content: String,
        formatter: SingBoxOutboundConfigFormatter = LibboxSingBoxOutboundConfigFormatter,
    ): List<ImportedSingBoxOutbound> {
        val outcome = parseImportOutcome(content, formatter)
        if (outcome.accepted.isEmpty()) {
            throw IllegalArgumentException(
                outcome.issues.firstOrNull()?.message ?: "No supported proxy outbounds found",
            )
        }
        return outcome.accepted
    }

    fun parseImportOutcome(
        content: String,
        formatter: SingBoxOutboundConfigFormatter = LibboxSingBoxOutboundConfigFormatter,
    ): ImportOutcome<ImportedSingBoxOutbound> {
        require(content.isNotBlank()) { "Outbound import content is empty" }
        val element = SingBoxJson.parseToJsonElement(content)
        val mutations = mutableListOf<ImportMutation>()
        val outbounds = when (element) {
            is JsonObject -> {
                if ("outbounds" in element) {
                    element.keys
                        .filterNot { key -> key == "outbounds" }
                        .forEach {
                            mutations += ImportMutation(
                                code = ImportMutationCode.IGNORED_SECTION,
                                message = "Ignored a top-level sing-box section",
                            )
                        }
                    element["outbounds"] as? JsonArray
                        ?: throw IllegalArgumentException(
                            "sing-box configuration must contain an outbounds array",
                        )
                } else {
                    JsonArray(listOf(element))
                }
            }
            is JsonArray -> element
            else -> throw IllegalArgumentException("Outbound import must be a JSON object or array")
        }
        val issues = mutableListOf<ImportIssue>()
        try {
            requireImportCandidateCount(outbounds.size)
        } catch (_: IllegalArgumentException) {
            return ImportOutcome(
                format = OutboundImportFormat.JSON,
                detectedCount = outbounds.size,
                accepted = emptyList(),
                issues = listOf(
                    ImportIssue(
                        reason = ImportIssueReason.TOO_MANY_CANDIDATES,
                        severity = ImportIssueSeverity.ERROR,
                        stage = ImportStage.PARSE,
                        message = "Import contains too many outbound candidates",
                    ),
                ),
                mutations = mutations,
            )
        }
        val accepted = outbounds.mapIndexedNotNull { index, item ->
            val outbound = item as? JsonObject
            if (outbound == null) {
                issues += rejectedJsonOutbound(
                    reason = ImportIssueReason.INVALID_ENTRY,
                    sourceIndex = index,
                    message = "Outbound entry must be a JSON object",
                )
                return@mapIndexedNotNull null
            }
            val type = outbound.stringField("type")
            if (type.isNullOrBlank()) {
                issues += rejectedJsonOutbound(
                    reason = ImportIssueReason.INVALID_FIELD,
                    sourceIndex = index,
                    message = "Outbound entry has no type",
                )
                return@mapIndexedNotNull null
            }
            if (type !in SupportedSingBoxProxyOutboundTypes) {
                issues += rejectedJsonOutbound(
                    reason = ImportIssueReason.UNSUPPORTED_TYPE,
                    sourceIndex = index,
                    detectedType = type,
                    message = "Outbound type is not supported",
                )
                return@mapIndexedNotNull null
            }
            runCatching {
                parseRawOutboundArray(JsonArray(listOf(outbound)), formatter)
                    .single()
                    .copy(sourceIndex = index)
            }.getOrElse {
                issues += rejectedJsonOutbound(
                    reason = ImportIssueReason.INVALID_ENTRY,
                    sourceIndex = index,
                    detectedType = type,
                    message = "Outbound entry could not be normalized",
                )
                null
            }
        }
        return ImportOutcome(
            format = OutboundImportFormat.JSON,
            detectedCount = outbounds.size,
            accepted = accepted,
            issues = issues,
            mutations = mutations,
        )
    }

    fun parsePreparedOutbounds(
        outbounds: List<JsonObject>,
    ): List<ImportedSingBoxOutbound> = parsePreparedOutboundArray(
        outbounds = JsonArray(outbounds),
        sourceIndexes = outbounds.indices.toList(),
    )

    private fun parseRawOutboundArray(
        outbounds: JsonArray,
        formatter: SingBoxOutboundConfigFormatter,
    ): List<ImportedSingBoxOutbound> {
        val candidates = extractSupportedCandidates(outbounds)
        val minimalRoot = buildJsonObject {
            put(
                "outbounds",
                JsonArray(candidates.map(RawOutboundImportCandidate::outbound)),
            )
        }
        val formatted = formatter.format(
            SingBoxJson.encodeToString(JsonElement.serializer(), minimalRoot),
        )
        val formattedRoot = parseSingBoxJson(formatted)
        val formattedOutbounds = formattedRoot["outbounds"] as? JsonArray
            ?: throw IllegalArgumentException(
                "Formatted sing-box configuration must contain an outbounds array",
            )
        if (formattedOutbounds.size != candidates.size) {
            throw IllegalArgumentException(
                "Formatted sing-box configuration changed the outbound count",
            )
        }
        return parsePreparedOutboundArray(
            outbounds = formattedOutbounds,
            sourceIndexes = candidates.map(RawOutboundImportCandidate::sourceIndex),
        )
    }

    private fun parsePreparedOutboundArray(
        outbounds: JsonArray,
        sourceIndexes: List<Int>,
    ): List<ImportedSingBoxOutbound> {
        require(outbounds.size == sourceIndexes.size) {
            "Prepared outbound indexes do not match outbound count"
        }
        val root = buildJsonObject { put("outbounds", outbounds) }
        SingBoxDeprecatedConfigValidator.validate(root)
        val imported = outbounds.mapIndexedNotNull { convertedIndex, element ->
            val sourceIndex = sourceIndexes[convertedIndex]
            val outbound = element as? JsonObject
                ?: throw IllegalArgumentException(
                    "Formatted outbound at index $sourceIndex must be a JSON object",
                )
            val type = outbound.stringField("type")
                ?: throw IllegalArgumentException(
                    "Formatted outbound at index $sourceIndex has no type",
                )
            if (type !in SupportedSingBoxProxyOutboundTypes) return@mapIndexedNotNull null
            val tag = outbound.stringField("tag").orEmpty()
            val remarks = tag
                .takeUnless { value -> value.startsWith(ManagedSingBoxTagPrefix) }
                ?.takeIf(String::isNotBlank)
                ?: "$type-${sourceIndex + 1}"
            ImportedSingBoxOutbound(
                sourceIndex = sourceIndex,
                sourceTag = tag,
                remarks = remarks,
                type = type,
                json = SingBoxJson.encodeToString(JsonElement.serializer(), outbound),
            )
        }
        if (imported.size != outbounds.size) {
            throw IllegalArgumentException(
                "Formatted sing-box configuration contains an unsupported outbound",
            )
        }
        return imported
    }

    private fun extractSupportedCandidates(
        outbounds: JsonArray,
    ): List<RawOutboundImportCandidate> {
        val candidates = outbounds.mapIndexedNotNull { index, element ->
            val outbound = element as? JsonObject
                ?: throw IllegalArgumentException("Outbound at index $index must be a JSON object")
            val type = outbound.stringField("type")
                ?: throw IllegalArgumentException("Outbound at index $index has no type")
            outbound
                .takeIf { type in SupportedSingBoxProxyOutboundTypes }
                ?.let { RawOutboundImportCandidate(index, it) }
        }
        if (candidates.isEmpty()) {
            throw IllegalArgumentException("No supported proxy outbounds found")
        }
        return candidates
    }
}

private fun rejectedJsonOutbound(
    reason: ImportIssueReason,
    sourceIndex: Int,
    message: String,
    detectedType: String? = null,
): ImportIssue = ImportIssue(
    reason = reason,
    severity = ImportIssueSeverity.ERROR,
    stage = ImportStage.PARSE,
    sourceIndex = sourceIndex,
    detectedType = detectedType,
    message = message,
)

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

internal data class OutboundImportPlan(
    val state: AppState,
    val outcome: ImportOutcome<ImportedSingBoxOutbound>,
    val committed: Boolean,
)

internal fun AppState.planOutboundImport(
    groupId: Int,
    parsed: ImportOutcome<ImportedSingBoxOutbound>,
    replaceGroup: Boolean,
    strict: Boolean,
): OutboundImportPlan {
    require(outboundGroups.any { group -> group.id == groupId }) {
        "Outbound group does not exist: $groupId"
    }
    val existingFingerprints = if (replaceGroup) {
        emptySet()
    } else {
        outbounds
            .asSequence()
            .filter { outbound -> outbound.groupId == groupId }
            .map { outbound ->
                importFingerprint(
                    type = outbound.type,
                    remarks = outbound.remarks,
                    json = outbound.json,
                )
            }
            .toSet()
    }
    val deduplicated = deduplicateImportCandidates(
        candidates = parsed.accepted.map { outbound ->
            IndexedImportCandidate(
                sourceIndex = outbound.sourceIndex,
                value = outbound,
            )
        },
        existingFingerprints = existingFingerprints,
    ) { outbound ->
        importFingerprint(
            type = outbound.type,
            remarks = outbound.remarks,
            json = outbound.json,
        )
    }
    val duplicateCount = parsed.duplicateCount + deduplicated.duplicateCount
    val baseMutations = parsed.mutations + deduplicated.mutations
    val preliminaryOutcome = ImportOutcome(
        format = parsed.format,
        detectedCount = parsed.detectedCount,
        accepted = deduplicated.accepted,
        duplicateCount = duplicateCount,
        issues = parsed.issues,
        mutations = baseMutations,
        priorOmittedDetailCount = parsed.omittedDetailCount,
    )

    if (replaceGroup && strict && preliminaryOutcome.skippedCount > 0) {
        return OutboundImportPlan(
            state = this,
            outcome = ImportOutcome(
                format = preliminaryOutcome.format,
                detectedCount = preliminaryOutcome.detectedCount,
                accepted = preliminaryOutcome.accepted,
                duplicateCount = preliminaryOutcome.duplicateCount,
                issues = preliminaryOutcome.issues + ImportIssue(
                    reason = ImportIssueReason.STRICT_MODE_REJECTED,
                    severity = ImportIssueSeverity.ERROR,
                    stage = ImportStage.VALIDATE,
                    message =
                        "Strict import rejected invalid entries; the previous subscription was preserved",
                ),
                mutations = preliminaryOutcome.mutations,
                priorOmittedDetailCount = preliminaryOutcome.omittedDetailCount,
            ),
            committed = false,
        )
    }
    if (preliminaryOutcome.accepted.isEmpty()) {
        val needsNoSupportedIssue =
            preliminaryOutcome.skippedCount > 0 &&
                preliminaryOutcome.issues.none { issue ->
                    issue.reason == ImportIssueReason.NO_SUPPORTED_ITEMS
                }
        return OutboundImportPlan(
            state = this,
            outcome = if (needsNoSupportedIssue) {
                ImportOutcome(
                    format = preliminaryOutcome.format,
                    detectedCount = preliminaryOutcome.detectedCount,
                    accepted = preliminaryOutcome.accepted,
                    duplicateCount = preliminaryOutcome.duplicateCount,
                    issues = preliminaryOutcome.issues + ImportIssue(
                        reason = ImportIssueReason.NO_SUPPORTED_ITEMS,
                        severity = ImportIssueSeverity.ERROR,
                        stage = ImportStage.VALIDATE,
                        message = "No supported outbound candidates were accepted",
                    ),
                    mutations = preliminaryOutcome.mutations,
                    priorOmittedDetailCount = preliminaryOutcome.omittedDetailCount,
                )
            } else {
                preliminaryOutcome
            },
            committed = false,
        )
    }

    val appliedState = withImportedOutbounds(
        groupId = groupId,
        imported = preliminaryOutcome.accepted,
        replaceGroup = replaceGroup,
    )
    val storedAdditions = appliedState.outbounds.takeLast(preliminaryOutcome.accepted.size)
    val referenceMutations = preliminaryOutcome.accepted.zip(storedAdditions)
        .flatMap { (source, stored) ->
            removedManagedReferenceMutations(source, stored)
        }
    return OutboundImportPlan(
        state = appliedState,
        outcome = ImportOutcome(
            format = preliminaryOutcome.format,
            detectedCount = preliminaryOutcome.detectedCount,
            accepted = preliminaryOutcome.accepted,
            duplicateCount = preliminaryOutcome.duplicateCount,
            issues = preliminaryOutcome.issues,
            mutations = preliminaryOutcome.mutations + referenceMutations,
            priorOmittedDetailCount = preliminaryOutcome.omittedDetailCount,
        ),
        committed = true,
    )
}

private fun removedManagedReferenceMutations(
    imported: ImportedSingBoxOutbound,
    stored: OutboundState,
): List<ImportMutation> {
    val source = SingBoxJson.parseToJsonElement(imported.json) as? JsonObject ?: return emptyList()
    val normalized = SingBoxJson.parseToJsonElement(stored.json) as? JsonObject ?: return emptyList()
    return buildList {
        if (source.hasNonBlankString("detour") && !normalized.hasNonBlankString("detour")) {
            add(
                ImportMutation(
                    code = ImportMutationCode.REMOVED_DETOUR,
                    sourceIndex = imported.sourceIndex,
                    message = "Removed an unresolved outbound detour",
                ),
            )
        }
        if (
            source.hasNonBlankString("domain_resolver") &&
            !normalized.hasNonBlankString("domain_resolver")
        ) {
            add(
                ImportMutation(
                    code = ImportMutationCode.REMOVED_DOMAIN_RESOLVER,
                    sourceIndex = imported.sourceIndex,
                    message = "Removed an unresolved domain resolver",
                ),
            )
        }
    }
}

private fun JsonObject.hasNonBlankString(name: String): Boolean =
    (get(name) as? JsonPrimitive)?.contentOrNull?.isNotBlank() == true

private fun JsonObject.stringField(name: String): String? =
    (get(name) as? JsonPrimitive)?.contentOrNull

internal val SupportedSingBoxProxyOutboundTypes = linkedSetOf(
    "socks",
    "http",
    "naive",
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
