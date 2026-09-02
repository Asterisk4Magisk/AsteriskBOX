// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.outbound

import androidx.compose.foundation.lazy.LazyListScope

internal fun naiveOutboundFields() = listOf(
    outboundField("username", "Username"),
    outboundField("password", "Password"),
    outboundField("insecure_concurrency", "Insecure concurrency", OutboundFieldKind.INTEGER),
    outboundField("extra_headers", "Extra headers", OutboundFieldKind.KEY_VALUE),
    outboundField("stream_receive_window", "Stream receive window"),
    outboundField("udp_over_tcp.enabled", "UDP over TCP", OutboundFieldKind.BOOLEAN),
    outboundField(
        "udp_over_tcp.version",
        "UDP over TCP version",
        OutboundFieldKind.INTEGER,
        conditions = listOf(OutboundFieldCondition("udp_over_tcp.enabled")),
    ),
    outboundField("quic", "Use QUIC", OutboundFieldKind.BOOLEAN),
    outboundSelect(
        "quic_congestion_control",
        "QUIC congestion control",
        listOf("", "bbr", "bbr2", "cubic", "reno"),
        conditions = listOf(OutboundFieldCondition("quic")),
    ),
    outboundField(
        "quic_session_receive_window",
        "QUIC session receive window",
        conditions = listOf(OutboundFieldCondition("quic")),
    ),
)

internal fun naiveTlsFields() = listOf(
    outboundField("tls.enabled", "TLS", OutboundFieldKind.BOOLEAN),
    outboundField("tls.server_name", "Server name"),
    outboundField("tls.certificate_path", "CA certificate path"),
    outboundField("tls.certificate", "CA certificate", OutboundFieldKind.MULTILINE),
    outboundField("tls.ech.enabled", "ECH", OutboundFieldKind.BOOLEAN),
    outboundField(
        "tls.ech.config",
        "ECH config",
        OutboundFieldKind.TEXT_LIST,
        conditions = listOf(OutboundFieldCondition("tls.ech.enabled")),
    ),
    outboundField(
        "tls.ech.config_path",
        "ECH config path",
        conditions = listOf(OutboundFieldCondition("tls.ech.enabled")),
    ),
    outboundField(
        "tls.ech.query_server_name",
        "ECH query server name",
        conditions = listOf(OutboundFieldCondition("tls.ech.enabled")),
    ),
)

internal fun shadowTlsOutboundFields() = listOf(
    outboundSelect("version", "ShadowTLS version", listOf("1", "2", "3")),
    outboundField(
        "password",
        "Password",
        conditions = listOf(OutboundFieldCondition("version", setOf("2", "3"))),
    ),
)

internal fun anyTlsOutboundFields() = listOf(
    outboundField("password", "Password", required = true),
    outboundField("idle_session_check_interval", "Idle session check interval"),
    outboundField("idle_session_timeout", "Idle session timeout"),
    outboundField("min_idle_session", "Minimum idle sessions", OutboundFieldKind.INTEGER),
    outboundField("client_metadata", "Client metadata"),
)

internal fun snellOutboundFields() = listOf(
    outboundSelect("version", "Snell version", listOf("4", "6")),
    outboundField("psk", "Pre-shared key", required = true),
    outboundField("userkey", "User key"),
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
    outboundField("password", "Password"),
    outboundField("private_key", "Private key", OutboundFieldKind.MULTILINE),
    outboundField("private_key_path", "Private key path"),
    outboundField("private_key_passphrase", "Private key passphrase"),
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

internal fun LazyListScope.naiveOutboundEditor(state: OutboundEditorContentState) {
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
