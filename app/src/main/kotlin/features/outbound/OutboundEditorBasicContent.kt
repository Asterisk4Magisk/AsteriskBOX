// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.outbound

import androidx.compose.foundation.lazy.LazyListScope

internal fun socksOutboundFields() = listOf(
    outboundSelect("version", "SOCKS version", listOf("5", "4", "4a")),
    outboundField("username", "Username"),
    outboundField("password", "Password"),
    outboundSelect("network", "Network", listOf("", "tcp", "udp")),
    outboundField("udp_over_tcp.enabled", "UDP over TCP", OutboundFieldKind.BOOLEAN),
    outboundField(
        "udp_over_tcp.version",
        "UDP over TCP version",
        OutboundFieldKind.INTEGER,
        conditions = listOf(OutboundFieldCondition("udp_over_tcp.enabled")),
    ),
)

internal fun httpOutboundFields() = listOf(
    outboundField("username", "Username"),
    outboundField("password", "Password"),
    outboundField("path", "Request path"),
    outboundField("headers", "Headers", OutboundFieldKind.KEY_VALUE),
)

internal fun shadowsocksOutboundFields() = listOf(
    outboundSelect("method", "Encryption method", SingBoxShadowsocksMethods, required = true),
    outboundField("password", "Password", required = true),
    outboundSelect("plugin", "Plugin", listOf("", "obfs-local", "v2ray-plugin")),
    outboundField("plugin_opts", "Plugin options"),
    outboundSelect("network", "Network", listOf("", "tcp", "udp")),
    outboundField("udp_over_tcp.enabled", "UDP over TCP", OutboundFieldKind.BOOLEAN),
)

internal val SingBoxShadowsocksMethods = listOf(
    "2022-blake3-aes-128-gcm",
    "2022-blake3-aes-256-gcm",
    "2022-blake3-chacha20-poly1305",
    "none",
    "aes-128-gcm",
    "aes-192-gcm",
    "aes-256-gcm",
    "chacha20-ietf-poly1305",
    "xchacha20-ietf-poly1305",
    "aes-128-ctr",
    "aes-192-ctr",
    "aes-256-ctr",
    "aes-128-cfb",
    "aes-192-cfb",
    "aes-256-cfb",
    "rc4-md5",
    "chacha20-ietf",
    "xchacha20",
)

internal fun LazyListScope.socksOutboundEditor(state: OutboundEditorContentState) {
    outboundEditorSections(state)
}

internal fun LazyListScope.httpOutboundEditor(state: OutboundEditorContentState) {
    outboundEditorSections(state)
}

internal fun LazyListScope.shadowsocksOutboundEditor(state: OutboundEditorContentState) {
    outboundEditorSections(state)
}
