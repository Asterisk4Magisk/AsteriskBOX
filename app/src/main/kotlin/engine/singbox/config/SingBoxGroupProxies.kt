// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package engine.singbox.config

import app.AppState
import app.OutboundState
import app.SingBoxSelectorTypeSelector
import app.SingBoxSelectorTypeUrlTest
import app.managedOutboundGroupLandingMemberTag
import app.managedOutboundGroupLandingTag
import app.managedOutboundGroupSelectorTag
import engine.singbox.plugin.PluginOutbounds
import engine.singbox.plugin.supportsPluginGroupLanding
import engine.singbox.plugin.xrayOnlyVlessFeatures
import features.outbound.SupportedSingBoxProxyOutboundTypes
import features.selector.expandSelectorMemberReferences
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import utils.booleanOrNull
import utils.stringOrEmpty

/** Shared by configuration generation and the reference picker's dependency graph. */
internal fun OutboundState.inheritedGroupDetour(
    groupDetour: String,
    parsed: JsonObject,
): String? {
    if (type !in SupportedSingBoxProxyOutboundTypes) return null
    val target = groupDetour.trim().takeIf(String::isNotEmpty) ?: return null
    if (target == tag) return null
    val explicit = parsed["detour"]
    // Preserve explicit values (including malformed ones for normal core validation).
    if (explicit != null && (explicit as? JsonPrimitive)?.contentOrNull != "") return null
    return target
}

internal class GroupLandingTarget(
    val outbound: JsonObject,
    val members: List<GroupLandingTarget> = emptyList(),
) {
    val tag: String get() = outbound.stringOrEmpty("tag")
}

internal fun AppState.groupLandingTargets(
    outboundsByTag: Map<String, JsonObject>,
    editedGroupId: Int? = null,
): Map<String, GroupLandingTarget> {
    val selectorsByTag = selectors.filter { selector ->
        selector.type == SingBoxSelectorTypeSelector || selector.type == SingBoxSelectorTypeUrlTest
    }.associateBy { it.tag }
    val availableTags = outboundsByTag.keys + selectorsByTag.keys + endpoints.map { it.tag } +
        APP_DIRECT_OUTBOUND + APP_GLOBAL_SELECTOR
    val groupsWithLanding = outboundGroups.filter { it.enabled && (it.id == editedGroupId || it.landing.isNotBlank()) }
        .mapTo(mutableSetOf()) { managedOutboundGroupSelectorTag(it.id, it.name) }
    val resolved = mutableMapOf<String, GroupLandingTarget?>()
    val resolving = mutableSetOf<String>()

    fun resolve(tag: String): GroupLandingTarget? {
        if (tag in resolved) return resolved[tag]
        if (!resolving.add(tag)) return null
        val selector = selectorsByTag[tag]
        val outbound = if (selector == null) {
            outboundsByTag[tag]
        } else {
            val members = if (selector.outbounds.any { it.trim() in groupsWithLanding }) emptyList() else {
                expandSelectorMemberReferences(selector.outbounds, availableTags)
            }
            when {
                members.isEmpty() -> null
                selector.type == SingBoxSelectorTypeUrlTest -> buildUrlTestOutbound(selector, members, this)
                else -> buildSelectorOutbound(
                    tag = tag,
                    members = members,
                    default = selector.default.trim().takeIf(members::contains) ?: members.first(),
                    interruptExistConnections = selector.interruptExistConnections,
                )
            }
        }
        val type = outbound?.stringOrEmpty("type")
        val target = when {
            outbound == null -> null
            type == SingBoxSelectorTypeSelector || type == SingBoxSelectorTypeUrlTest -> {
                val references = outbound.landingMemberTags()
                val members = references.map(::resolve)
                if (members.isEmpty() || members.any { it == null }) null
                else GroupLandingTarget(outbound, members.filterNotNull())
            }
            outbound.supportsGroupLandingProxy(type.orEmpty(), pluginProtocols) -> GroupLandingTarget(outbound)
            else -> null
        }
        resolving.remove(tag)
        resolved[tag] = target
        return target
    }

    return (outboundsByTag.keys + selectorsByTag.keys).mapNotNull { tag -> resolve(tag)?.let { tag to it } }.toMap()
}

private fun JsonObject.supportsGroupLandingProxy(type: String, pluginProtocols: Collection<String>): Boolean {
    if (type !in SupportedSingBoxProxyOutboundTypes || type == "shadowtls") return false
    val plugin = PluginOutbounds.requirementFor(type, pluginProtocols)
    if (plugin != null) return supportsPluginGroupLanding(plugin)
    return type != PluginOutbounds.vless.outboundType || xrayOnlyVlessFeatures().isEmpty()
}

private fun JsonObject.landingMemberTags(): List<String> {
    // Provider and name filters operate on the original outbound tags.
    if (!(this["providers"] as? JsonArray).isNullOrEmpty() || booleanOrNull("use_all_providers") == true ||
        stringOrEmpty("exclude").isNotBlank() || stringOrEmpty("include").isNotBlank()
    ) return emptyList()
    return (this["outbounds"] as? JsonArray).orEmpty()
        .mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotEmpty) }
        .distinct()
}

internal fun AppState.appendGroupLanding(
    target: GroupLandingTarget,
    member: String,
    memberId: Int,
    outbounds: MutableList<JsonObject>,
): String {
    if (target.tag == member) return member
    val emitted = mutableMapOf<String, String>()
    val rootTag = managedOutboundGroupLandingTag(memberId)

    fun append(current: GroupLandingTarget): String {
        if (current.tag == member) return member
        return emitted.getOrPut(current.tag) {
            val tag = if (current === target) rootTag else managedOutboundGroupLandingMemberTag(memberId, current.tag)
            val options = current.outbound.toMutableMap()
            options["tag"] = JsonPrimitive(tag)
            if (current.members.isEmpty()) {
                options["detour"] = JsonPrimitive(member)
            } else {
                val members = current.members.associate { it.tag to append(it) }
                options["outbounds"] = JsonArray(members.values.map(::JsonPrimitive))
                if (current.outbound.stringOrEmpty("type") == SingBoxSelectorTypeSelector) {
                    val default = listOfNotNull(
                        selectorSelections[tag],
                        selectorSelections[current.tag],
                        current.outbound.stringOrEmpty("default"),
                    ).firstNotNullOfOrNull(members::get) ?: members.values.first()
                    options["default"] = JsonPrimitive(default)
                }
            }
            outbounds += JsonObject(options)
            tag
        }
    }

    return append(target)
}
