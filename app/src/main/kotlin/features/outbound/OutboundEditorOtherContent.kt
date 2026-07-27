// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.outbound

import androidx.compose.foundation.lazy.LazyListScope

internal fun shadowTlsOutboundFields() = listOf(
    outboundSelect("version", "ShadowTLS version", listOf("1", "2", "3")),
    outboundField(
        "password",
        "Password",
        OutboundFieldKind.SECRET,
        conditions = listOf(OutboundFieldCondition("version", setOf("2", "3"))),
    ),
)

internal fun anyTlsOutboundFields() = listOf(
    outboundField("password", "Password", OutboundFieldKind.SECRET, required = true),
    outboundField("idle_session_check_interval", "Idle session check interval"),
    outboundField("idle_session_timeout", "Idle session timeout"),
    outboundField("min_idle_session", "Minimum idle sessions", OutboundFieldKind.INTEGER),
)

internal fun snellOutboundFields() = listOf(
    outboundSelect("version", "Snell version", listOf("4", "6")),
    outboundField("psk", "Pre-shared key", OutboundFieldKind.SECRET, required = true),
    outboundField("userkey", "User key", OutboundFieldKind.SECRET),
    outboundField("reuse", "Connection reuse", OutboundFieldKind.BOOLEAN),
    outboundSelect("network", "Network", listOf("", "tcp", "udp")),
    outboundSelect(
        "obfs_mode",
        "Obfuscation mode",
        listOf("", "none", "http"),
        conditions = listOf(OutboundFieldCondition("version", setOf("4"))),
    ),
    outboundField(
        "obfs_host",
        "Obfuscation host",
        conditions = listOf(
            OutboundFieldCondition("version", setOf("4")),
            OutboundFieldCondition("obfs_mode", setOf("http")),
        ),
    ),
    outboundSelect(
        "mode",
        "Traffic shaping mode",
        listOf("", "default", "unshaped", "unsafe-raw"),
        conditions = listOf(OutboundFieldCondition("version", setOf("6"))),
    ),
)

internal fun sshOutboundFields() = listOf(
    outboundField("user", "User"),
    outboundField("password", "Password", OutboundFieldKind.SECRET),
    outboundField("private_key", "Private key", OutboundFieldKind.MULTILINE),
    outboundField("private_key_path", "Private key path"),
    outboundField("private_key_passphrase", "Private key passphrase", OutboundFieldKind.SECRET),
    outboundField("host_key", "Host keys", OutboundFieldKind.TEXT_LIST),
    outboundField("host_key_algorithms", "Host key algorithms", OutboundFieldKind.TEXT_LIST),
    outboundField("client_version", "Client version"),
    outboundField("cipher", "Ciphers", OutboundFieldKind.TEXT_LIST),
    outboundField("mac", "MAC algorithms", OutboundFieldKind.TEXT_LIST),
    outboundField("kex_algorithm", "Key exchange algorithms", OutboundFieldKind.TEXT_LIST),
)

internal fun LazyListScope.shadowTlsOutboundEditor(state: OutboundEditorContentState) {
    outboundEditorSections(state)
}

internal fun LazyListScope.anyTlsOutboundEditor(state: OutboundEditorContentState) {
    outboundEditorSections(state)
}

internal fun LazyListScope.snellOutboundEditor(state: OutboundEditorContentState) {
    outboundEditorSections(state)
}

internal fun LazyListScope.sshOutboundEditor(state: OutboundEditorContentState) {
    outboundEditorSections(state)
}
