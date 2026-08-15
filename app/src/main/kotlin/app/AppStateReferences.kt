// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package app

import app.modes.RunModeBpf2Socks
import app.modes.RunModeEbpf
import app.modes.RunModeTproxy
import app.modes.RunModeTun
import app.modes.RunModeTun2Socks
import app.modes.RunModeVpnService
import engine.network.isIpAddress
import engine.singbox.config.SingBoxJson
import engine.singbox.config.APP_DIRECT_OUTBOUND
import engine.singbox.config.APP_GLOBAL_SELECTOR
import engine.singbox.config.APP_LOCAL_INBOUND
import engine.singbox.config.APP_ROOT_INBOUND
import engine.singbox.config.APP_TUN_INBOUND
import features.resources.hasSingBoxRuleSetExtension
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

internal enum class ManagedOutboundChoiceKind {
    Selector,
    UrlTest,
    Outbound,
    Endpoint,
    Direct,
    GlobalSelector,
}

internal data class ManagedOutboundChoice(
    val tag: String,
    val label: String,
    val kind: ManagedOutboundChoiceKind,
    val groupName: String? = null,
)

internal data class ManagedReferenceChoice(
    val tag: String,
    val remarks: String,
)

internal fun AppState.managedReferenceRemarks(): Map<String, String> = buildMap {
    outboundGroups.forEach { group ->
        putVisibleRemarks(managedOutboundGroupSelectorTag(group.id, group.name), group.name)
    }
    outbounds.forEach { outbound ->
        putVisibleRemarks(outbound.tag, outbound.remarks)
    }
    selectors.forEach { selector ->
        putVisibleRemarks(selector.tag, selector.remarks)
    }
    endpoints.forEach { endpoint ->
        putVisibleRemarks(endpoint.tag, endpoint.remarks)
    }
    dnsServers.forEach { server ->
        putVisibleRemarks(server.tag, server.remarks)
    }
    dnsRules
        .filter { rule -> rule.action == SingBoxDnsEvaluateAction }
        .forEach { rule ->
            putVisibleRemarks(rule.evaluationTag, rule.remarks)
        }
    customResourceFiles.forEach { file ->
        putVisibleRemarks(managedCustomRuleSetTag(file.id, file.name), file.name)
    }
    ResourceFileKind.entries
        .filter { kind -> kind.fileName.endsWith(SingBoxRuleSetExtension, ignoreCase = true) }
        .forEach { kind ->
            putVisibleRemarks(managedBundledRuleSetTag(kind), kind.fileName)
        }
}

internal fun visibleManagedReference(
    value: String,
    labels: Map<String, String>,
    unavailableLabel: String,
): String {
    val normalized = value.trim()
    return labels[normalized]
        ?.trim()
        ?.takeIf(String::isNotEmpty)
        ?: normalized.takeUnless { reference ->
            isManagedSingBoxTag(reference)
        }
        ?: unavailableLabel
}

internal fun selectableManagedOutbounds(
    state: AppState,
    excludedTag: String = "",
    excludedSelectorId: Int = 0,
    excludedManagedGroupId: Int = 0,
    includeEndpoints: Boolean = true,
    includeDirect: Boolean = true,
    includeGlobalSelector: Boolean = true,
): List<ManagedOutboundChoice> {
    val enabledGroups = state.outboundGroups.filter(OutboundGroupState::enabled)
    val enabledGroupIds = enabledGroups.mapTo(mutableSetOf(), OutboundGroupState::id)
    val originalExcludedTag = state.selectors
        .firstOrNull { selector -> selector.id == excludedSelectorId }
        ?.tag
        .orEmpty()
    val excludedTargets = setOf(excludedTag.trim(), originalExcludedTag.trim())
        .filterTo(mutableSetOf(), String::isNotEmpty)
    val excludedManagedGroupTag = state.outboundGroups
        .firstOrNull { group -> group.id == excludedManagedGroupId }
        ?.let { group -> managedOutboundGroupSelectorTag(group.id, group.name) }
        .orEmpty()
    return buildList {
        enabledGroups.forEach { group ->
            if (state.outbounds.any { outbound -> outbound.groupId == group.id }) {
                val tag = managedOutboundGroupSelectorTag(group.id, group.name)
                add(
                    ManagedOutboundChoice(
                        tag = tag,
                        label = group.name.trim(),
                        kind = ManagedOutboundChoiceKind.Selector,
                    ),
                )
            }
        }
        state.selectors
            .filter { selector -> selector.outbounds.isNotEmpty() }
            .forEach { selector ->
                add(
                    ManagedOutboundChoice(
                        tag = selector.tag,
                        label = selector.remarks,
                        kind = if (selector.type == SingBoxSelectorTypeUrlTest) {
                            ManagedOutboundChoiceKind.UrlTest
                        } else {
                            ManagedOutboundChoiceKind.Selector
                        },
                    ),
                )
            }
        state.outbounds
            .filter { outbound -> outbound.groupId in enabledGroupIds }
            .forEach { outbound ->
                add(
                    ManagedOutboundChoice(
                        tag = outbound.tag,
                        label = outbound.remarks,
                        kind = when (outbound.type) {
                            SingBoxSelectorTypeSelector -> ManagedOutboundChoiceKind.Selector
                            SingBoxSelectorTypeUrlTest -> ManagedOutboundChoiceKind.UrlTest
                            else -> ManagedOutboundChoiceKind.Outbound
                        },
                        groupName = enabledGroups
                            .firstOrNull { group -> group.id == outbound.groupId }
                            ?.name
                            ?.trim()
                            ?.takeIf(String::isNotEmpty),
                    ),
                )
            }
        if (includeEndpoints) {
            state.endpoints.forEach { endpoint ->
                add(
                    ManagedOutboundChoice(
                        tag = endpoint.tag,
                        label = endpoint.remarks,
                        kind = ManagedOutboundChoiceKind.Endpoint,
                    ),
                )
            }
        }
        if (includeDirect) {
            add(
                ManagedOutboundChoice(
                    tag = APP_DIRECT_OUTBOUND,
                    label = APP_DIRECT_OUTBOUND,
                    kind = ManagedOutboundChoiceKind.Direct,
                ),
            )
        }
        if (includeGlobalSelector) {
            add(
                ManagedOutboundChoice(
                    tag = APP_GLOBAL_SELECTOR,
                    label = APP_GLOBAL_SELECTOR,
                    kind = ManagedOutboundChoiceKind.GlobalSelector,
                ),
            )
        }
    }
        .map { choice -> choice.copy(tag = choice.tag.trim(), label = choice.label.trim()) }
        .filter { choice -> choice.tag.isNotEmpty() }
        .distinctBy(ManagedOutboundChoice::tag)
        .filterNot { choice ->
            (
                excludedManagedGroupId != 0 &&
                    choice.tag == excludedManagedGroupTag
                ) ||
                choice.tag in excludedTargets ||
                excludedTargets.any { target ->
                    state.outboundDependsOn(choice.tag, target, mutableSetOf())
                }
        }
        .sortedBy { choice -> choice.kind.priority }
}

internal fun selectableDetourOutbounds(
    state: AppState,
    excludedTag: String,
    excludedManagedGroupId: Int = 0,
    includeGlobalSelector: Boolean = true,
): List<ManagedOutboundChoice> {
    return selectableManagedOutbounds(
        state = state,
        excludedTag = excludedTag,
        excludedManagedGroupId = excludedManagedGroupId,
        includeEndpoints = true,
        includeDirect = true,
        includeGlobalSelector = includeGlobalSelector,
    ).filterNot { choice ->
        !includeGlobalSelector &&
            state.outboundDependsOn(
                tag = choice.tag,
                targetTag = APP_GLOBAL_SELECTOR,
                visited = mutableSetOf(),
            )
    }
}

internal fun AppState.withCanonicalManagedTagReferences(): AppState {
    val tagsByIdentity = currentManagedTagsByIdentity()
    val resolve: (String) -> String = { value ->
        managedTagIdentityOrNull(value)
            ?.let(tagsByIdentity::get)
            ?: value
    }
    val canonical = copy(
        outbounds = outbounds.map { outbound ->
            outbound.withCanonicalManagedReferences(resolve)
        },
        endpoints = endpoints.map { endpoint ->
            endpoint.withCanonicalManagedReferences(resolve)
        },
        selectors = selectors.map { selector ->
            selector.copy(
                outbounds = selector.outbounds.map(resolve),
                default = resolve(selector.default),
            )
        },
        selectorSelections = buildMap {
            this@withCanonicalManagedTagReferences.selectorSelections.forEach { (selector, target) ->
                put(resolve(selector), resolve(target))
            }
        },
        routeFinal = resolve(routeFinal),
        ebpfBypassRuleSetTags = ebpfBypassRuleSetTags.map(resolve),
        routeRules = routeRules.map { rule -> rule.withCanonicalManagedReferences(resolve) },
        dnsFinal = resolve(dnsFinal),
        routeDefaultDomainResolver = resolve(routeDefaultDomainResolver),
        dnsServers = dnsServers.map { server ->
            server.copy(
                endpoint = resolve(server.endpoint),
                detour = resolve(server.detour),
                domainResolver = resolve(server.domainResolver),
                servers = server.servers.map(resolve),
            )
        },
        dnsRules = dnsRules.map { rule -> rule.withCanonicalManagedReferences(resolve) },
    )
    return if (canonical == this) this else canonical
}

private fun AppState.currentManagedTagsByIdentity(): Map<ManagedTagIdentity, String> = buildMap {
    fun add(tag: String) {
        managedTagIdentityOrNull(tag)?.let { identity -> put(identity, tag) }
    }
    add(APP_DIRECT_OUTBOUND)
    add(APP_GLOBAL_SELECTOR)
    add(APP_LOCAL_INBOUND)
    add(APP_TUN_INBOUND)
    add(APP_ROOT_INBOUND)
    add(ManagedApiServiceTag)
    outboundGroups.forEach { group ->
        add(managedOutboundGroupSelectorTag(group.id, group.name))
    }
    outbounds.forEach { outbound -> add(outbound.tag) }
    endpoints.forEach { endpoint -> add(endpoint.tag) }
    selectors.forEach { selector -> add(selector.tag) }
    dnsServers.forEach { server -> add(server.tag) }
    dnsRules
        .filter { rule -> rule.action == SingBoxDnsEvaluateAction }
        .forEach { rule -> add(rule.evaluationTag) }
    customResourceFiles.forEach { file ->
        add(managedCustomRuleSetTag(file.id, file.name))
    }
    ResourceFileKind.entries
        .filter { kind -> kind.fileName.endsWith(SingBoxRuleSetExtension, ignoreCase = true) }
        .forEach { kind -> add(managedBundledRuleSetTag(kind)) }
}

private fun SingBoxRouteRuleState.withCanonicalManagedReferences(
    resolve: (String) -> String,
): SingBoxRouteRuleState = copy(
    logicalRules = logicalRules.map { rule -> rule.withCanonicalManagedReferences(resolve) },
    inbound = inbound.map(resolve),
    ruleSet = ruleSet.map(resolve),
    outbound = resolve(outbound),
)

private fun SingBoxDnsRuleState.withCanonicalManagedReferences(
    resolve: (String) -> String,
): SingBoxDnsRuleState = copy(
    logicalRules = logicalRules.map { rule -> rule.withCanonicalManagedReferences(resolve) },
    server = resolve(server),
    matches = matches.map { match ->
        if (match.field in CanonicalDnsReferenceFields) {
            match.copy(values = match.values.map(resolve))
        } else {
            match
        }
    },
)

private fun OutboundState.withCanonicalManagedReferences(
    resolve: (String) -> String,
): OutboundState {
    val root = jsonObject() ?: return this
    return copy(
        json = root.withCanonicalManagedReferences(
            tag = tag,
            resolve = resolve,
            includeSelectorFields = type in CanonicalJsonSelectorTypes,
        ).encoded(),
    )
}

private fun SingBoxEndpointState.withCanonicalManagedReferences(
    resolve: (String) -> String,
): SingBoxEndpointState {
    val root = jsonObject() ?: return this
    return copy(
        json = root.withCanonicalManagedReferences(
            tag = tag,
            resolve = resolve,
            includeSelectorFields = false,
        ).encoded(),
    )
}

private fun JsonObject.withCanonicalManagedReferences(
    tag: String,
    resolve: (String) -> String,
    includeSelectorFields: Boolean,
): JsonObject = JsonObject(
    toMutableMap().apply {
        put("tag", JsonPrimitive(tag))
        CanonicalJsonReferenceFields.forEach { field ->
            val value = (get(field) as? JsonPrimitive)?.contentOrNull ?: return@forEach
            put(field, JsonPrimitive(resolve(value)))
        }
        if (includeSelectorFields) {
            val default = (get("default") as? JsonPrimitive)
                ?.takeIf(JsonPrimitive::isString)
                ?.content
            if (default != null) put("default", JsonPrimitive(resolve(default)))
            val outbounds = get("outbounds") as? JsonArray
            if (outbounds != null) {
                put(
                    "outbounds",
                    JsonArray(
                        outbounds.map { element ->
                            val value = (element as? JsonPrimitive)
                                ?.takeIf(JsonPrimitive::isString)
                                ?.content
                            if (value == null) element else JsonPrimitive(resolve(value))
                        },
                    ),
                )
            }
        }
    },
)

internal fun selectableDnsEndpoints(
    state: AppState,
    dnsServerType: String,
): List<ManagedReferenceChoice> {
    val endpointType = when (dnsServerType) {
        "tailscale" -> "tailscale"
        "openconnect" -> "openconnect"
        "openvpn" -> "openvpn-client"
        else -> return emptyList()
    }
    return state.endpoints
        .filter { endpoint -> endpoint.type == endpointType }
        .map { endpoint ->
            ManagedReferenceChoice(
                tag = endpoint.tag,
                remarks = endpoint.remarks,
            )
        }
        .distinctBy(ManagedReferenceChoice::tag)
}

internal fun managedInboundTags(state: AppState): List<String> = buildList {
    add(APP_LOCAL_INBOUND)
    when (state.runMode) {
        RunModeVpnService -> if (!state.enableVpnHevTun) add(APP_TUN_INBOUND)
        RunModeTun -> add(APP_TUN_INBOUND)
        RunModeTproxy, RunModeTun2Socks, RunModeBpf2Socks, RunModeEbpf -> add(APP_ROOT_INBOUND)
    }
}

internal fun selectablePreferredByDnsServerTags(state: AppState): List<String> =
    selectablePreferredByDnsServers(state).map(ManagedReferenceChoice::tag)

internal fun selectablePreferredByDnsServers(state: AppState): List<ManagedReferenceChoice> =
    state.dnsServers
        .filter { server -> server.type in PreferredByDnsServerTypes }
        .map { server ->
            ManagedReferenceChoice(
                tag = server.tag,
                remarks = server.remarks,
            )
        }
        .distinctBy(ManagedReferenceChoice::tag)

internal fun AppState.withPrunedManagedInboundReferences(): AppState {
    val availableTags = managedInboundTags(this).toSet()
    return copy(
        routeRules = routeRules.map { rule ->
            rule.disableUnavailableInboundReferences(availableTags)
        },
        dnsRules = dnsRules.map { rule ->
            rule.disableUnavailableDnsMatchReferences(SingBoxInboundField, availableTags)
        },
    ).withPrunedDnsEvaluationReferences()
}

internal data class ManagedRuleSetChoice(
    val tag: String,
    val remarks: String,
    val fileName: String,
)

internal fun AppState.managedRuleSetChoices(
    availableFileNames: Iterable<String>,
): List<ManagedRuleSetChoice> {
    val available = availableFileNames.mapTo(mutableSetOf()) { name -> name.lowercase() }
    val bundled = ResourceFileKind.entries.mapNotNull { kind ->
        kind.fileName
            .takeIf { fileName ->
                fileName.endsWith(SingBoxRuleSetExtension, ignoreCase = true) &&
                    fileName.lowercase() in available
            }
            ?.let { fileName ->
                ManagedRuleSetChoice(
                    tag = managedBundledRuleSetTag(kind),
                    remarks = fileName,
                    fileName = fileName,
                )
            }
    }
    val custom = customResourceFiles.mapNotNull { file ->
        file.name
            .takeIf { fileName ->
                fileName.hasSingBoxRuleSetExtension() &&
                    fileName.lowercase() in available
            }
            ?.let { fileName ->
                ManagedRuleSetChoice(
                    tag = managedCustomRuleSetTag(file.id, fileName),
                    remarks = fileName,
                    fileName = fileName,
                )
            }
    }
    return bundled + custom
}

internal fun AppState.withRemovedManagedRuleSets(
    fileNames: Set<String>,
): AppState {
    val normalizedNames = fileNames.mapTo(mutableSetOf()) { name -> name.lowercase() }
    val removedTags = customResourceFiles
        .filter { file -> file.name.lowercase() in normalizedNames }
        .mapTo(mutableSetOf()) { file -> managedCustomRuleSetTag(file.id, file.name) }
    if (removedTags.isEmpty()) return this
    return copy(
        ebpfBypassRuleSetTags = ebpfBypassRuleSetTags.filterNot(removedTags::contains),
        routeRules = routeRules.map { rule ->
            rule.updateManagedRuleSetReferences { tag -> tag.takeUnless(removedTags::contains) }
        },
        dnsRules = dnsRules.map { rule ->
            rule.updateManagedMatchReferences(SingBoxRuleSetField) { tag ->
                tag.takeUnless(removedTags::contains)
            }
        },
    ).withPrunedDnsEvaluationReferences()
}

internal fun AppState.withUnavailableManagedRuleSetsDisabled(
    availableTags: Set<String>,
): AppState =
    copy(
        routeRules = routeRules.map { rule ->
            rule.disableUnavailableRuleSetReferences(availableTags)
        },
        dnsRules = dnsRules.map { rule ->
            rule.disableUnavailableDnsMatchReferences(SingBoxRuleSetField, availableTags)
        },
    ).withPrunedDnsEvaluationReferences()

internal fun AppState.withPrunedDnsEvaluationReferences(): AppState {
    val taggedResponses = mutableSetOf<String>()
    val updatedRules = dnsRules.map { rule ->
        val updatedRule = rule.disableUnavailableDnsEvaluationReferences(taggedResponses)
        if (updatedRule.enabled && updatedRule.action == SingBoxDnsEvaluateAction) {
            taggedResponses += updatedRule.evaluationTag
        }
        updatedRule
    }
    return copy(dnsRules = updatedRules)
}

internal fun List<OutboundState>.replaceManagedReference(
    field: String,
    previousTag: String,
    replacementTag: String,
): List<OutboundState> = map { outbound ->
    outbound.updateManagedReference(field, previousTag, replacementTag)
}

internal fun List<OutboundState>.clearUnavailableManagedReferences(
    field: String,
    availableTags: Set<String>,
): List<OutboundState> = map { outbound ->
    val root = outbound.jsonObject() ?: return@map outbound
    val current = (root[field] as? JsonPrimitive)?.contentOrNull.orEmpty()
    if (current.isBlank() || current in availableTags) {
        outbound
    } else {
        outbound.copy(json = root.withReference(field, "").encoded())
    }
}

internal fun List<SingBoxEndpointState>.replaceEndpointManagedReference(
    field: String,
    previousTag: String,
    replacementTag: String,
): List<SingBoxEndpointState> = map { endpoint ->
    endpoint.updateManagedReference(field, previousTag, replacementTag)
}

internal fun List<SingBoxEndpointState>.clearUnavailableEndpointManagedReferences(
    field: String,
    availableTags: Set<String>,
): List<SingBoxEndpointState> = map { endpoint ->
    val root = endpoint.jsonObject() ?: return@map endpoint
    val current = (root[field] as? JsonPrimitive)?.contentOrNull.orEmpty()
    if (current.isBlank() || current in availableTags) {
        endpoint
    } else {
        endpoint.copy(json = root.withReference(field, "").encoded())
    }
}

internal fun AppState.withPrunedDnsServerReferences(): AppState {
    val availableTags = dnsServers.mapNotNullTo(mutableSetOf()) { server ->
        server.tag.trim().takeIf(String::isNotEmpty)
    }
    val preferredByTags = selectablePreferredByDnsServerTags(this).toSet()
    return copy(
        routeDefaultDomainResolver = routeDefaultDomainResolver
            .takeIf { tag -> tag.isBlank() || tag in availableTags }
            .orEmpty(),
        dnsServers = dnsServers
            .map { server ->
                if (server.type == "group") {
                    val pruned = server.servers
                        .filter { member -> member in availableTags && member != server.tag }
                    if (pruned == server.servers) server else server.copy(servers = pruned)
                } else {
                    server
                }
            }
            .filterNot { server -> server.type == "group" && server.servers.isEmpty() },
        outbounds = outbounds.clearUnavailableManagedReferences(
            field = "domain_resolver",
            availableTags = availableTags,
        ),
        endpoints = endpoints.clearUnavailableEndpointManagedReferences(
            field = "domain_resolver",
            availableTags = availableTags,
        ),
        dnsRules = dnsRules.map { rule ->
            rule.updateManagedMatchReferences("preferred_by") { tag ->
                tag.takeIf(preferredByTags::contains)
            }
        },
    ).withPrunedDnsEvaluationReferences()
}

internal fun AppState.withRemovedManagedDnsServers(
    removedTags: Set<String>,
): AppState {
    val unavailableTags = removedTags
        .map(String::trim)
        .filterTo(mutableSetOf(), String::isNotEmpty)
    if (unavailableTags.isEmpty()) return this
    while (true) {
        val dependentTags = dnsServers
            .filter { server ->
                server.tag !in unavailableTags &&
                    server.domainResolver in unavailableTags &&
                    server.requiresManagedDomainResolver()
            }
            .mapTo(mutableSetOf(), SingBoxDnsServerState::tag)
        if (dependentTags.isEmpty()) break
        unavailableTags += dependentTags
    }
    val remainingServers = dnsServers
        .filterNot { server -> server.tag in unavailableTags }
        .map { server ->
            server.copy(
                domainResolver = server.domainResolver
                    .takeUnless(unavailableTags::contains)
                    .orEmpty(),
                servers = if (server.type == "group") {
                    server.servers.filterNot(unavailableTags::contains)
                } else {
                    server.servers
                },
            )
        }
        .filterNot { server -> server.type == "group" && server.servers.isEmpty() }
    if (remainingServers.size == dnsServers.size) return this
    return copy(
        dnsServers = remainingServers,
        dnsFinal = if (dnsFinal in unavailableTags) {
            remainingServers.firstOrNull()?.tag.orEmpty()
        } else {
            dnsFinal
        },
        routeDefaultDomainResolver = routeDefaultDomainResolver
            .takeUnless(unavailableTags::contains)
            .orEmpty(),
        dnsRules = dnsRules.mapNotNull { rule ->
            if (
                rule.action in DnsActionsWithManagedServer &&
                rule.server in unavailableTags
            ) {
                null
            } else {
                rule.updateManagedMatchReferences("preferred_by") { tag ->
                    tag.takeUnless(unavailableTags::contains)
                }
            }
        },
    ).withPrunedDnsServerReferences()
}

private fun SingBoxDnsServerState.requiresManagedDomainResolver(): Boolean {
    if (type !in NetworkDnsServerTypesWithDomainResolver) return false
    val address = server.trim()
    if (address.isBlank()) return false
    val unwrapped = address
        .takeIf { value -> value.length > 2 && value.first() == '[' && value.last() == ']' }
        ?.substring(1, address.lastIndex)
        ?: address
    return !isIpAddress(unwrapped)
}

private fun AppState.outboundDependsOn(
    tag: String,
    targetTag: String,
    visited: MutableSet<String>,
): Boolean {
    if (!visited.add(tag)) return false
    val dependencies = when {
        tag == APP_DIRECT_OUTBOUND -> emptyList()
        tag == APP_GLOBAL_SELECTOR -> {
            val enabledGroupIds = outboundGroups
                .filter(OutboundGroupState::enabled)
                .mapTo(mutableSetOf(), OutboundGroupState::id)
            val validManagedGroupIds = outbounds
                .filter { outbound ->
                    outbound.groupId in enabledGroupIds &&
                        outbound.jsonObject() != null
                }
                .mapTo(mutableSetOf(), OutboundState::groupId)
            outboundGroups
                .filter { group -> group.enabled && group.id in validManagedGroupIds }
                .map { group -> managedOutboundGroupSelectorTag(group.id, group.name) } +
                endpoints
                    .filter { endpoint ->
                        endpoint.type in SupportedSingBoxEndpointTypes &&
                            endpoint.jsonObject() != null
                    }
                    .map(SingBoxEndpointState::tag)
        }
        selectors.any { selector -> selector.tag == tag } ->
            selectors.first { selector -> selector.tag == tag }.outbounds
        outboundGroups.any { group ->
            managedOutboundGroupSelectorTag(group.id, group.name) == tag
        } -> {
            val groupId = outboundGroups.first { group ->
                managedOutboundGroupSelectorTag(group.id, group.name) == tag
            }.id
            outbounds
                .filter { outbound -> outbound.groupId == groupId }
                .map(OutboundState::tag)
        }
        else -> {
            val managedOutbound = outbounds.firstOrNull { outbound -> outbound.tag == tag }
            val managedJson = managedOutbound?.jsonObject()
                ?: endpoints.firstOrNull { endpoint -> endpoint.tag == tag }?.jsonObject()
            if (
                managedOutbound?.type == SingBoxSelectorTypeSelector ||
                managedOutbound?.type == SingBoxSelectorTypeUrlTest
            ) {
                (managedJson?.get("outbounds") as? JsonArray)
                    .orEmpty()
                    .mapNotNull { value -> (value as? JsonPrimitive)?.contentOrNull }
                    .filter(String::isNotBlank)
            } else {
                managedJson
                    ?.get("detour")
                    ?.let { value -> (value as? JsonPrimitive)?.contentOrNull }
                    ?.takeIf(String::isNotBlank)
                    ?.let(::listOf)
                    .orEmpty()
            }
        }
    }
    return dependencies.any { dependency ->
        dependency == targetTag || outboundDependsOn(dependency, targetTag, visited)
    }
}

private val ManagedOutboundChoiceKind.priority: Int
    get() = when (this) {
        ManagedOutboundChoiceKind.Selector -> 0
        ManagedOutboundChoiceKind.UrlTest -> 1
        ManagedOutboundChoiceKind.Endpoint -> 2
        ManagedOutboundChoiceKind.Direct -> 3
        ManagedOutboundChoiceKind.GlobalSelector -> 4
        ManagedOutboundChoiceKind.Outbound -> 5
    }

private fun SingBoxRouteRuleState.updateManagedRuleSetReferences(
    transform: (String) -> String?,
): SingBoxRouteRuleState {
    val updatedRuleSet = ruleSet.mapNotNull(transform).distinct()
    val updatedLogicalRules = logicalRules.map { rule ->
        rule.updateManagedRuleSetReferences(transform)
    }
    val lostRequiredReference = ruleSet.isNotEmpty() && updatedRuleSet.isEmpty()
    val lostEnabledChild =
        type == SingBoxRouteRuleTypeLogical &&
            logicalRules.zip(updatedLogicalRules).any { (previous, updated) ->
                previous.enabled && !updated.enabled
            }
    return copy(
        enabled = enabled && !lostRequiredReference && !lostEnabledChild,
        ruleSet = updatedRuleSet,
        logicalRules = updatedLogicalRules,
    )
}

private fun SingBoxRouteRuleState.disableUnavailableInboundReferences(
    availableTags: Set<String>,
): SingBoxRouteRuleState {
    val updatedLogicalRules = logicalRules.map { rule ->
        rule.disableUnavailableInboundReferences(availableTags)
    }
    val hasUnavailableReference = inbound.any { tag -> tag !in availableTags }
    val lostEnabledChild =
        type == SingBoxRouteRuleTypeLogical &&
            logicalRules.zip(updatedLogicalRules).any { (previous, updated) ->
                previous.enabled && !updated.enabled
            }
    return copy(
        enabled = enabled && !hasUnavailableReference && !lostEnabledChild,
        logicalRules = updatedLogicalRules,
    )
}

private fun SingBoxRouteRuleState.disableUnavailableRuleSetReferences(
    availableTags: Set<String>,
): SingBoxRouteRuleState {
    val updatedLogicalRules = logicalRules.map { rule ->
        rule.disableUnavailableRuleSetReferences(availableTags)
    }
    val hasUnavailableReference = ruleSet.any { tag -> tag !in availableTags }
    val lostEnabledChild =
        type == SingBoxRouteRuleTypeLogical &&
            logicalRules.zip(updatedLogicalRules).any { (previous, updated) ->
                previous.enabled && !updated.enabled
            }
    return copy(
        enabled = enabled && !hasUnavailableReference && !lostEnabledChild,
        logicalRules = updatedLogicalRules,
    )
}

internal fun SingBoxDnsRuleState.updateManagedMatchReferences(
    field: String,
    transform: (String) -> String?,
): SingBoxDnsRuleState {
    var lostRequiredReference = false
    val updatedMatches = matches.mapNotNull { match ->
        if (match.field != field) return@mapNotNull match
        val updatedValues = match.values.mapNotNull(transform).distinct()
        if (
            type != SingBoxDnsRuleTypeLogical &&
            match.values.isNotEmpty() &&
            updatedValues.isEmpty()
        ) {
            lostRequiredReference = true
        }
        match.copy(values = updatedValues)
            .takeIf { updated -> updated.values.isNotEmpty() }
    }
    val updatedLogicalRules = logicalRules.map { rule ->
        rule.updateManagedMatchReferences(field, transform)
    }
    val lostEnabledChild =
        type == SingBoxDnsRuleTypeLogical &&
            logicalRules.zip(updatedLogicalRules).any { (previous, updated) ->
                previous.enabled && !updated.enabled
            }
    return copy(
        matches = updatedMatches,
        logicalRules = updatedLogicalRules,
        enabled = enabled && !lostRequiredReference && !lostEnabledChild,
    )
}

private fun SingBoxDnsRuleState.disableUnavailableDnsMatchReferences(
    field: String,
    availableTags: Set<String>,
): SingBoxDnsRuleState {
    val updatedLogicalRules = logicalRules.map { rule ->
        rule.disableUnavailableDnsMatchReferences(field, availableTags)
    }
    val hasUnavailableReference = type != SingBoxDnsRuleTypeLogical &&
        matches.any { match ->
            match.field == field && match.values.any { tag -> tag !in availableTags }
        }
    val lostEnabledChild =
        type == SingBoxDnsRuleTypeLogical &&
            logicalRules.zip(updatedLogicalRules).any { (previous, updated) ->
                previous.enabled && !updated.enabled
            }
    return copy(
        enabled = enabled && !hasUnavailableReference && !lostEnabledChild,
        logicalRules = updatedLogicalRules,
    )
}

private fun SingBoxDnsRuleState.disableUnavailableDnsEvaluationReferences(
    availableTags: Set<String>,
): SingBoxDnsRuleState {
    val updatedLogicalRules = logicalRules.map { rule ->
        rule.disableUnavailableDnsEvaluationReferences(availableTags)
    }
    val hasUnavailableReference = type != SingBoxDnsRuleTypeLogical &&
        matches.any { match ->
            if (match.field != SingBoxMatchResponseField) return@any false
            val value = match.values.firstOrNull().orEmpty()
            !match.encodeAsString || value !in availableTags
        }
    val lostEnabledChild =
        type == SingBoxDnsRuleTypeLogical &&
            logicalRules.zip(updatedLogicalRules).any { (previous, updated) ->
                previous.enabled && !updated.enabled
            }
    return copy(
        enabled = enabled && !hasUnavailableReference && !lostEnabledChild,
        logicalRules = updatedLogicalRules,
    )
}

private fun OutboundState.updateManagedReference(
    field: String,
    previousTag: String,
    replacementTag: String,
): OutboundState {
    val root = jsonObject() ?: return this
    val current = (root[field] as? JsonPrimitive)?.contentOrNull
    if (current != previousTag) return this
    return copy(json = root.withReference(field, replacementTag).encoded())
}

private fun SingBoxEndpointState.updateManagedReference(
    field: String,
    previousTag: String,
    replacementTag: String,
): SingBoxEndpointState {
    val root = jsonObject() ?: return this
    val current = (root[field] as? JsonPrimitive)?.contentOrNull
    if (current != previousTag) return this
    return copy(json = root.withReference(field, replacementTag).encoded())
}

private fun OutboundState.jsonObject(): JsonObject? =
    runCatching {
        SingBoxJson.parseToJsonElement(json) as JsonObject
    }.getOrNull()

private fun SingBoxEndpointState.jsonObject(): JsonObject? =
    runCatching {
        SingBoxJson.parseToJsonElement(json) as JsonObject
    }.getOrNull()

private fun JsonObject.withReference(
    field: String,
    value: String,
): JsonObject = JsonObject(
    toMutableMap().apply {
        if (value.isBlank()) remove(field) else put(field, JsonPrimitive(value))
    },
)

private fun JsonObject.encoded(): String =
    SingBoxJson.encodeToString(JsonElement.serializer(), this)

private const val SingBoxRuleSetExtension = ".srs"
private const val SingBoxRuleSetField = "rule_set"
private const val SingBoxInboundField = "inbound"
private const val SingBoxMatchResponseField = "match_response"
private const val SingBoxDnsEvaluateAction = "evaluate"
private val CanonicalJsonReferenceFields = setOf("detour", "domain_resolver")
private val CanonicalJsonSelectorTypes = setOf(
    SingBoxSelectorTypeSelector,
    SingBoxSelectorTypeUrlTest,
)
private val CanonicalDnsReferenceFields =
    setOf(SingBoxInboundField, SingBoxRuleSetField, "preferred_by", SingBoxMatchResponseField)
private val DnsActionsWithManagedServer = setOf("route", "evaluate")
private val NetworkDnsServerTypesWithDomainResolver =
    setOf("udp", "tcp", "tls", "quic", "https", "h3")
private val PreferredByDnsServerTypes =
    setOf("hosts", "local", "mdns", "tailscale", "openconnect", "resolved")

private fun MutableMap<String, String>.putVisibleRemarks(tag: String, remarks: String) {
    remarks.trim()
        .takeIf(String::isNotEmpty)
        ?.let { visibleRemarks -> put(tag.trim(), visibleRemarks) }
}
