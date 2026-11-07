// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.dns

import app.AppState
import app.withCompatibleProxyAppListMode
import app.withPrunedDnsServerReferences
import app.withCanonicalManagedTagReferences
import features.settings.DnsSettingsDraft

internal fun AppState.withDnsSettings(draft: DnsSettingsDraft): AppState {
    return copy(
        enableLocalDns = draft.enableLocalDns,
        dnsFinal = draft.dnsFinal,
        routeDefaultDomainResolver = draft.routeDefaultDomainResolver,
        dnsCacheCapacity = draft.dnsCacheCapacity,
        dnsOptimisticCache = draft.dnsOptimisticCache,
        dnsDisableCache = draft.dnsDisableCache,
        dnsDisableExpire = draft.dnsDisableExpire,
        dnsTimeout = draft.dnsTimeout,
        enableFakeIp = draft.enableFakeIp,
        dnsFakeIpRange = draft.dnsFakeIpRange,
        storeFakeIp = draft.storeFakeIp,
        storeDns = draft.storeDns,
        dnsServers = draft.dnsServers,
        nextDnsServerId = draft.nextDnsServerId,
        dnsRules = dnsRules
            .replaceDnsServerTagReferences(draft.dnsServerTagReplacements)
            .replaceDnsPreferredByTagReferences(draft.dnsPreferredByTagReplacements),
    ).withCanonicalManagedTagReferences().withPrunedDnsServerReferences().withCompatibleProxyAppListMode()
}
