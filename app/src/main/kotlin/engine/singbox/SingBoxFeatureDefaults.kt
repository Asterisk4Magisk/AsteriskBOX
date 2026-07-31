// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package engine.singbox

import app.ResourceFileKind
import app.SingBoxDnsRuleMatchState
import app.SingBoxDnsRuleState
import app.SingBoxDnsServerState
import app.SingBoxRouteRuleActionReject
import app.SingBoxRouteRuleState
import app.managedBundledRuleSetTag
import app.managedDnsServerTag
import engine.singbox.config.APP_DIRECT_OUTBOUND
import engine.singbox.config.APP_GLOBAL_SELECTOR

const val DefaultSingBoxDnsFakeIpRange = "198.18.0.1/16"
val DefaultSingBoxDnsFinal = managedDnsServerTag(2)
val DefaultSingBoxRouteDefaultDomainResolver = managedDnsServerTag(1)
const val DefaultSingBoxDnsCacheCapacity = ""
const val DefaultSingBoxDnsTimeout = "10s"
val DefaultSingBoxDnsServers = listOf(
    SingBoxDnsServerState(
        id = 1,
        remarks = "direct",
        type = "udp",
        server = "223.5.5.5",
    ),
    SingBoxDnsServerState(
        id = 2,
        remarks = "proxy",
        type = "tls",
        server = "1.1.1.1",
        detour = APP_GLOBAL_SELECTOR,
    ),
)
val DefaultSingBoxDnsRules = listOf(
    SingBoxDnsRuleState(
        id = 1,
        remarks = "ad_blocker",
        enabled = false,
        matches = listOf(
            SingBoxDnsRuleMatchState(
                field = "rule_set",
                values = listOf(
                    managedBundledRuleSetTag(ResourceFileKind.GeositeCategoryAdsAll),
                ),
            ),
        ),
        action = SingBoxRouteRuleActionReject,
        server = managedDnsServerTag(2),
    ),
    SingBoxDnsRuleState(
        id = 2,
        remarks = "google",
        matches = listOf(
            SingBoxDnsRuleMatchState(
                field = "rule_set",
                values = listOf(managedBundledRuleSetTag(ResourceFileKind.GeositeGoogle)),
            ),
        ),
        server = managedDnsServerTag(2),
    ),
    SingBoxDnsRuleState(
        id = 3,
        remarks = "china_site",
        matches = listOf(
            SingBoxDnsRuleMatchState(
                field = "rule_set",
                values = listOf(managedBundledRuleSetTag(ResourceFileKind.GeositeCn)),
            ),
        ),
        server = managedDnsServerTag(1),
    ),
)
val DefaultSingBoxRouteRules = listOf(
    SingBoxRouteRuleState(
        id = 1,
        remarks = "block_udp_443",
        network = listOf("udp"),
        port = listOf("443"),
        action = SingBoxRouteRuleActionReject,
    ),
    SingBoxRouteRuleState(
        id = 2,
        remarks = "google",
        ruleSet = listOf(managedBundledRuleSetTag(ResourceFileKind.GeositeGoogle)),
        outbound = APP_GLOBAL_SELECTOR,
    ),
    SingBoxRouteRuleState(
        id = 3,
        remarks = "china_ip_site",
        ruleSet = listOf(
            managedBundledRuleSetTag(ResourceFileKind.GeositeCn),
            managedBundledRuleSetTag(ResourceFileKind.GeoipCn),
        ),
        outbound = APP_DIRECT_OUTBOUND,
    ),
)

val SingBoxSnifferProtocols = listOf(
    "http",
    "tls",
    "quic",
    "stun",
    "dns",
    "bittorrent",
    "dtls",
    "ssh",
    "rdp",
    "ntp",
)
val DefaultSingBoxSnifferProtocols = listOf("http", "tls", "quic")
const val DefaultSingBoxSnifferTimeout = "300ms"
