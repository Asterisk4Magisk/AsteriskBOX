// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.outbound

import androidx.compose.foundation.lazy.LazyListScope

internal fun hysteriaOutboundFields() = listOf(
    outboundField("server_ports", "Server ports", OutboundFieldKind.TEXT_LIST),
    outboundField("hop_interval", "Port hopping interval"),
    outboundField("up_mbps", "Upload bandwidth (Mbps)", OutboundFieldKind.INTEGER),
    outboundField("down_mbps", "Download bandwidth (Mbps)", OutboundFieldKind.INTEGER),
    outboundField("obfs", "Obfuscation password", OutboundFieldKind.SECRET),
    outboundField("auth", "Authentication bytes", OutboundFieldKind.SECRET),
    outboundField("auth_str", "Authentication string", OutboundFieldKind.SECRET),
    outboundSelect("network", "Network", listOf("", "tcp", "udp")),
)

internal fun tuicOutboundFields() = listOf(
    outboundField("uuid", "UUID", required = true),
    outboundField("password", "Password", OutboundFieldKind.SECRET, required = true),
    outboundSelect("congestion_control", "Congestion control", listOf("", "cubic", "new_reno", "bbr")),
    outboundSelect("udp_relay_mode", "UDP relay mode", listOf("", "native", "quic")),
    outboundField("udp_over_stream", "UDP over stream", OutboundFieldKind.BOOLEAN),
    outboundField("zero_rtt_handshake", "0-RTT handshake", OutboundFieldKind.BOOLEAN),
    outboundField("heartbeat", "Heartbeat interval"),
    outboundSelect("network", "Network", listOf("", "tcp", "udp")),
)

internal fun hysteria2OutboundFields() = listOf(
    outboundField("server_ports", "Server ports", OutboundFieldKind.TEXT_LIST),
    outboundField("hop_interval", "Port hopping interval"),
    outboundField("up_mbps", "Upload bandwidth (Mbps)", OutboundFieldKind.INTEGER),
    outboundField("down_mbps", "Download bandwidth (Mbps)", OutboundFieldKind.INTEGER),
    outboundSelect("obfs.type", "Obfuscation", listOf("", "salamander")),
    outboundField(
        "obfs.password",
        "Obfuscation password",
        OutboundFieldKind.SECRET,
        conditions = listOf(OutboundFieldCondition("obfs.type", setOf("salamander"))),
    ),
    outboundField("password", "Password", OutboundFieldKind.SECRET, required = true),
    outboundSelect("network", "Network", listOf("", "tcp", "udp")),
)

internal fun LazyListScope.hysteriaOutboundEditor(state: OutboundEditorContentState) {
    outboundEditorSections(state)
}

internal fun LazyListScope.tuicOutboundEditor(state: OutboundEditorContentState) {
    outboundEditorSections(state)
}

internal fun LazyListScope.hysteria2OutboundEditor(state: OutboundEditorContentState) {
    outboundEditorSections(state)
}
