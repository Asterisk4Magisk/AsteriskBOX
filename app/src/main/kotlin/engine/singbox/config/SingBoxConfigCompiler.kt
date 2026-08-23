// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package engine.singbox.config

import android.content.Context
import app.AppState
import app.ManagedApiServiceTag
import app.ManagedDirectOutboundTag
import app.ManagedGlobalSelectorTag
import app.ManagedLocalInboundTag
import app.ManagedRootInboundTag
import app.ManagedTunInboundTag
import app.OutboundState
import app.SingBoxRouteNetworkStrategies
import app.SingBoxRouteNetworkTypes
import app.SingBoxRouteRuleActionReject
import app.SingBoxRouteRuleLogicalModeOr
import app.SingBoxRouteRuleState
import app.SingBoxRouteRuleTypeLogical
import app.SingBoxSelectorTypeSelector
import app.SingBoxSelectorTypeUrlTest
import app.expandSelectorMemberReferences
import app.isManagedSingBoxTag
import app.managedOutboundGroupSelectorTag
import app.managedRuleSetChoices
import app.modes.RunModeBpf2Socks
import app.modes.RunModeEbpf
import app.modes.RunModeTproxy
import app.modes.RunModeTun
import app.modes.RunModeTun2Socks
import app.modes.RunModeVpnService
import app.modes.SingBoxModeDirect
import app.modes.SingBoxModeGlobal
import app.modes.isRootRunMode
import app.withCanonicalManagedTagReferences
import app.withPrunedDnsServerReferences
import app.withUnavailableManagedRuleSetsDisabled
import engine.network.toPortOrNull
import engine.proxy.LocalProxyLoopbackAddress
import engine.proxy.toLocalProxyOptions
import engine.root.RootModeEngine
import engine.singbox.isNonNegativeSingBoxDuration
import engine.singbox.singBoxControlConfig
import engine.vpn.toTunOptions
import features.resources.SingBoxRuleSetFileFormat
import features.resources.runtime.singBoxRuleSetFiles
import features.resources.singBoxRuleSetFormatOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.File

internal const val APP_GLOBAL_SELECTOR = ManagedGlobalSelectorTag
internal const val APP_LOCAL_INBOUND = ManagedLocalInboundTag
internal const val APP_TUN_INBOUND = ManagedTunInboundTag
internal const val APP_DIRECT_OUTBOUND = ManagedDirectOutboundTag
internal const val APP_ROOT_INBOUND = ManagedRootInboundTag

internal data class SingBoxLocalRuleSet(
    val tag: String,
    val path: String,
    val format: SingBoxRuleSetFileFormat,
)

internal object SingBoxConfigCompiler {
    fun compile(
        context: Context,
        appState: AppState,
        runMode: Int = appState.runMode,
        exposePorts: Boolean = true,
        customRuleSetFileOverrides: Map<Int, File> = emptyMap(),
    ): String {
        val canonicalState = appState.withCanonicalManagedTagReferences()
        val filesByName = context.singBoxRuleSetFiles(canonicalState.customResourceFiles)
            .associateByTo(linkedMapOf()) { file -> file.name.lowercase() }
        canonicalState.customResourceFiles.forEach { customFile ->
            customRuleSetFileOverrides[customFile.id]
                ?.takeIf { file -> file.isFile && file.length() > 0L }
                ?.let { file -> filesByName[customFile.name.lowercase()] = file }
        }
        val choicesByFileName = canonicalState
            .managedRuleSetChoices(filesByName.keys)
            .associateBy { choice -> choice.fileName }
        val localRuleSets = choicesByFileName.values.mapNotNull { choice ->
            val file = filesByName[choice.fileName.lowercase()] ?: return@mapNotNull null
            val format = choice.fileName.singBoxRuleSetFormatOrNull() ?: return@mapNotNull null
            SingBoxLocalRuleSet(
                tag = choice.tag,
                path = file.absolutePath,
                format = format,
            )
        }.distinctBy(SingBoxLocalRuleSet::tag)
        val runtimeState = canonicalState
            .withUnavailableManagedRuleSetsDisabled(
                localRuleSets.mapTo(mutableSetOf(), SingBoxLocalRuleSet::tag),
            )
            .withPrunedDnsServerReferences()
        return compileGenerated(
            appState = runtimeState,
            runMode = runMode,
            exposePorts = exposePorts,
            localRuleSets = localRuleSets,
            ebpfUidPolicy = if (runMode == RunModeEbpf) {
                context.resolveEbpfUidPolicy(runtimeState)
            } else {
                EbpfUidPolicy()
            },
        )
    }

    internal fun compileGenerated(
        appState: AppState,
        runMode: Int = appState.runMode,
        exposePorts: Boolean = true,
        localRuleSets: List<SingBoxLocalRuleSet> = emptyList(),
        ebpfUidPolicy: EbpfUidPolicy = EbpfUidPolicy(),
    ): String {
        val encoded = encodeSingBoxJson(
            compileGeneratedRoot(
                appState = appState,
                runMode = runMode,
                exposePorts = exposePorts,
                localRuleSets = localRuleSets,
                ebpfUidPolicy = ebpfUidPolicy,
            ),
        )
        SingBoxConfigChecker.check(encoded)
        return encoded
    }

    internal fun compileGeneratedRoot(
        appState: AppState,
        runMode: Int = appState.runMode,
        exposePorts: Boolean = true,
        localRuleSets: List<SingBoxLocalRuleSet> = emptyList(),
        ebpfUidPolicy: EbpfUidPolicy = EbpfUidPolicy(),
    ): JsonObject = generateRoot(
        sourceRoot = JsonObject(emptyMap()),
        appState = appState.withCanonicalManagedTagReferences(),
        runMode = runMode,
        exposePorts = exposePorts,
        localRuleSets = localRuleSets,
        ebpfUidPolicy = ebpfUidPolicy,
    )

    private fun generateRoot(
        sourceRoot: JsonObject,
        appState: AppState,
        runMode: Int = appState.runMode,
        exposePorts: Boolean = true,
        localRuleSets: List<SingBoxLocalRuleSet> = emptyList(),
        ebpfUidPolicy: EbpfUidPolicy = EbpfUidPolicy(),
    ): JsonObject {
        val managedSourceRoot = sourceRoot.withLocalRuleSets(localRuleSets)
        val availableRuleSetTags = localRuleSets.mapTo(linkedSetOf(), SingBoxLocalRuleSet::tag)
        val dnsResult = SingBoxDnsCompiler.compile(appState)
        var runtime = managedSourceRoot
            .updated("log", compileLog(managedSourceRoot["log"] as? JsonObject, appState))
            .updated(
                "inbounds",
                compileInbounds(
                    root = managedSourceRoot,
                    appState = appState,
                    runMode = runMode,
                    exposePorts = exposePorts,
                    ebpfUidPolicy = ebpfUidPolicy,
                    availableRuleSetTags = availableRuleSetTags,
                ),
            )
            .updated("endpoints", compileEndpoints(managedSourceRoot, appState))
            .updated("outbounds", compileOutbounds(managedSourceRoot, appState))
            .updated("services", compileServices(managedSourceRoot, appState, runMode, exposePorts))

        runtime = runtime.updated("dns", dnsResult?.dns)
        val availableOutboundTags = sequenceOf(
            runtime["endpoints"] as? JsonArray,
            runtime["outbounds"] as? JsonArray,
        )
            .filterNotNull()
            .flatMap(JsonArray::asSequence)
            .mapNotNull { target ->
                ((target as? JsonObject)?.get("tag") as? JsonPrimitive)
                    ?.contentOrNull
                    ?.takeIf(String::isNotBlank)
            }
            .toSet()
        runtime = runtime.updated(
            "route",
            compileRoute(
                sourceRoute = managedSourceRoot["route"] as? JsonObject,
                appState = appState,
                availableOutboundTags = availableOutboundTags,
                dnsEnabled = runtime["dns"] is JsonObject,
                defaultDomainResolver = dnsResult?.defaultDomainResolver,
            ),
        )

        SingBoxDeprecatedConfigValidator.validate(runtime)
        runtime = runtime.updated(
            "experimental",
            compileExperimental(appState),
        )
        return runtime
    }
}

internal fun JsonObject.withLocalRuleSets(localRuleSets: List<SingBoxLocalRuleSet>): JsonObject {
    val sourceRoute = this["route"] as? JsonObject
    val sourceRuleSets = sourceRoute?.get("rule_set") as? JsonArray
    if (sourceRuleSets == null && localRuleSets.isEmpty()) return this
    val retainedRuleSets = sourceRuleSets
        ?.filterNot { element ->
            val tag = ((element as? JsonObject)?.get("tag") as? JsonPrimitive)?.contentOrNull
            tag?.let(::isManagedSingBoxTag) == true
        }
        .orEmpty()
    val compiledRuleSets: List<JsonElement> = retainedRuleSets + localRuleSets.map { ruleSet ->
        buildJsonObject {
            put("type", "local")
            put("tag", ruleSet.tag)
            put("format", ruleSet.format.configValue)
            put("path", ruleSet.path)
        }
    }
    val managedRoute = JsonObject(
        buildMap {
            sourceRoute?.let(::putAll)
            put("rule_set", JsonArray(compiledRuleSets))
        },
    )
    return updated("route", managedRoute)
}

private fun compileLog(source: JsonObject?, appState: AppState): JsonObject =
    JsonObject(
        buildMap {
            source?.let(::putAll)
            remove("disabled")
            put(
                "level",
                JsonPrimitive(appState.coreLogLevel),
            )
        },
    )

private fun compileInbounds(
    root: JsonObject,
    appState: AppState,
    runMode: Int,
    exposePorts: Boolean,
    ebpfUidPolicy: EbpfUidPolicy,
    availableRuleSetTags: Set<String>,
): JsonArray {
    val retained = (root["inbounds"] as? JsonArray)
        .orEmptyObjects()
        .filterNot(JsonObject::hasAppTag)
        .toMutableList()
    if (!exposePorts) return JsonArray(retained)

    retained += compileLocalInbound(appState)
    when (runMode) {
        RunModeVpnService -> if (!appState.enableVpnHevTun) {
            retained += compileTunInbound(appState, rootMode = false)
        }
        RunModeTproxy -> retained += buildJsonObject {
            put("type", "tproxy")
            put("tag", APP_ROOT_INBOUND)
            put("listen", "0.0.0.0")
            put("listen_port", appState.transparentProxyPort.toPortOrNull() ?: RootModeEngine.DefaultTproxyPort)
        }
        RunModeTun2Socks, RunModeBpf2Socks -> retained += buildJsonObject {
            put("type", "socks")
            put("tag", APP_ROOT_INBOUND)
            put("listen", LocalProxyLoopbackAddress)
            put("listen_port", appState.socks5ProxyPort.toPortOrNull() ?: RootModeEngine.DefaultTun2SocksProxyPort)
        }
        RunModeTun -> retained += compileTunInbound(appState, rootMode = true)
        RunModeEbpf -> retained += compileEbpfInbound(
            appState = appState,
            uidPolicy = ebpfUidPolicy,
            availableRuleSetTags = availableRuleSetTags,
        )
    }
    return JsonArray(retained)
}

internal fun compileEbpfInbound(
    appState: AppState,
    uidPolicy: EbpfUidPolicy,
    availableRuleSetTags: Set<String>,
): JsonObject {
    val sharedInterfaces = normalizeEbpfSharedNetworkInterfaces(appState.ebpfSharedNetworkInterfaces)
    val ipv6Mode = if (appState.enableIpv6) "always" else "off"
    return buildJsonObject {
        put("type", "ebpf")
        put("tag", APP_ROOT_INBOUND)
        put("mode", if (sharedInterfaces.isEmpty()) "local" else "hybrid")
        put("dns_mode", if (appState.enableLocalDns) "hijack" else "off")
        putJsonObject("local") {
            put("ipv6_mode", ipv6Mode)
            put("bypass_private_address", false)
            if (uidPolicy.includeUids.isNotEmpty()) {
                putJsonArray("include_uid") {
                    uidPolicy.includeUids.distinct().sorted().forEach(::add)
                }
            }
            if (uidPolicy.excludeUids.isNotEmpty()) {
                putJsonArray("exclude_uid") {
                    uidPolicy.excludeUids.distinct().sorted().forEach(::add)
                }
            }
        }
        val bypassRuleSets = appState.availableEbpfBypassRuleSetTags(availableRuleSetTags)
        if (bypassRuleSets.isNotEmpty()) {
            putJsonArray("bypass_rule_set") {
                bypassRuleSets.forEach(::add)
            }
        }
        if (sharedInterfaces.isNotEmpty()) {
            putJsonObject("shared") {
                putJsonArray("interface") {
                    sharedInterfaces.forEach(::add)
                }
                put("bypass_private_address", false)
                put("ipv6_mode", ipv6Mode)
            }
        }
    }
}

private fun AppState.availableEbpfBypassRuleSetTags(
    availableTags: Set<String>,
): List<String> =
    ebpfBypassRuleSetTags
        .map(String::trim)
        .filter(String::isNotEmpty)
        .distinct()
        .filter(availableTags::contains)

private fun compileLocalInbound(appState: AppState): JsonObject {
    val options = appState.toLocalProxyOptions()
    return buildJsonObject {
        put("type", "mixed")
        put("tag", APP_LOCAL_INBOUND)
        put("listen", options.listenAddress)
        put("listen_port", options.port)
        if (options.username.isNotBlank()) {
            putJsonArray("users") {
                add(
                    buildJsonObject {
                        put("username", options.username)
                        put("password", options.password)
                    },
                )
            }
        }
    }
}

private fun compileTunInbound(appState: AppState, rootMode: Boolean): JsonObject {
    val options = appState.toTunOptions()
    return buildJsonObject {
        put("type", "tun")
        put("tag", APP_TUN_INBOUND)
        if (rootMode) {
            put("interface_name", SingBoxTunDevice)
        } else {
            put("auto_route", true)
        }
        put("mtu", options.mtu)
        putJsonArray("address") {
            add("${options.ipv4Address.address}/${options.ipv4Address.prefixLength}")
            if (appState.enableIpv6) {
                add("${options.ipv6Address.address}/${options.ipv6Address.prefixLength}")
            }
        }
        put("dns_mode", if (appState.enableLocalDns) "hijack" else "disabled")
        if (appState.enableLocalDns) {
            putJsonArray("dns_address") {
                options.dnsServers.forEach(::add)
            }
        }
        put(
            "stack",
            when (appState.singBoxTunStack) {
                app.modes.SingBoxTunStackGvisor -> "gvisor"
                app.modes.SingBoxTunStackMixed -> "mixed"
                else -> "system"
            },
        )
    }
}

internal fun compileOutbounds(root: JsonObject, appState: AppState): JsonArray {
    val enabledGroups = appState.outboundGroups
        .filter { group -> group.enabled }
    val enabledGroupIds = enabledGroups.mapTo(mutableSetOf()) { group -> group.id }
    val managedOutbounds = appState.outbounds
        .asSequence()
        .filter { outbound -> outbound.groupId in enabledGroupIds }
        .mapNotNull { outbound ->
            runCatching { parseSingBoxJson(outbound.json) }
                .getOrNull()
                ?.takeIf { parsed -> outbound.shouldRetainRawGroupedOutbound(parsed) }
                ?.let { parsed ->
                    outbound.groupId to JsonObject(
                        buildMap {
                            putAll(parsed)
                            put("type", JsonPrimitive(outbound.type))
                            put("tag", JsonPrimitive(outbound.tag))
                        },
                    )
                }
        }
        .distinctBy { (_, outbound) ->
            (outbound["tag"] as? JsonPrimitive)?.contentOrNull
        }
        .toList()
    val managedTags = managedOutbounds.mapNotNullTo(mutableSetOf()) { (_, outbound) ->
        (outbound["tag"] as? JsonPrimitive)?.contentOrNull
    }
    val claimedCustomSelectorTags = appState.selectors
        .mapTo(mutableSetOf()) { selector -> selector.tag }
    val retained = (root["outbounds"] as? JsonArray)
        .orEmptyObjects()
        .filterNot { outbound ->
            outbound.hasAppTag() ||
                (outbound["tag"] as? JsonPrimitive)?.contentOrNull in managedTags ||
                (outbound["tag"] as? JsonPrimitive)?.contentOrNull in claimedCustomSelectorTags
        }
        .toMutableList()
    retained += managedOutbounds.map { (_, outbound) -> outbound }
    val outboundCandidates = retained.mapNotNull { outbound ->
        val tag = (outbound["tag"] as? JsonPrimitive)
            ?.contentOrNull
            ?.takeIf(String::isNotBlank)
            ?: return@mapNotNull null
        val type = (outbound["type"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        tag to type
    }
    val outboundCandidateTags = outboundCandidates.map { (tag, _) -> tag }
    val endpointCandidateTags = compileEndpoints(root, appState).mapNotNull { endpoint ->
        (endpoint as? JsonObject)
            ?.get("tag")
            ?.let { tag -> (tag as? JsonPrimitive)?.contentOrNull }
            ?.takeIf(String::isNotBlank)
    }
    val candidateTags = (outboundCandidateTags + endpointCandidateTags).distinct()
    val candidateSelectorTags = outboundCandidates
        .filter { (_, type) -> type == SingBoxSelectorTypeSelector }
        .map { (tag, _) -> tag }
    val candidateUrlTestTags = outboundCandidates
        .filter { (_, type) -> type == SingBoxSelectorTypeUrlTest }
        .map { (tag, _) -> tag }
    val groupedCandidateTags = (candidateSelectorTags + candidateUrlTestTags).toSet()
    val ordinaryCandidateTags = candidateTags.filterNot(groupedCandidateTags::contains)
    retained += buildJsonObject {
        put("type", "direct")
        put("tag", APP_DIRECT_OUTBOUND)
    }
    val managedGroupTags = mutableListOf<String>()
    enabledGroups.forEach { group ->
        val members = managedOutbounds
            .filter { (groupId, _) -> groupId == group.id }
            .mapNotNull { (_, outbound) ->
                (outbound["tag"] as? JsonPrimitive)?.contentOrNull
                    ?.takeIf(String::isNotBlank)
            }
        if (members.isNotEmpty()) {
            val groupTag = managedOutboundGroupSelectorTag(group.id, group.name)
            retained += buildSelectorOutbound(
                tag = groupTag,
                members = members,
                default = appState.selectorDefault(
                    selectorTag = groupTag,
                    members = members,
                    fallback = members.first(),
                ),
                interruptExistConnections = true,
            )
            managedGroupTags += groupTag
        }
    }
    val managedSelectors = appState.selectors
        .distinctBy { selector -> selector.id }
        .filter { selector ->
            selector.type == SingBoxSelectorTypeSelector ||
                selector.type == SingBoxSelectorTypeUrlTest
        }
    val baseAvailableCustomMembers = (
        managedGroupTags +
            candidateSelectorTags +
            candidateUrlTestTags +
            APP_DIRECT_OUTBOUND +
            APP_GLOBAL_SELECTOR +
            ordinaryCandidateTags
        ).distinct()
    val emittableManagedSelectorTags = mutableSetOf<String>()
    while (
        managedSelectors
            .filterNot { selector -> selector.tag in emittableManagedSelectorTags }
            .any { selector ->
                val availableMembers = baseAvailableCustomMembers +
                    emittableManagedSelectorTags
                val canEmit = appState.expandSelectorMemberReferences(
                    references = selector.outbounds,
                    availableMemberTags = availableMembers,
                ).any { member -> member != selector.tag }
                if (canEmit) emittableManagedSelectorTags += selector.tag
                canEmit
            }
    ) {
        // Resolve selectors from concrete members outward until no more are emit-able.
    }
    val customSelectorTags = managedSelectors
        .filter { selector -> selector.type == SingBoxSelectorTypeSelector }
        .map { selector -> selector.tag }
        .filter(emittableManagedSelectorTags::contains)
        .distinct()
    val customUrlTestTags = managedSelectors
        .filter { selector -> selector.type == SingBoxSelectorTypeUrlTest }
        .map { selector -> selector.tag }
        .filter(emittableManagedSelectorTags::contains)
        .distinct()
    val globalSelectorMembers = (
        listOf(APP_DIRECT_OUTBOUND) +
            managedGroupTags +
            customSelectorTags +
            customUrlTestTags +
            endpointCandidateTags
        ).distinct()
    val availableCustomMembers = (
        managedGroupTags +
            customSelectorTags +
            candidateSelectorTags +
            customUrlTestTags +
            candidateUrlTestTags +
            APP_DIRECT_OUTBOUND +
            APP_GLOBAL_SELECTOR +
            ordinaryCandidateTags
        ).distinct()
    managedSelectors
        .asSequence()
        .filter { selector -> selector.tag in emittableManagedSelectorTags }
        .forEach { selector ->
            val members = appState.expandSelectorMemberReferences(
                references = selector.outbounds,
                availableMemberTags = availableCustomMembers,
            ).filter { member -> member != selector.tag }
            if (members.isNotEmpty()) {
                when (selector.type) {
                    SingBoxSelectorTypeSelector -> {
                        retained += buildSelectorOutbound(
                            tag = selector.tag,
                            members = members,
                            default = appState.selectorDefault(
                                selectorTag = selector.tag,
                                members = members,
                                fallback = selector.default.trim().takeIf(members::contains)
                                    ?: members.first(),
                            ),
                            interruptExistConnections = selector.interruptExistConnections,
                        )
                    }
                    SingBoxSelectorTypeUrlTest -> {
                        retained += buildUrlTestOutbound(
                            selector = selector,
                            members = members,
                        )
                    }
                }
            }
        }
    retained += buildJsonObject {
        put("type", "selector")
        put("tag", APP_GLOBAL_SELECTOR)
        putJsonArray("outbounds") {
            globalSelectorMembers.forEach(::add)
        }
        put(
            "default",
            appState.selectorDefault(
                selectorTag = APP_GLOBAL_SELECTOR,
                members = globalSelectorMembers,
                fallback = globalSelectorMembers.first(),
            ),
        )
        put("interrupt_exist_connections", true)
    }
    return JsonArray(retained)
}

private fun OutboundState.shouldRetainRawGroupedOutbound(parsed: JsonObject): Boolean {
    if (
        type != SingBoxSelectorTypeSelector &&
        type != SingBoxSelectorTypeUrlTest
    ) {
        return true
    }
    // Malformed shapes stay on the existing compiler/validation path. Only cleanup's [] is silent.
    val members = parsed["outbounds"] as? JsonArray ?: return true
    return members.isNotEmpty()
}

private fun AppState.selectorDefault(
    selectorTag: String,
    members: List<String>,
    fallback: String,
): String = selectorSelections[selectorTag]
    ?.takeIf(members::contains)
    ?: fallback

private fun buildSelectorOutbound(
    tag: String,
    members: List<String>,
    default: String,
    interruptExistConnections: Boolean,
): JsonObject = buildJsonObject {
    put("type", "selector")
    put("tag", tag)
    putJsonArray("outbounds") {
        members.forEach(::add)
    }
    put("default", default)
    put("interrupt_exist_connections", interruptExistConnections)
}

private fun buildUrlTestOutbound(
    selector: app.SingBoxSelectorState,
    members: List<String>,
): JsonObject = buildJsonObject {
    put("type", SingBoxSelectorTypeUrlTest)
    put("tag", selector.tag)
    putJsonArray("outbounds") {
        members.forEach(::add)
    }
    put("url", selector.url.trim())
    put("interval", selector.interval.trim())
    put("tolerance", selector.tolerance)
    put("idle_timeout", selector.idleTimeout.trim())
    put("interrupt_exist_connections", selector.interruptExistConnections)
}

internal fun compileEndpoints(root: JsonObject, appState: AppState): JsonArray {
    val managed = appState.endpoints
        .asSequence()
        .filter { endpoint -> endpoint.type in app.SupportedSingBoxEndpointTypes }
        .mapNotNull { endpoint ->
            runCatching { parseSingBoxJson(endpoint.json) }
                .getOrNull()
                ?.let { parsed ->
                    JsonObject(
                        buildMap {
                            putAll(parsed)
                            put("type", JsonPrimitive(endpoint.type))
                            put("tag", JsonPrimitive(endpoint.tag))
                        },
                    )
                }
        }
        .distinctBy { endpoint ->
            (endpoint["tag"] as? JsonPrimitive)?.contentOrNull
        }
        .toList()
    val managedTags = managed.mapNotNullTo(mutableSetOf()) { endpoint ->
        (endpoint["tag"] as? JsonPrimitive)?.contentOrNull
    }
    val retained = (root["endpoints"] as? JsonArray)
        .orEmptyObjects()
        .filterNot { endpoint ->
            endpoint.hasAppTag() ||
                (endpoint["tag"] as? JsonPrimitive)?.contentOrNull in managedTags
        }
        .toMutableList()
    retained += managed
    return JsonArray(retained)
}

private fun compileServices(
    root: JsonObject,
    appState: AppState,
    runMode: Int,
    exposePorts: Boolean,
): JsonArray? {
    val retained = (root["services"] as? JsonArray)
        .orEmptyObjects()
        .filterNot { service ->
            (service["type"] as? JsonPrimitive)?.contentOrNull == "api" || service.hasAppTag()
        }
        .toMutableList()
    if (exposePorts && runMode.isRootRunMode()) {
        val control = appState.singBoxControlConfig()
        retained += buildJsonObject {
            put("type", "api")
            put("tag", ManagedApiServiceTag)
            put("listen", control.host)
            put("listen_port", control.port)
            if (control.secret.isNotEmpty()) {
                put("secret", control.secret)
            }
        }
    }
    return retained.takeIf(List<JsonObject>::isNotEmpty)?.let(::JsonArray)
}

internal fun compileRoute(
    sourceRoute: JsonObject?,
    appState: AppState,
    availableOutboundTags: Set<String>,
    dnsEnabled: Boolean,
    defaultDomainResolver: String?,
): JsonObject {
    val finalOutbound = resolveRouteFinal(appState, availableOutboundTags)
    val networkStrategy = appState.routeDefaultNetworkStrategy
        .trim()
        .lowercase()
        .takeIf(SingBoxRouteNetworkStrategies::contains)
        .orEmpty()
    val networkTypes = appState.routeDefaultNetworkTypes.sanitizedRouteNetworkTypes()
    val fallbackNetworkTypes =
        appState.routeDefaultFallbackNetworkTypes.sanitizedRouteNetworkTypes()
    val fallbackDelay = appState.routeDefaultFallbackDelay.trim()
    val existingRules = (sourceRoute?.get("rules") as? JsonArray)
        .orEmptyObjects()
    val managedRules = appState.routeRules
        .filter(SingBoxRouteRuleState::enabled)
        .mapNotNull { rule ->
            if (appState.runMode == RunModeVpnService) {
                compileManagedRouteRule(rule)
            } else {
                when (val resolved = rule.resolveClashMode(appState.singBoxMode)) {
                    StaticRouteMatch.Never -> null
                    StaticRouteMatch.Always -> compileManagedRouteAction(rule)
                    is StaticRouteMatch.Rule -> compileManagedRouteRule(resolved.state)
                }
            }
        }
    val injectedRules = buildList {
        addAll(SingBoxSniffCompiler.compile(appState))
        if (dnsEnabled) {
            add(
                buildJsonObject {
                    put("port", 53)
                    put("action", "hijack-dns")
                },
            )
        }
        if (appState.runMode == RunModeVpnService) {
            add(
                buildJsonObject {
                    put("clash_mode", "Global")
                    put("action", "route")
                    put("outbound", APP_GLOBAL_SELECTOR)
                },
            )
            add(
                buildJsonObject {
                    put("clash_mode", "Direct")
                    put("action", "route")
                    put("outbound", APP_DIRECT_OUTBOUND)
                },
            )
        } else {
            when (appState.singBoxMode) {
                SingBoxModeDirect -> add(
                    buildJsonObject {
                        put("action", "route")
                        put("outbound", APP_DIRECT_OUTBOUND)
                    },
                )
                SingBoxModeGlobal -> add(
                    buildJsonObject {
                        put("action", "route")
                        put("outbound", APP_GLOBAL_SELECTOR)
                    },
                )
            }
        }
    }
    val ruleModeFallback = if (appState.runMode == RunModeVpnService) {
        listOf(
            buildJsonObject {
                put("clash_mode", "Rule")
                put("action", "route")
                put("outbound", finalOutbound)
            },
        )
    } else {
        emptyList()
    }
    return JsonObject(
        buildMap {
            sourceRoute
                ?.filterKeys { key -> key !in ManagedRouteSettingKeys }
                ?.let(::putAll)
            put("rules", JsonArray(injectedRules + managedRules + existingRules + ruleModeFallback))
            put("final", JsonPrimitive(finalOutbound))
            if (defaultDomainResolver != null) {
                put("default_domain_resolver", JsonPrimitive(defaultDomainResolver))
            }
            if (appState.routeAutoDetectInterface) {
                put("auto_detect_interface", JsonPrimitive(true))
                if (appState.routeOverrideAndroidVpn) {
                    put("override_android_vpn", JsonPrimitive(true))
                }
                if (networkStrategy.isNotEmpty()) {
                    put("default_network_strategy", JsonPrimitive(networkStrategy))
                }
                if (networkTypes.isNotEmpty()) {
                    put("default_network_type", JsonArray(networkTypes.map(::JsonPrimitive)))
                }
                if (networkStrategy == "fallback") {
                    if (fallbackNetworkTypes.isNotEmpty()) {
                        put(
                            "default_fallback_network_type",
                            JsonArray(fallbackNetworkTypes.map(::JsonPrimitive)),
                        )
                    }
                    if (
                        fallbackDelay.isNotEmpty() &&
                        isNonNegativeSingBoxDuration(fallbackDelay)
                    ) {
                        put("default_fallback_delay", JsonPrimitive(fallbackDelay))
                    }
                }
            }
            if (appState.routeFindProcess) {
                put("find_process", JsonPrimitive(true))
            }
        },
    )
}

internal fun resolveRouteFinal(
    appState: AppState,
    availableOutboundTags: Set<String>,
): String = appState.routeFinal
    .trim()
    .takeIf(availableOutboundTags::contains)
    ?: APP_GLOBAL_SELECTOR

private val ManagedRouteSettingKeys = setOf(
    "auto_detect_interface",
    "override_android_vpn",
    "default_network_strategy",
    "default_network_type",
    "default_fallback_network_type",
    "default_fallback_delay",
    "find_process",
)

private fun List<String>.sanitizedRouteNetworkTypes(): List<String> {
    val selected = map { value -> value.trim().lowercase() }.toSet()
    return SingBoxRouteNetworkTypes.filter(selected::contains)
}

internal fun compileManagedRouteRule(rule: SingBoxRouteRuleState): JsonObject =
    JsonObject(
        compileManagedRouteMatch(rule) + compileManagedRouteAction(rule),
    )

private fun compileManagedRouteAction(rule: SingBoxRouteRuleState): JsonObject =
    buildJsonObject {
        if (rule.action == SingBoxRouteRuleActionReject) {
            val method = rule.rejectMethod.takeIf { it in RouteRejectMethods } ?: "default"
            put("action", SingBoxRouteRuleActionReject)
            put("method", method)
            if (rule.rejectNoDrop && method != "drop") put("no_drop", true)
        } else {
            put("action", "route")
            put("outbound", rule.outbound.trim().ifBlank { APP_GLOBAL_SELECTOR })
        }
    }

private fun compileManagedRouteMatch(rule: SingBoxRouteRuleState): JsonObject =
    buildJsonObject {
        if (rule.type == SingBoxRouteRuleTypeLogical) {
            put("type", SingBoxRouteRuleTypeLogical)
            put(
                "mode",
                if (rule.logicalMode == SingBoxRouteRuleLogicalModeOr) {
                    SingBoxRouteRuleLogicalModeOr
                } else {
                    "and"
                },
            )
            putJsonArray("rules") {
                rule.logicalRules
                    .filter(SingBoxRouteRuleState::enabled)
                    .forEach { child -> add(compileManagedRouteMatch(child)) }
            }
            if (rule.invert) put("invert", true)
            return@buildJsonObject
        }
        putStringArray("inbound", rule.inbound)
        rule.clashMode.takeIf(String::isNotEmpty)?.let { mode ->
            put("clash_mode", mode)
        }
        if (rule.ipVersion == 4 || rule.ipVersion == 6) {
            put("ip_version", rule.ipVersion)
        }
        putStringArray("network", rule.network)
        putStringArray("protocol", rule.protocol)
        putStringArray("domain", rule.domain)
        putStringArray("domain_suffix", rule.domainSuffix)
        putStringArray("domain_keyword", rule.domainKeyword)
        putStringArray("domain_regex", rule.domainRegex)
        putStringArray("source_ip_cidr", rule.sourceIpCidr)
        putStringArray("ip_cidr", rule.ipCidr)
        putPortArray("source_port", rule.sourcePort)
        putStringArray("source_port_range", rule.sourcePortRange)
        putPortArray("port", rule.port)
        putStringArray("port_range", rule.portRange)
        putStringArray("package_name", rule.packageName)
        putStringArray("network_type", rule.networkType)
        putStringArray("wifi_ssid", rule.wifiSsid)
        putStringArray("wifi_bssid", rule.wifiBssid)
        putStringArray("rule_set", rule.ruleSet)
        if (rule.sourceIpIsPrivate) put("source_ip_is_private", true)
        if (rule.ipIsPrivate) put("ip_is_private", true)
        if (rule.invert) put("invert", true)
    }

private sealed interface StaticRouteMatch {
    data object Always : StaticRouteMatch
    data object Never : StaticRouteMatch
    data class Rule(val state: SingBoxRouteRuleState) : StaticRouteMatch
}

private fun SingBoxRouteRuleState.resolveClashMode(mode: Int): StaticRouteMatch {
    if (type != SingBoxRouteRuleTypeLogical) {
        if (clashMode.isBlank()) return StaticRouteMatch.Rule(this)
        val activeMode = when (mode) {
            SingBoxModeGlobal -> "Global"
            SingBoxModeDirect -> "Direct"
            else -> "Rule"
        }
        if (!clashMode.equals(activeMode, ignoreCase = true)) {
            return if (invert) StaticRouteMatch.Always else StaticRouteMatch.Never
        }
        val withoutMode = copy(clashMode = "")
        if (withoutMode.hasDefaultRouteMatchers()) {
            return StaticRouteMatch.Rule(withoutMode)
        }
        return if (invert) StaticRouteMatch.Never else StaticRouteMatch.Always
    }

    val children = logicalRules
        .filter(SingBoxRouteRuleState::enabled)
        .map { child -> child.resolveClashMode(mode) }
    val resolved = if (logicalMode == SingBoxRouteRuleLogicalModeOr) {
        when {
            children.any { child -> child == StaticRouteMatch.Always } ->
                StaticRouteMatch.Always
            else -> {
                val remaining = children.filterIsInstance<StaticRouteMatch.Rule>()
                if (remaining.isEmpty()) {
                    StaticRouteMatch.Never
                } else {
                    StaticRouteMatch.Rule(copy(logicalRules = remaining.map { child -> child.state }))
                }
            }
        }
    } else {
        when {
            children.any { child -> child == StaticRouteMatch.Never } ->
                StaticRouteMatch.Never
            else -> {
                val remaining = children.filterIsInstance<StaticRouteMatch.Rule>()
                if (remaining.isEmpty()) {
                    StaticRouteMatch.Always
                } else {
                    StaticRouteMatch.Rule(copy(logicalRules = remaining.map { child -> child.state }))
                }
            }
        }
    }
    if (!invert || resolved is StaticRouteMatch.Rule) return resolved
    return when (resolved) {
        StaticRouteMatch.Always -> StaticRouteMatch.Never
        StaticRouteMatch.Never -> StaticRouteMatch.Always
        is StaticRouteMatch.Rule -> resolved
    }
}

private fun SingBoxRouteRuleState.hasDefaultRouteMatchers(): Boolean =
    inbound.isNotEmpty() ||
        ipVersion == 4 ||
        ipVersion == 6 ||
        network.isNotEmpty() ||
        protocol.isNotEmpty() ||
        domain.isNotEmpty() ||
        domainSuffix.isNotEmpty() ||
        domainKeyword.isNotEmpty() ||
        domainRegex.isNotEmpty() ||
        sourceIpCidr.isNotEmpty() ||
        ipCidr.isNotEmpty() ||
        sourcePort.isNotEmpty() ||
        sourcePortRange.isNotEmpty() ||
        port.isNotEmpty() ||
        portRange.isNotEmpty() ||
        packageName.isNotEmpty() ||
        networkType.isNotEmpty() ||
        wifiSsid.isNotEmpty() ||
        wifiBssid.isNotEmpty() ||
        ruleSet.isNotEmpty() ||
        sourceIpIsPrivate ||
        ipIsPrivate

private fun kotlinx.serialization.json.JsonObjectBuilder.putStringArray(
    name: String,
    values: List<String>,
) {
    val normalized = values.map(String::trim).filter(String::isNotEmpty).distinct()
    if (normalized.isEmpty()) return
    putJsonArray(name) {
        normalized.forEach(::add)
    }
}

private fun kotlinx.serialization.json.JsonObjectBuilder.putPortArray(
    name: String,
    values: List<String>,
) {
    val ports = values
        .mapNotNull { value -> value.trim().toIntOrNull()?.takeIf { it in 0..65_535 } }
        .distinct()
    if (ports.isEmpty()) return
    putJsonArray(name) {
        ports.forEach(::add)
    }
}

private fun JsonArray?.orEmptyObjects(): List<JsonObject> =
    this?.mapNotNull { element -> element as? JsonObject }.orEmpty()

private fun JsonObject.hasAppTag(): Boolean =
    isManagedSingBoxTag((this["tag"] as? JsonPrimitive)?.contentOrNull.orEmpty())

private fun compileExperimental(appState: AppState): JsonObject? {
    if (!appState.storeFakeIp && !appState.storeDns) return null
    return buildJsonObject {
        putJsonObject("cache_file") {
            put("enabled", true)
            if (appState.storeFakeIp) put("store_fakeip", true)
            if (appState.storeDns) put("store_dns", true)
        }
    }
}

private val RouteRejectMethods = setOf("default", "drop", "reply")
