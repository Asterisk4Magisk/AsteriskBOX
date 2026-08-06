// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package app

import java.text.Normalizer

internal const val LegacyManagedSingBoxTagPrefix = "__asteriskbox_"

internal const val ManagedDirectOutboundTag = "outbound_direct"
internal const val ManagedGlobalSelectorTag = "selector_global"
internal const val ManagedLocalInboundTag = "inbound_local"
internal const val ManagedTunInboundTag = "inbound_tun"
internal const val ManagedRootInboundTag = "inbound_root"
internal const val ManagedApiServiceTag = "service_api"

internal enum class ManagedTagKind {
    OUTBOUND,
    ENDPOINT,
    SELECTOR,
    OUTBOUND_GROUP,
    DNS_SERVER,
    DNS_EVALUATION,
    CUSTOM_RULE_SET,
    BUNDLED_RULE_SET,
    DIRECT_OUTBOUND,
    GLOBAL_SELECTOR,
    LOCAL_INBOUND,
    TUN_INBOUND,
    ROOT_INBOUND,
    API_SERVICE,
}

internal data class ManagedTagIdentity(
    val kind: ManagedTagKind,
    val id: Int? = null,
    val key: String = "",
)

internal fun sanitizeManagedTagPart(value: String): String {
    val normalized = Normalizer.normalize(value.trim(), Normalizer.Form.NFKC)
    val sanitized = buildString(normalized.length) {
        var separatorPending = false
        var index = 0
        while (index < normalized.length) {
            val codePoint = Character.codePointAt(normalized, index)
            if (Character.isLetterOrDigit(codePoint)) {
                if (separatorPending && isNotEmpty()) append('_')
                appendCodePoint(codePoint)
                separatorPending = false
            } else if (isNotEmpty()) {
                separatorPending = true
            }
            index += Character.charCount(codePoint)
        }
    }
    return sanitized.ifEmpty { "unnamed" }
}

internal fun managedOutboundTag(id: Int, remarks: String): String =
    "outbound_${id}_${sanitizeManagedTagPart(remarks)}"

internal fun managedEndpointTag(id: Int, remarks: String): String =
    "endpoint_${id}_${sanitizeManagedTagPart(remarks)}"

internal fun managedSelectorTag(id: Int, remarks: String): String =
    "selector_${id}_${sanitizeManagedTagPart(remarks)}"

internal fun managedOutboundGroupSelectorTag(id: Int, name: String): String =
    "outbound_group_${id}_${sanitizeManagedTagPart(name)}"

internal fun managedDnsServerTag(id: Int, remarks: String): String =
    "dns_server_${id}_${sanitizeManagedTagPart(remarks)}"

internal fun managedDnsEvaluationTag(id: Int, remarks: String): String =
    "dns_evaluation_${id}_${sanitizeManagedTagPart(remarks)}"

internal fun managedCustomRuleSetTag(id: Int, fileName: String): String =
    "rule_set_${id}_${sanitizeManagedTagPart(fileName)}"

internal fun managedBundledRuleSetTag(kind: ResourceFileKind): String =
    "rule_set_${sanitizeManagedTagPart(kind.fileName)}"

internal fun managedTagIdentityOrNull(tag: String): ManagedTagIdentity? {
    val normalized = tag.trim()
    FixedManagedTagIdentities[normalized]?.let { return it }
    DynamicManagedTagPatterns.forEach { pattern ->
        pattern.regex.matchEntire(normalized)?.let { match ->
            val id = match.groupValues[1].toIntOrNull() ?: return@let
            return ManagedTagIdentity(kind = pattern.kind, id = id)
        }
    }
    ResourceFileKind.entries
        .filter { kind -> kind.fileName.endsWith(".srs", ignoreCase = true) }
        .forEach { kind ->
            if (
                normalized == managedBundledRuleSetTag(kind) ||
                normalized == "${LegacyManagedSingBoxTagPrefix}rule_set_${kind.name.lowercase()}__"
            ) {
                return ManagedTagIdentity(
                    kind = ManagedTagKind.BUNDLED_RULE_SET,
                    key = kind.name,
                )
            }
        }
    return null
}

internal fun isManagedSingBoxTag(tag: String): Boolean =
    managedTagIdentityOrNull(tag) != null

private data class DynamicManagedTagPattern(
    val kind: ManagedTagKind,
    val regex: Regex,
)

private val FixedManagedTagIdentities = mapOf(
    ManagedDirectOutboundTag to ManagedTagIdentity(ManagedTagKind.DIRECT_OUTBOUND),
    ManagedGlobalSelectorTag to ManagedTagIdentity(ManagedTagKind.GLOBAL_SELECTOR),
    ManagedLocalInboundTag to ManagedTagIdentity(ManagedTagKind.LOCAL_INBOUND),
    ManagedTunInboundTag to ManagedTagIdentity(ManagedTagKind.TUN_INBOUND),
    ManagedRootInboundTag to ManagedTagIdentity(ManagedTagKind.ROOT_INBOUND),
    ManagedApiServiceTag to ManagedTagIdentity(ManagedTagKind.API_SERVICE),
    "${LegacyManagedSingBoxTagPrefix}direct__" to ManagedTagIdentity(ManagedTagKind.DIRECT_OUTBOUND),
    "${LegacyManagedSingBoxTagPrefix}global__" to ManagedTagIdentity(ManagedTagKind.GLOBAL_SELECTOR),
    "${LegacyManagedSingBoxTagPrefix}local__" to ManagedTagIdentity(ManagedTagKind.LOCAL_INBOUND),
    "${LegacyManagedSingBoxTagPrefix}tun__" to ManagedTagIdentity(ManagedTagKind.TUN_INBOUND),
    "${LegacyManagedSingBoxTagPrefix}root__" to ManagedTagIdentity(ManagedTagKind.ROOT_INBOUND),
    "${LegacyManagedSingBoxTagPrefix}api__" to ManagedTagIdentity(ManagedTagKind.API_SERVICE),
)

private val DynamicManagedTagPatterns = listOf(
    DynamicManagedTagPattern(ManagedTagKind.OUTBOUND_GROUP, Regex("^outbound_group_(\\d+)_.+$")),
    DynamicManagedTagPattern(ManagedTagKind.DNS_EVALUATION, Regex("^dns_evaluation_(\\d+)_.+$")),
    DynamicManagedTagPattern(ManagedTagKind.DNS_SERVER, Regex("^dns_server_(\\d+)_.+$")),
    DynamicManagedTagPattern(ManagedTagKind.CUSTOM_RULE_SET, Regex("^rule_set_(\\d+)_.+$")),
    DynamicManagedTagPattern(ManagedTagKind.OUTBOUND, Regex("^outbound_(\\d+)_.+$")),
    DynamicManagedTagPattern(ManagedTagKind.ENDPOINT, Regex("^endpoint_(\\d+)_.+$")),
    DynamicManagedTagPattern(ManagedTagKind.SELECTOR, Regex("^selector_(\\d+)_.+$")),
    DynamicManagedTagPattern(
        ManagedTagKind.OUTBOUND_GROUP,
        Regex("^${LegacyManagedSingBoxTagPrefix}selector_group_(\\d+)__$"),
    ),
    DynamicManagedTagPattern(
        ManagedTagKind.DNS_EVALUATION,
        Regex("^${LegacyManagedSingBoxTagPrefix}dns_evaluation_(\\d+)__$"),
    ),
    DynamicManagedTagPattern(
        ManagedTagKind.DNS_SERVER,
        Regex("^${LegacyManagedSingBoxTagPrefix}dns_server_(\\d+)__$"),
    ),
    DynamicManagedTagPattern(
        ManagedTagKind.CUSTOM_RULE_SET,
        Regex("^${LegacyManagedSingBoxTagPrefix}rule_set_(\\d+)__$"),
    ),
    DynamicManagedTagPattern(
        ManagedTagKind.OUTBOUND,
        Regex("^${LegacyManagedSingBoxTagPrefix}outbound_(\\d+)__$"),
    ),
    DynamicManagedTagPattern(
        ManagedTagKind.ENDPOINT,
        Regex("^${LegacyManagedSingBoxTagPrefix}endpoint_(\\d+)__$"),
    ),
    DynamicManagedTagPattern(
        ManagedTagKind.SELECTOR,
        Regex("^${LegacyManagedSingBoxTagPrefix}selector_(\\d+)__$"),
    ),
)
