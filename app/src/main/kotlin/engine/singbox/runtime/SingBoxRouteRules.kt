// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package engine.singbox.runtime

import engine.proxy.RuntimeRouteRule
import engine.proxy.RuntimeRuleOutbound
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import utils.objectValue
import utils.stringListValue
import utils.stringOrNull

internal fun parseSingBoxRouteRules(config: JsonObject): List<RuntimeRouteRule> {
    val route = config.objectValue("route") ?: return emptyList()
    val inlineSizes = (route["rule_set"] as? JsonArray).orEmpty()
        .filterIsInstance<JsonObject>()
        .flatMap { ruleSet ->
            val rules = ruleSet["rules"] as? JsonArray ?: return@flatMap emptyList()
            ruleSet.stringListValue("tag").map { tag -> tag to rules.size }
        }
        .toMap()
    return buildList {
        (route["rules"] as? JsonArray).orEmpty().forEachIndexed { index, element ->
            val rule = element as? JsonObject ?: error("Invalid sing-box route rule at index $index")
            val action = rule.stringOrNull("action").orEmpty().ifBlank { "route" }
            val type = if (rule.stringOrNull("type") == "logical") "logical ($action)" else action
            add(
                RuntimeRouteRule(
                    index = index,
                    type = type,
                    payload = rule.entries
                        .filterNot { (key) -> key == "type" || key == "action" || key == "outbound" }
                        .joinToString(" · ") { (key, value) ->
                            val text = (value as? JsonPrimitive)?.contentOrNull ?: value.toString()
                            "$key=$text"
                        },
                    policy = rule.stringOrNull("outbound").orEmpty(),
                    size = rule.stringListValue("rule_set").singleOrNull()?.let(inlineSizes::get),
                ),
            )
        }
        route.stringOrNull("final")?.takeIf(String::isNotBlank)?.let { policy ->
            add(RuntimeRouteRule(index = size, type = "final", payload = "", policy = policy))
        }
    }
}

internal fun List<RuntimeRouteRule>.withSingBoxRuleOutbounds(
    groups: List<SingBoxProxyGroup>,
): List<RuntimeRouteRule> {
    val byName = groups.associateBy(SingBoxProxyGroup::name)
    val paths = mapTo(linkedSetOf(), RuntimeRouteRule::policy).associateWith { policy ->
        buildList {
            val visited = mutableSetOf<String>()
            var current = policy
            while (current.isNotBlank() && visited.add(current)) {
                val group = byName[current]
                add(RuntimeRuleOutbound(current, group?.icon.orEmpty()))
                current = group?.now.orEmpty()
            }
        }
    }
    return map { rule -> rule.copy(outbound = paths[rule.policy].orEmpty()) }
}
