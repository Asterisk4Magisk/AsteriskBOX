// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.selector

import app.AppState
import app.DefaultSingBoxUrlTestIdleTimeout
import app.DefaultSingBoxUrlTestInterval
import app.DefaultSingBoxUrlTestUrl
import app.ManagedOutboundChoice
import app.SingBoxSelectorTypeSelector
import app.SingBoxSelectorTypeUrlTest
import app.SingBoxSelectorState
import app.SupportedSingBoxSelectorTypes
import app.selectableManagedOutbounds
import engine.singbox.SingBoxUnsigned16Max
import engine.singbox.isNonNegativeSingBoxDuration
import engine.singbox.isSingBoxDurationNotGreaterThan
import java.net.URI

internal fun selectorTargetChoices(
    state: AppState,
    selectorId: Int = 0,
): List<ManagedOutboundChoice> = selectableManagedOutbounds(
    state = state,
    excludedTag = state.selectors.firstOrNull { selector -> selector.id == selectorId }?.tag.orEmpty(),
    excludedSelectorId = selectorId,
    includeGlobalSelector = false,
)

internal fun selectorTargetTags(
    state: AppState,
    selectorId: Int = 0,
): List<String> = selectorTargetChoices(
    state = state,
    selectorId = selectorId,
).map { choice -> choice.tag }

internal fun selectorCardMemberCount(memberTags: Iterable<String>): Int = memberTags.count()

internal fun selectorDefaultMemberIndex(
    members: List<String>,
    default: String,
): Int = members.indexOf(default).coerceAtLeast(0)

internal fun validateSelectorDraft(
    state: AppState,
    draft: SingBoxSelectorState,
): SingBoxSelectorState {
    val type = draft.type.trim().lowercase()
    require(type in SupportedSingBoxSelectorTypes) { "Unsupported selector type" }
    val remarks = draft.remarks.trim()
    require(remarks.isNotEmpty()) { "Selector remarks are required" }

    val available = selectorTargetTags(
        state = state,
        selectorId = draft.id,
    )
    val requestedMembers = draft.outbounds
        .map(String::trim)
        .filter(String::isNotEmpty)
        .toSet()
    val members = available.filter(requestedMembers::contains)
    require(members.isNotEmpty()) { "Selector requires at least one outbound" }
    require(members.size == requestedMembers.size) { "Selector contains an unavailable outbound" }
    return when (type) {
        SingBoxSelectorTypeSelector -> {
            val default = draft.default.trim().ifBlank { members.first() }
            require(default in members) { "Selector default must be a member" }
            draft.copy(
                type = type,
                remarks = remarks,
                outbounds = members,
                default = default,
            )
        }
        SingBoxSelectorTypeUrlTest -> {
            val url = draft.url.trim().ifBlank { DefaultSingBoxUrlTestUrl }
            val interval = draft.interval.trim().ifBlank { DefaultSingBoxUrlTestInterval }
            val idleTimeout = draft.idleTimeout.trim()
                .ifBlank { DefaultSingBoxUrlTestIdleTimeout }
            require(isValidUrlTestUrl(url)) { "URLTest URL is invalid" }
            require(isValidSingBoxDuration(interval)) { "URLTest interval is invalid" }
            require(draft.tolerance in 0..SingBoxUnsigned16Max) {
                "URLTest tolerance is invalid"
            }
            require(isValidSingBoxDuration(idleTimeout)) { "URLTest idle timeout is invalid" }
            require(isSingBoxDurationNotGreaterThan(interval, idleTimeout)) {
                "URLTest interval must not exceed idle timeout"
            }
            draft.copy(
                type = type,
                remarks = remarks,
                outbounds = members,
                default = "",
                url = url,
                interval = interval,
                idleTimeout = idleTimeout,
            )
        }
        else -> error("Unreachable selector type")
    }
}

internal fun isValidUrlTestUrl(value: String): Boolean {
    val uri = runCatching { URI(value.trim()) }.getOrNull() ?: return false
    if (uri.scheme !in setOf("http", "https")) return false
    if (!uri.host.isNullOrBlank()) return true
    val authority = uri.rawAuthority?.substringAfterLast('@') ?: return false
    if (authority.any(Char::isWhitespace)) return false
    val host = when {
        authority.startsWith('[') -> authority.substringAfter('[').substringBefore(']')
        authority.count { it == ':' } <= 1 -> authority.substringBefore(':')
        else -> ""
    }
    return host.isNotBlank()
}

internal fun isValidSingBoxDuration(value: String): Boolean =
    isNonNegativeSingBoxDuration(value)
