// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package app

import engine.network.DefaultFakeIpIpv4Pool
import engine.network.withoutIpv6FakeIpPool
import engine.singbox.DefaultSingBoxDnsServers

internal const val ManagedFakeIpDnsTag = "dns_fakeip"

/** Move the former server editor's settings to the dedicated FakeIP control. */
internal fun AppState.withIpv4FakeIpSettings(): AppState {
    val legacyServers = dnsServers.filter { it.type == "fakeip" }
    if (legacyServers.isEmpty()) {
        val pool = dnsFakeIpRange.withoutIpv6FakeIpPool()
        return if (pool == dnsFakeIpRange) this else copy(dnsFakeIpRange = pool)
    }
    val legacy = legacyServers.first()
    val pool = if (legacyServers.any { it.inet6Range.isNotBlank() || ':' in it.inet4Range }) {
        DefaultFakeIpIpv4Pool
    } else {
        // An empty old field used this default, independently of the new-install default.
        legacy.inet4Range.ifBlank { "198.18.0.1/16" }
    }
    var updated = this
    legacyServers.forEach { server ->
        updated = updated.withReplacedManagedTag(server.tag, ManagedFakeIpDnsTag)
    }
    val realServers = updated.dnsServers.filterNot { it.type == "fakeip" }.ifEmpty {
        // Avoid reusing an ID that an existing legacy reference may still contain.
        DefaultSingBoxDnsServers.mapIndexed { index, server ->
            server.copy(id = nextDnsServerId + index)
        }
    }
    val defaultServer = realServers.first().tag
    return updated.copy(
        enableFakeIp = true,
        dnsFakeIpRange = pool,
        dnsServers = realServers,
        nextDnsServerId = maxOf(nextDnsServerId, realServers.maxOf { it.id } + 1),
        dnsFinal = updated.dnsFinal.takeUnless { it == ManagedFakeIpDnsTag } ?: defaultServer,
        routeDefaultDomainResolver = updated.routeDefaultDomainResolver
            .takeUnless { it == ManagedFakeIpDnsTag } ?: defaultServer,
    )
}
