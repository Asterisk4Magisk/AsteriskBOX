// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.settings.sheets

internal enum class DnsServerFieldKind {
    None,
    Local,
    Hosts,
    Network,
    Dhcp,
    Mdns,
    FakeIp,
    Endpoint,
    Resolved,
}

internal data class DnsServerFieldLayout(
    val kind: DnsServerFieldKind,
    val showPath: Boolean = false,
    val showTls: Boolean = false,
)

internal fun dnsServerFieldLayout(serverType: String): DnsServerFieldLayout =
    when (serverType) {
        "local" -> DnsServerFieldLayout(DnsServerFieldKind.Local)
        "hosts" -> DnsServerFieldLayout(DnsServerFieldKind.Hosts)
        "udp", "tcp" -> DnsServerFieldLayout(DnsServerFieldKind.Network)
        "tls", "quic" -> DnsServerFieldLayout(
            kind = DnsServerFieldKind.Network,
            showTls = true,
        )
        "https", "h3" -> DnsServerFieldLayout(
            kind = DnsServerFieldKind.Network,
            showPath = true,
            showTls = true,
        )
        "dhcp" -> DnsServerFieldLayout(DnsServerFieldKind.Dhcp)
        "mdns" -> DnsServerFieldLayout(DnsServerFieldKind.Mdns)
        "fakeip" -> DnsServerFieldLayout(DnsServerFieldKind.FakeIp)
        "tailscale", "openconnect", "openvpn" ->
            DnsServerFieldLayout(DnsServerFieldKind.Endpoint)
        "resolved" -> DnsServerFieldLayout(DnsServerFieldKind.Resolved)
        else -> DnsServerFieldLayout(DnsServerFieldKind.None)
    }
