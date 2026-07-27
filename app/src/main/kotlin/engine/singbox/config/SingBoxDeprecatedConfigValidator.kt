// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package engine.singbox.config

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

internal object SingBoxDeprecatedConfigValidator {
    fun validate(root: JsonObject) {
        rejectField(root, "experimental", "")
        rejectField(root, "geoip", "")
        rejectField(root, "geosite", "")

        validateDns(root["dns"] as? JsonObject)
        validateInbounds(root["inbounds"] as? JsonArray)
        validateOutbounds(root["outbounds"] as? JsonArray)
        validateRoute(root["route"] as? JsonObject)
        validateRuleSets(root["route"] as? JsonObject)
        validateEveryObject(root, "")
    }

    private fun validateDns(dns: JsonObject?) {
        if (dns == null) return
        rejectField(dns, "fakeip", "/dns")
        rejectField(dns, "independent_cache", "/dns")
        val servers = dns["servers"] as? JsonArray
        servers?.forEachIndexed { index, server ->
            val pointer = "/dns/servers/$index"
            val serverObject = server as? JsonObject
                ?: deprecated(pointer)
            val type = (serverObject["type"] as? JsonPrimitive)?.contentOrNull
            if (type.isNullOrBlank() || type == "legacy") {
                deprecated("$pointer/type")
            }
        }
        validateRules(dns["rules"] as? JsonArray, "/dns/rules", dnsRules = true)
    }

    private fun validateInbounds(inbounds: JsonArray?) {
        inbounds?.forEachIndexed { index, inbound ->
            val item = inbound as? JsonObject ?: return@forEachIndexed
            val pointer = "/inbounds/$index"
            DeprecatedInboundFields.forEach { field -> rejectField(item, field, pointer) }
            rejectHysteriaCompatFields(item, pointer)
        }
    }

    private fun validateOutbounds(outbounds: JsonArray?) {
        outbounds?.forEachIndexed { index, outbound ->
            val item = outbound as? JsonObject ?: return@forEachIndexed
            val pointer = "/outbounds/$index"
            when ((item["type"] as? JsonPrimitive)?.contentOrNull) {
                "block", "dns", "wireguard" -> deprecated("$pointer/type")
                "direct" -> {
                    rejectField(item, "override_address", pointer)
                    rejectField(item, "override_port", pointer)
                }
                "hysteria" -> rejectHysteriaCompatFields(item, pointer)
            }
            rejectField(item, "domain_strategy", pointer)
        }
    }

    private fun validateRoute(route: JsonObject?) {
        if (route == null) return
        rejectField(route, "geoip", "/route")
        rejectField(route, "geosite", "/route")
        validateRules(route["rules"] as? JsonArray, "/route/rules", dnsRules = false)
    }

    private fun validateRuleSets(route: JsonObject?) {
        val ruleSets = route?.get("rule_set") as? JsonArray ?: return
        var requiresDefaultHttpClient = false
        ruleSets.forEachIndexed { index, ruleSet ->
            val item = ruleSet as? JsonObject ?: return@forEachIndexed
            val pointer = "/route/rule_set/$index"
            rejectField(item, "download_detour", pointer)
            if (
                (item["type"] as? JsonPrimitive)?.contentOrNull == "remote" &&
                item["http_client"] == null
            ) {
                requiresDefaultHttpClient = true
            }
        }
        if (requiresDefaultHttpClient && route["default_http_client"] == null) {
            deprecated("/route/default_http_client")
        }
    }

    private fun validateRules(
        rules: JsonArray?,
        pointer: String,
        dnsRules: Boolean,
    ) {
        rules?.forEachIndexed { index, rule ->
            val item = rule as? JsonObject ?: return@forEachIndexed
            val itemPointer = "$pointer/$index"
            DeprecatedRuleFields.forEach { field -> rejectField(item, field, itemPointer) }
            if (dnsRules) {
                DeprecatedDnsRuleFields.forEach { field -> rejectField(item, field, itemPointer) }
                if (
                    item["match_response"] == null &&
                    (item["ip_cidr"] != null || item["ip_is_private"] != null)
                ) {
                    val field = if (item["ip_cidr"] != null) "ip_cidr" else "ip_is_private"
                    deprecated("$itemPointer/$field")
                }
            }
            validateRules(item["rules"] as? JsonArray, "$itemPointer/rules", dnsRules)
        }
    }

    private fun rejectHysteriaCompatFields(item: JsonObject, pointer: String) {
        DeprecatedHysteriaFields.forEach { field -> rejectField(item, field, pointer) }
    }

    private fun validateEveryObject(element: JsonElement, pointer: String) {
        when (element) {
            is JsonArray -> element.forEachIndexed { index, child ->
                validateEveryObject(child, "$pointer/$index")
            }
            is JsonObject -> {
                element.forEach { (name, child) ->
                    val childPointer = "$pointer/${jsonPointerToken(name)}"
                    when (name) {
                        "acme",
                        "pq_signature_schemes_enabled",
                        "dynamic_record_sizing_disabled",
                        "domain_strategy",
                        "proxy_protocol",
                        "proxy_protocol_accept_no_header",
                        -> deprecated(childPointer)
                    }
                    validateEveryObject(child, childPointer)
                }
            }
            else -> Unit
        }
    }

    private fun rejectField(
        objectValue: JsonObject?,
        field: String,
        parentPointer: String,
    ) {
        objectValue?.get(field) ?: return
        deprecated("$parentPointer/${jsonPointerToken(field)}")
    }

    private fun deprecated(pointer: String): Nothing {
        throw IllegalArgumentException(
            "Experimental or deprecated sing-box configuration is not allowed at $pointer",
        )
    }
}

private val DeprecatedInboundFields = setOf(
    "sniff",
    "sniff_override_destination",
    "sniff_timeout",
    "domain_strategy",
    "inet4_address",
    "inet6_address",
    "inet4_route_address",
    "inet6_route_address",
    "inet4_route_exclude_address",
    "inet6_route_exclude_address",
    "endpoint_independent_nat",
    "gso",
)

private val DeprecatedRuleFields = setOf(
    "geoip",
    "source_geoip",
    "geosite",
    "rule_set_ipcidr_match_source",
)

private val DeprecatedDnsRuleFields = setOf(
    "outbound",
    "rule_set_ip_cidr_accept_empty",
    "strategy",
)

private val DeprecatedHysteriaFields = setOf(
    "recv_window_conn",
    "recv_window_client",
    "recv_window",
    "max_conn_client",
    "disable_mtu_discovery",
)
