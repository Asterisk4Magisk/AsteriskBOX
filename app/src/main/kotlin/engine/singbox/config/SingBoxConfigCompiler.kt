// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package engine.singbox.config

import android.content.Context
import app.AppState
import app.SingBoxSelectorState
import app.SingBoxSelectorTypeSelector
import app.SingBoxSelectorTypeUrlTest
import app.SingBoxRouteRuleActionReject
import app.SingBoxRouteRuleLogicalModeOr
import app.SingBoxRouteRuleState
import app.SingBoxRouteRuleTypeLogical
import app.managedOutboundGroupSelectorTag
import app.withUnavailableManagedRuleSetsDisabled
import app.managedRuleSetChoices
import app.withPrunedDnsServerReferences
import app.modes.RunModeBpf2Socks
import app.modes.RunModeTproxy
import app.modes.RunModeTun
import app.modes.RunModeTun2Socks
import app.modes.RunModeVpnService
import app.modes.SingBoxModeDirect
import app.modes.SingBoxModeGlobal
import app.modes.isRootRunMode
import engine.network.toPortOrNull
import engine.proxy.LocalProxyLoopbackAddress
import engine.proxy.toLocalProxyOptions
import engine.singbox.singBoxControlConfig
import engine.tproxy.DefaultTproxyPort
import engine.tun.SingBoxTunDevice
import engine.tun2socks.DefaultTun2SocksProxyPort
import engine.vpn.toTunOptions
import features.resources.runtime.singBoxRuleSetFiles
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

internal const val APP_TAG_PREFIX = "__asteriskbox_"
internal const val APP_GLOBAL_SELECTOR = "__asteriskbox_global__"
internal const val APP_LOCAL_INBOUND = "__asteriskbox_local__"
internal const val APP_TUN_INBOUND = "__asteriskbox_tun__"
internal const val APP_DIRECT_OUTBOUND = "__asteriskbox_direct__"
internal const val APP_ROOT_INBOUND = "__asteriskbox_root__"

internal data class SingBoxLocalRuleSet(
    val tag: String,
    val path: String,
)

internal object SingBoxConfigCompiler {
    fun compile(
        context: Context,
        appState: AppState,
        runMode: Int = appState.runMode,
        exposePorts: Boolean = true,
    ): String {
        val files = context.singBoxRuleSetFiles(appState.customResourceFiles)
        val choicesByFileName = appState.managedRuleSetChoices(files.map { file -> file.name })
            .associateBy { choice -> choice.fileName }
        val localRuleSets = files.mapNotNull { file ->
            choicesByFileName[file.name]?.let { choice ->
                SingBoxLocalRuleSet(
                    tag = choice.tag,
                    path = file.absolutePath,
                )
            }
        }.distinctBy(SingBoxLocalRuleSet::tag)
        val runtimeState = appState
            .withUnavailableManagedRuleSetsDisabled(
                localRuleSets.mapTo(mutableSetOf(), SingBoxLocalRuleSet::tag),
            )
            .withPrunedDnsServerReferences()
        return compileGenerated(
            appState = runtimeState,
            runMode = runMode,
            exposePorts = exposePorts,
            localRuleSets = localRuleSets,
        )
    }

    internal fun compileGenerated(
        appState: AppState,
        runMode: Int = appState.runMode,
        exposePorts: Boolean = true,
        localRuleSets: List<SingBoxLocalRuleSet> = emptyList(),
    ): String {
        val encoded = encodeSingBoxJson(
            generateRoot(
                appState = appState,
                runMode = runMode,
                exposePorts = exposePorts,
                localRuleSets = localRuleSets,
            ),
        )
        SingBoxConfigChecker.check(encoded)
        return encoded
    }

    internal fun generateRoot(
        appState: AppState,
        runMode: Int = appState.runMode,
        exposePorts: Boolean = true,
        localRuleSets: List<SingBoxLocalRuleSet> = emptyList(),
    ): JsonObject = generateRoot(
        sourceRoot = JsonObject(emptyMap()),
        appState = appState,
        runMode = runMode,
        exposePorts = exposePorts,
        localRuleSets = localRuleSets,
    )

    private fun generateRoot(
        sourceRoot: JsonObject,
        appState: AppState,
        runMode: Int = appState.runMode,
        exposePorts: Boolean = true,
        localRuleSets: List<SingBoxLocalRuleSet> = emptyList(),
    ): JsonObject {
        val managedSourceRoot = sourceRoot.withLocalRuleSets(localRuleSets)
        val dnsResult = SingBoxDnsCompiler.compile(appState)
        var runtime = managedSourceRoot
            .updated("log", compileLog(managedSourceRoot["log"] as? JsonObject, appState))
            .updated("inbounds", compileInbounds(managedSourceRoot, appState, runMode, exposePorts))
            .updated("endpoints", compileEndpoints(managedSourceRoot, appState))
            .updated("outbounds", compileOutbounds(managedSourceRoot, appState))
            .updated("services", compileServices(managedSourceRoot, appState, runMode, exposePorts))

        runtime = runtime.updated("dns", dnsResult?.dns)
        runtime = runtime.updated(
            "route",
            compileRoute(
                sourceRoute = managedSourceRoot["route"] as? JsonObject,
                appState = appState,
                dnsEnabled = runtime["dns"] is JsonObject,
                defaultDomainResolver = dnsResult?.defaultDomainResolver,
            ),
        )

        SingBoxDeprecatedConfigValidator.validate(runtime)
        return runtime
    }
}

private fun JsonObject.withLocalRuleSets(localRuleSets: List<SingBoxLocalRuleSet>): JsonObject {
    if (localRuleSets.isEmpty()) return this
    val sourceRoute = this["route"] as? JsonObject
    val managedTags = localRuleSets.mapTo(mutableSetOf(), SingBoxLocalRuleSet::tag)
    val retainedRuleSets = (sourceRoute?.get("rule_set") as? JsonArray)
        ?.filterNot { element ->
            val tag = ((element as? JsonObject)?.get("tag") as? JsonPrimitive)?.contentOrNull
            tag in managedTags
        }
        .orEmpty()
    val compiledRuleSets: List<JsonElement> = retainedRuleSets + localRuleSets.map { ruleSet ->
        buildJsonObject {
            put("type", "local")
            put("tag", ruleSet.tag)
            put("format", "binary")
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
            put("listen_port", appState.transparentProxyPort.toPortOrNull() ?: DefaultTproxyPort)
        }
        RunModeTun2Socks, RunModeBpf2Socks -> retained += buildJsonObject {
            put("type", "socks")
            put("tag", APP_ROOT_INBOUND)
            put("listen", LocalProxyLoopbackAddress)
            put("listen_port", appState.socks5ProxyPort.toPortOrNull() ?: DefaultTun2SocksProxyPort)
        }
        RunModeTun -> retained += compileTunInbound(appState, rootMode = true)
    }
    return JsonArray(retained)
}

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
            val groupTag = managedOutboundGroupSelectorTag(group.id)
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
    val globalSelectorMembers = (
        listOf(APP_DIRECT_OUTBOUND) +
            managedGroupTags +
            endpointCandidateTags
        ).distinct()
    val managedSelectors = appState.selectors
        .distinctBy { selector -> selector.id }
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
                val canEmit = selector.outbounds.any { member ->
                    member != selector.tag && member in availableMembers
                }
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
            val requestedMembers = selector.outbounds
                .map(String::trim)
                .filter(String::isNotEmpty)
                .toSet()
            val members = availableCustomMembers
                .filter { member -> member != selector.tag && member in requestedMembers }
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
            put("tag", "${APP_TAG_PREFIX}api__")
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
    dnsEnabled: Boolean,
    defaultDomainResolver: String?,
): JsonObject {
    val finalOutbound = appState.routeFinal.trim().ifBlank { APP_GLOBAL_SELECTOR }
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
            sourceRoute?.let(::putAll)
            put("rules", JsonArray(injectedRules + managedRules + existingRules + ruleModeFallback))
            put("final", JsonPrimitive(finalOutbound))
            if (defaultDomainResolver != null) {
                put("default_domain_resolver", JsonPrimitive(defaultDomainResolver))
            }
        },
    )
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
    ((this["tag"] as? JsonPrimitive)?.contentOrNull ?: "").startsWith(APP_TAG_PREFIX)

private val RouteRejectMethods = setOf("default", "drop", "reply")
