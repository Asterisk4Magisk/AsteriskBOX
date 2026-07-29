// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.dns

import app.AppState
import app.withPrunedDnsServerReferences
import features.settings.DnsSettingsDraft
import features.settings.replaceDnsPreferredByTagReferences
import features.settings.replaceDnsServerTagReferences

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
        dnsServers = draft.dnsServers,
        nextDnsServerId = draft.nextDnsServerId,
        dnsRules = dnsRules
            .replaceDnsServerTagReferences(draft.dnsServerTagReplacements)
            .replaceDnsPreferredByTagReferences(draft.dnsPreferredByTagReplacements),
    ).withPrunedDnsServerReferences()
}
