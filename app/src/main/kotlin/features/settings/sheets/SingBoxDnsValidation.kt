// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.settings.sheets

import engine.network.isIpAddress
import engine.singbox.isSingBoxUnsigned16

internal fun isDnsNetworkServerAddress(value: String): Boolean {
    val address = value.trim()
    if (address.isEmpty() || address.any(Char::isWhitespace)) return false
    if (address.contains("://") || address.any { it == '/' || it == '\\' || it == '?' || it == '#' || it == '@' }) {
        return false
    }
    if (isDnsServerIpAddress(address)) return true
    return ':' !in address
}

internal fun dnsServerDomainResolverRequired(
    address: String,
): Boolean =
    !isDnsServerIpAddress(address.trim())

internal fun isDnsServerPort(value: String): Boolean =
    isSingBoxUnsigned16(value)

private fun isDnsServerIpAddress(value: String): Boolean {
    if (isIpAddress(value)) return true
    return value.length > 2 &&
        value.first() == '[' &&
        value.last() == ']' &&
        isIpAddress(value.substring(1, value.lastIndex))
}
