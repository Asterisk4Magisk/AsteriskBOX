// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package app

import androidx.compose.runtime.Stable
import features.resources.ResourceFileDirectCidrIpv4Name
import features.resources.ResourceFileDirectCidrIpv4Url
import features.resources.ResourceFileDirectCidrIpv6Name
import features.resources.ResourceFileDirectCidrIpv6Url
import features.resources.ResourceFileGeoipCnName
import features.resources.ResourceFileGeoipCnUrl
import features.resources.ResourceFileGeositeCategoryAdsAllName
import features.resources.ResourceFileGeositeCategoryAdsAllUrl
import features.resources.ResourceFileGeositeCnName
import features.resources.ResourceFileGeositeCnUrl
import features.resources.ResourceFileGeositeGoogleName
import features.resources.ResourceFileGeositeGoogleUrl
import features.resources.ResourceFileSingBoxCoreName
import features.resources.ResourceFileSourceCustom
import features.resources.ResourceFileSourceDefault
import features.resources.SingBoxCoreVersion
import kotlinx.serialization.Serializable

@Stable
data class SubscriptionInfo(
    val uploadBytes: Long = 0L,
    val downloadBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val expireAtSeconds: Long = 0L,
) {
    val usedBytes: Long
        get() = uploadBytes + downloadBytes

    val hasTraffic: Boolean
        get() = totalBytes > 0L
}

@Stable
enum class OutboundGroupUpdateStatus {
    NEVER,
    SUCCESS,
    PARTIAL,
    NOT_MODIFIED,
    FAILED,
}

@Stable
data class OutboundGroupState(
    val id: Int,
    val name: String,
    val url: String = "",
    val userAgent: String = DefaultOutboundSubscriptionUserAgent,
    val updateInterval: String = "",
    val hwid: String = "",
    val updateViaProxy: Boolean = false,
    val ageSecretKey: String = "",
    val enabled: Boolean = true,
    val strictImport: Boolean = false,
    val lastUpdateAttemptAtMillis: Long = 0L,
    val lastUpdatedAtMillis: Long = 0L,
    val lastUpdateStatus: OutboundGroupUpdateStatus = OutboundGroupUpdateStatus.NEVER,
    val lastUpdateImportedCount: Int = 0,
    val lastUpdateSkippedCount: Int = 0,
    val lastUpdateDuplicateCount: Int = 0,
    val consecutiveUpdateFailures: Int = 0,
    val lastUpdateErrorSummary: String = "",
    val subscriptionEtag: String = "",
    val subscriptionLastModified: String = "",
)

@Stable
data class OutboundState(
    val id: Int,
    val groupId: Int,
    val remarks: String,
    val type: String,
    val json: String,
    val pingMillis: Long? = null,
) {
    val tag: String
        get() = managedOutboundTag(id)
}

val SupportedSingBoxEndpointTypes = listOf(
    "wireguard",
    "tailscale",
    "openconnect",
    "openvpn-client",
)

@Stable
data class SingBoxEndpointState(
    val id: Int,
    val remarks: String,
    val type: String,
    val json: String,
) {
    val tag: String
        get() = managedEndpointTag(id)
}

const val SingBoxSelectorTypeSelector = "selector"
const val SingBoxSelectorTypeUrlTest = "urltest"
const val DefaultSingBoxUrlTestUrl = "https://www.gstatic.com/generate_204"
const val DefaultSingBoxUrlTestInterval = "3m"
const val DefaultSingBoxUrlTestTolerance = 50
const val DefaultSingBoxUrlTestIdleTimeout = "30m"

val SupportedSingBoxSelectorTypes = listOf(
    SingBoxSelectorTypeSelector,
    SingBoxSelectorTypeUrlTest,
)

@Stable
data class SingBoxSelectorState(
    val id: Int,
    val remarks: String,
    val outbounds: List<String> = emptyList(),
    val default: String = "",
    val type: String = SingBoxSelectorTypeSelector,
    val url: String = DefaultSingBoxUrlTestUrl,
    val interval: String = DefaultSingBoxUrlTestInterval,
    val tolerance: Int = DefaultSingBoxUrlTestTolerance,
    val idleTimeout: String = DefaultSingBoxUrlTestIdleTimeout,
    val interruptExistConnections: Boolean = true,
) {
    val tag: String
        get() = managedSelectorTag(id)
}

const val SingBoxRouteRuleActionRoute = "route"
const val SingBoxRouteRuleActionReject = "reject"
const val SingBoxRouteRuleTypeDefault = "default"
const val SingBoxRouteRuleTypeLogical = "logical"
const val SingBoxRouteRuleLogicalModeAnd = "and"
const val SingBoxRouteRuleLogicalModeOr = "or"
val SingBoxRouteRuleClashModes = listOf("Rule", "Global", "Direct")
val SingBoxRouteNetworkStrategies = listOf("default", "hybrid", "fallback")
val SingBoxRouteNetworkTypes = listOf("wifi", "cellular", "ethernet", "other")

@Stable
@Serializable
data class SingBoxRouteRuleState(
    val id: Int,
    val remarks: String = "",
    val enabled: Boolean = true,
    val type: String = SingBoxRouteRuleTypeDefault,
    val logicalMode: String = SingBoxRouteRuleLogicalModeAnd,
    val logicalRules: List<SingBoxRouteRuleState> = emptyList(),
    val inbound: List<String> = emptyList(),
    val clashMode: String = "",
    val ipVersion: Int = 0,
    val network: List<String> = emptyList(),
    val protocol: List<String> = emptyList(),
    val domain: List<String> = emptyList(),
    val domainSuffix: List<String> = emptyList(),
    val domainKeyword: List<String> = emptyList(),
    val domainRegex: List<String> = emptyList(),
    val sourceIpCidr: List<String> = emptyList(),
    val ipCidr: List<String> = emptyList(),
    val sourcePort: List<String> = emptyList(),
    val sourcePortRange: List<String> = emptyList(),
    val port: List<String> = emptyList(),
    val portRange: List<String> = emptyList(),
    val packageName: List<String> = emptyList(),
    val networkType: List<String> = emptyList(),
    val wifiSsid: List<String> = emptyList(),
    val wifiBssid: List<String> = emptyList(),
    val ruleSet: List<String> = emptyList(),
    val sourceIpIsPrivate: Boolean = false,
    val ipIsPrivate: Boolean = false,
    val invert: Boolean = false,
    val action: String = SingBoxRouteRuleActionRoute,
    val outbound: String = "",
    val rejectMethod: String = "default",
    val rejectNoDrop: Boolean = false,
)

const val DefaultOutboundSubscriptionUserAgent = "sing-box"

fun managedOutboundGroupSelectorTag(groupId: Int): String =
    "__asteriskbox_selector_group_${groupId}__"

enum class ResourceFileKind(
    val fileName: String,
) {
    SingBoxCore(ResourceFileSingBoxCoreName),
    GeositeCategoryAdsAll(ResourceFileGeositeCategoryAdsAllName),
    GeositeGoogle(ResourceFileGeositeGoogleName),
    GeositeCn(ResourceFileGeositeCnName),
    GeoipCn(ResourceFileGeoipCnName),
    DirectCidrIpv4(ResourceFileDirectCidrIpv4Name),
    DirectCidrIpv6(ResourceFileDirectCidrIpv6Name),
    ;

    val displayName: String
        get() = when (this) {
            SingBoxCore -> "sing-box $SingBoxCoreVersion"
            else -> fileName
        }
}

@Stable
data class ResourceFileStatus(
    val exists: Boolean = false,
    val sizeBytes: Long = 0,
    val updatedAtMillis: Long = 0,
)

@Stable
data class CustomResourceFileState(
    val id: Int,
    val name: String,
    val url: String,
)

@Stable
data class CustomResourceFileStatus(
    val file: CustomResourceFileState,
    val status: ResourceFileStatus = ResourceFileStatus(),
)

@Stable
@Serializable
data class SingBoxDnsServerState(
    val id: Int = 0,
    val remarks: String = "",
    val type: String = "local",
    val server: String = "",
    val serverPort: String = "",
    val path: String = "",
    val hostsPaths: List<String> = emptyList(),
    val predefinedHosts: List<String> = emptyList(),
    val interfaceName: String = "",
    val interfaceNames: List<String> = emptyList(),
    val inet4Range: String = "",
    val inet6Range: String = "",
    val endpoint: String = "",
    val service: String = "",
    val acceptDefaultResolvers: Boolean = false,
    val acceptSearchDomain: Boolean = false,
    val preferGo: Boolean = false,
    val neighborDomain: List<String> = emptyList(),
    val domainResolver: String = "",
    val detour: String = "",
    val tlsServerName: String = "",
    val tlsInsecure: Boolean = false,
) {
    val tag: String
        get() = managedDnsServerTag(id)
}

@Stable
@Serializable
data class SingBoxDnsRuleMatchState(
    val field: String = "domain_suffix",
    val values: List<String> = emptyList(),
    val encodeAsString: Boolean = false,
)

@Stable
@Serializable
data class SingBoxDnsRuleState(
    val id: Int = 0,
    val remarks: String = "",
    val enabled: Boolean = true,
    val matches: List<SingBoxDnsRuleMatchState> = emptyList(),
    val ipVersion: String = "",
    val network: String = "",
    val invert: Boolean = false,
    val action: String = "route",
    val server: String = "",
    val disableCache: Boolean = false,
    val rewriteTtl: String = "",
    val timeout: String = "",
    val clientSubnet: String = "",
    val rejectMethod: String = "default",
    val noDrop: Boolean = false,
    val rcode: String = "",
    val answer: List<String> = emptyList(),
    val ns: List<String> = emptyList(),
    val extra: List<String> = emptyList(),
) {
    val evaluationTag: String
        get() = managedDnsEvaluationTag(id)
}

val SingBoxDnsServerTypes = listOf(
    "local",
    "hosts",
    "udp",
    "tcp",
    "tls",
    "quic",
    "https",
    "h3",
    "dhcp",
    "mdns",
    "fakeip",
    "tailscale",
    "openconnect",
    "openvpn",
    "resolved",
)

val SingBoxDnsRuleMatchers = listOf(
    "domain",
    "domain_suffix",
    "domain_keyword",
    "domain_regex",
    "rule_set",
    "query_type",
    "inbound",
    "auth_user",
    "protocol",
    "source_ip_cidr",
    "source_port",
    "source_port_range",
    "port",
    "port_range",
    "process_name",
    "process_path",
    "process_path_regex",
    "package_name",
    "package_name_regex",
    "clash_mode",
    "network_type",
    "interface_address",
    "network_interface_address",
    "default_interface_address",
    "source_mac_address",
    "source_hostname",
    "preferred_by",
    "wifi_ssid",
    "wifi_bssid",
    "match_response",
    "response_rcode",
    "response_answer",
    "response_ns",
    "response_extra",
)

val SingBoxDnsRuleActions = listOf(
    "route",
    "evaluate",
    "respond",
    "route-options",
    "reject",
    "predefined",
)

@Stable
data class ResourceFilesStatus(
    val resourceFiles: Map<ResourceFileKind, ResourceFileStatus> = emptyMap(),
    val customResourceFiles: List<CustomResourceFileStatus> = emptyList(),
)

data class ResourceFileUpdateSource(
    val id: Int,
    val geositeCategoryAdsAllUrl: String,
    val geositeGoogleUrl: String,
    val geositeCnUrl: String,
    val geoipCnUrl: String,
    val directCidrIpv4Url: String,
    val directCidrIpv6Url: String,
)

val ResourceFileUpdateSources = listOf(
    ResourceFileUpdateSource(
        id = ResourceFileSourceDefault,
        geositeCategoryAdsAllUrl = ResourceFileGeositeCategoryAdsAllUrl,
        geositeGoogleUrl = ResourceFileGeositeGoogleUrl,
        geositeCnUrl = ResourceFileGeositeCnUrl,
        geoipCnUrl = ResourceFileGeoipCnUrl,
        directCidrIpv4Url = ResourceFileDirectCidrIpv4Url,
        directCidrIpv6Url = ResourceFileDirectCidrIpv6Url,
    ),
)

fun resourceFileUpdateSourceAt(index: Int): ResourceFileUpdateSource =
    ResourceFileUpdateSources.getOrElse(index) { ResourceFileUpdateSources.first() }

fun AppState.nextAvailableOutboundGroupId(): Int {
    val usedIds = outboundGroups.mapTo(mutableSetOf()) { group -> group.id }
    var candidate = nextOutboundGroupId.coerceAtLeast(1)
    while (candidate in usedIds) candidate += 1
    return candidate
}

fun AppState.nextAvailableCustomResourceFileId(): Int {
    val usedIds = customResourceFiles.mapTo(mutableSetOf()) { file -> file.id }
    var candidate = nextCustomResourceFileId.coerceAtLeast(1)
    while (candidate in usedIds) candidate += 1
    return candidate
}

fun AppState.nextAvailableRouteRuleId(): Int {
    val usedIds = routeRules.mapTo(mutableSetOf()) { rule -> rule.id }
    var candidate = nextRouteRuleId.coerceAtLeast(1)
    while (candidate in usedIds) candidate += 1
    return candidate
}

fun AppState.nextAvailableDnsRuleId(): Int {
    val usedIds = dnsRules.mapTo(mutableSetOf()) { rule -> rule.id }
    var candidate = nextDnsRuleId.coerceAtLeast(1)
    while (candidate in usedIds) candidate += 1
    return candidate
}

fun AppState.nextAvailableDnsServerId(): Int {
    val usedIds = dnsServers.mapTo(mutableSetOf()) { server -> server.id }
    var candidate = nextDnsServerId.coerceAtLeast(1)
    while (candidate in usedIds) candidate += 1
    return candidate
}

fun AppState.nextAvailableSelectorId(): Int {
    val usedIds = selectors.mapTo(mutableSetOf()) { selector -> selector.id }
    var candidate = nextSelectorId.coerceAtLeast(1)
    while (candidate in usedIds) candidate += 1
    return candidate
}

fun AppState.withSelectorSelection(
    selectorTag: String,
    outboundTag: String,
): AppState {
    val normalizedSelectorTag = selectorTag.trim()
    val normalizedOutboundTag = outboundTag.trim()
    if (normalizedSelectorTag.isEmpty() || normalizedOutboundTag.isEmpty()) return this
    if (selectorSelections[normalizedSelectorTag] == normalizedOutboundTag) return this
    return copy(
        selectorSelections = selectorSelections + (normalizedSelectorTag to normalizedOutboundTag),
    )
}

fun AppState.withRemovedManagedOutboundTags(
    removedTags: Set<String>,
): AppState {
    if (removedTags.isEmpty()) return this
    val enabledGroupIds = outboundGroups
        .filter { group -> group.enabled }
        .mapTo(mutableSetOf()) { group -> group.id }
    val availableTargetTags = outbounds
        .filter { outbound -> outbound.groupId in enabledGroupIds }
        .mapTo(mutableSetOf()) { outbound -> outbound.tag }
        .apply { addAll(endpoints.map(SingBoxEndpointState::tag)) }
    val unavailableTags = removedTags - availableTargetTags
    if (unavailableTags.isEmpty()) return this
    val (updatedSelectors, transitivelyUnavailableTags) =
        selectors.withPrunedUnavailableMembers(unavailableTags)
    val dependentDnsServerTags = dnsServers
        .filter { server ->
            server.type in EndpointBackedDnsServerTypes &&
                server.endpoint in transitivelyUnavailableTags
        }
        .mapTo(mutableSetOf(), SingBoxDnsServerState::tag)
    return copy(
        outbounds = transitivelyUnavailableTags.fold(outbounds) { updatedOutbounds, tag ->
            updatedOutbounds.replaceManagedReference(
                field = "detour",
                previousTag = tag,
                replacementTag = "",
            )
        },
        endpoints = transitivelyUnavailableTags.fold(endpoints) { updatedEndpoints, tag ->
            updatedEndpoints.replaceEndpointManagedReference(
                field = "detour",
                previousTag = tag,
                replacementTag = "",
            )
        },
        selectors = updatedSelectors,
        selectorSelections = selectorSelections
            .filterKeys { selectorTag -> selectorTag !in transitivelyUnavailableTags }
            .filterValues { outboundTag -> outboundTag !in transitivelyUnavailableTags },
        routeFinal = routeFinal
            .takeUnless(transitivelyUnavailableTags::contains)
            .orEmpty(),
        routeRules = routeRules.map { rule ->
            if (rule.outbound in transitivelyUnavailableTags) {
                rule.copy(outbound = "")
            } else {
                rule
            }
        },
        dnsServers = dnsServers.map { server ->
            server.copy(
                detour = server.detour
                    .takeUnless(transitivelyUnavailableTags::contains)
                    .orEmpty(),
            )
        },
    ).withRemovedManagedDnsServers(dependentDnsServerTags)
}

private fun List<SingBoxSelectorState>.withPrunedUnavailableMembers(
    initiallyUnavailableTags: Set<String>,
): Pair<List<SingBoxSelectorState>, Set<String>> {
    val unavailableTags = initiallyUnavailableTags.toMutableSet()
    var updatedSelectors = this
    while (true) {
        updatedSelectors = updatedSelectors.map { selector ->
            val members = selector.outbounds.filterNot(unavailableTags::contains)
            selector.copy(
                outbounds = members,
                default = if (selector.type == SingBoxSelectorTypeUrlTest) {
                    ""
                } else {
                    selector.default.takeIf(members::contains)
                        ?: members.firstOrNull().orEmpty()
                },
            )
        }
        val newlyUnavailableTags = updatedSelectors
            .filter { selector -> selector.outbounds.isEmpty() }
            .mapTo(mutableSetOf(), SingBoxSelectorState::tag)
            .minus(unavailableTags)
        if (newlyUnavailableTags.isEmpty()) {
            return updatedSelectors to unavailableTags
        }
        unavailableTags += newlyUnavailableTags
    }
}

fun AppState.withRemovedManagedOutbound(outboundId: Int): AppState {
    val removed = outbounds.firstOrNull { outbound -> outbound.id == outboundId } ?: return this
    val remaining = outbounds.filterNot { outbound -> outbound.id == outboundId }
    val removedTags = mutableSetOf(removed.tag)
    if (remaining.none { outbound -> outbound.groupId == removed.groupId }) {
        removedTags += managedOutboundGroupSelectorTag(removed.groupId)
    }
    return copy(outbounds = remaining).withRemovedManagedOutboundTags(removedTags)
}

fun AppState.resourceFileUpdateSource(): ResourceFileUpdateSource {
    if (resourceFileSource != ResourceFileSourceCustom) {
        return resourceFileUpdateSourceAt(resourceFileSource)
    }
    val fallback = ResourceFileUpdateSources.first()
    return ResourceFileUpdateSource(
        id = ResourceFileSourceCustom,
        geositeCategoryAdsAllUrl = customResourceFileGeositeCategoryAdsAllUrl.trim().ifBlank {
            fallback.geositeCategoryAdsAllUrl
        },
        geositeGoogleUrl = customResourceFileGeositeGoogleUrl.trim().ifBlank {
            fallback.geositeGoogleUrl
        },
        geositeCnUrl = customResourceFileGeositeCnUrl.trim().ifBlank {
            fallback.geositeCnUrl
        },
        geoipCnUrl = customResourceFileGeoipCnUrl.trim().ifBlank {
            fallback.geoipCnUrl
        },
        directCidrIpv4Url = customResourceFileDirectCidrIpv4Url.trim().ifBlank {
            fallback.directCidrIpv4Url
        },
        directCidrIpv6Url = customResourceFileDirectCidrIpv6Url.trim().ifBlank {
            fallback.directCidrIpv6Url
        },
    )
}

fun ResourceFileUpdateSource.urlFor(kind: ResourceFileKind): String? =
    when (kind) {
        ResourceFileKind.SingBoxCore -> null
        ResourceFileKind.GeositeCategoryAdsAll -> geositeCategoryAdsAllUrl
        ResourceFileKind.GeositeGoogle -> geositeGoogleUrl
        ResourceFileKind.GeositeCn -> geositeCnUrl
        ResourceFileKind.GeoipCn -> geoipCnUrl
        ResourceFileKind.DirectCidrIpv4 -> directCidrIpv4Url
        ResourceFileKind.DirectCidrIpv6 -> directCidrIpv6Url
    }

fun ResourceFilesStatus.statusOf(kind: ResourceFileKind): ResourceFileStatus =
    resourceFiles[kind] ?: ResourceFileStatus()

fun sanitizeCustomResourceFileName(value: String, fallback: String): String {
    val candidate = value
        .trim()
        .replace('\\', '/')
        .substringAfterLast('/')
        .map { char -> if (char.isResourceFileNameChar()) char else '_' }
        .joinToString("")
        .trim()
    return candidate
        .takeUnless { it.isBlank() || it == "." || it == ".." }
        ?: fallback
}

fun customResourceFileNameOrNull(value: String): String? {
    if (value.isBlank() || value != value.trim()) return null
    if (value.any { char -> char.isWhitespace() || char == ':' }) return null
    return sanitizeCustomResourceFileName(value, fallback = "")
        .takeIf { sanitized -> sanitized == value }
}

private fun Char.isResourceFileNameChar(): Boolean =
    code >= 32 && this != '/' && this != '\\'

private val EndpointBackedDnsServerTypes = setOf("tailscale", "openconnect", "openvpn")
