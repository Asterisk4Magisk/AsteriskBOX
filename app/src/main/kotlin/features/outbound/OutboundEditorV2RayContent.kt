// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.outbound

import androidx.compose.foundation.lazy.LazyListScope

internal fun vmessOutboundFields() = listOf(
    outboundField("uuid", "UUID", required = true),
    outboundSelect(
        "security",
        "Security",
        listOf("auto", "none", "zero", "aes-128-gcm", "chacha20-poly1305"),
    ),
    outboundField("alter_id", "Alter ID", OutboundFieldKind.INTEGER),
    outboundField("global_padding", "Global padding", OutboundFieldKind.BOOLEAN),
    outboundField("authenticated_length", "Authenticated length", OutboundFieldKind.BOOLEAN),
    outboundSelect("network", "Network", listOf("", "tcp", "udp")),
    outboundSelect("packet_encoding", "Packet encoding", listOf("", "packetaddr", "xudp")),
)

internal fun trojanOutboundFields() = listOf(
    outboundField("password", "Password", required = true),
    outboundSelect("network", "Network", listOf("", "tcp", "udp")),
)

internal fun vlessOutboundFields() = listOf(
    outboundField("uuid", "UUID", required = true),
    outboundSelect("flow", "Flow", listOf("", "xtls-rprx-vision")),
    outboundSelect("network", "Network", listOf("", "tcp", "udp")),
    outboundSelect("packet_encoding", "Packet encoding", listOf("", "packetaddr", "xudp")),
)

internal fun LazyListScope.vmessOutboundEditor(state: OutboundEditorContentState) {
    outboundEditorSections(state)
}

internal fun LazyListScope.trojanOutboundEditor(state: OutboundEditorContentState) {
    outboundEditorSections(state)
}

internal fun LazyListScope.vlessOutboundEditor(state: OutboundEditorContentState) {
    outboundEditorSections(state)
}
