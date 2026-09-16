// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.subscription

import app.DefaultOutboundSubscriptionUserAgent
import io.ktor.http.Url
import java.net.URI

internal data class SubscriptionInstallConfig(
    val name: String,
    val url: String,
    val userAgent: String,
)

internal fun parseSubscriptionDeepLink(value: String): SubscriptionInstallConfig? {
    val raw = value.trim()
    if (raw.any(Char::isWhitespace)) return null
    val uri = runCatching { URI(raw) }.getOrNull() ?: return null
    if (uri.rawUserInfo != null || uri.port != -1 || uri.rawPath.orEmpty() !in setOf("", "/")) return null
    val scheme = uri.scheme?.lowercase() ?: return null
    val host = uri.host?.lowercase() ?: return null
    val userAgent = when (scheme) {
        "clash", "clashmeta" -> {
            if (host != "install-config") return null
            ClashMetaSubscriptionUserAgent
        }
        "v2rayng" -> {
            if (host !in setOf("install-config", "install-sub")) return null
            V2rayNgSubscriptionUserAgent
        }
        "sing-box" -> {
            if (host != "import-remote-profile") return null
            DefaultOutboundSubscriptionUserAgent
        }
        else -> return null
    }
    val link = runCatching { Url(raw) }.getOrNull() ?: return null
    if (link.parameters.getAll("url")?.size != 1 || (link.parameters.getAll("name")?.size ?: 0) > 1) return null
    val sourceUrl = link.parameters["url"]?.trim().orEmpty()
    if (sourceUrl.isBlank() || sourceUrl.any(Char::isWhitespace)) return null
    val source = runCatching { URI(sourceUrl) }.getOrNull() ?: return null
    if (source.scheme?.lowercase() !in setOf("http", "https") || source.host.isNullOrBlank()) return null
    // Ktor query parameters and fragments are already decoded. Do not decode them twice.
    val names = if (scheme == "sing-box") {
        listOf(link.fragment, link.parameters["name"], source.fragment, source.host)
    } else {
        listOf(link.parameters["name"], link.fragment, source.fragment, source.host)
    }
    val name = names.firstNotNullOf { it?.trim()?.takeIf(String::isNotBlank) }
    return SubscriptionInstallConfig(name = name, url = sourceUrl, userAgent = userAgent)
}
