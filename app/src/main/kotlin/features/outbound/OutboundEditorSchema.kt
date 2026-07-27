// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.outbound

import androidx.annotation.StringRes
import engine.singbox.config.SingBoxDeprecatedConfigValidator
import engine.singbox.config.SingBoxJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import org.asterisk.zcc.abox.R

internal enum class OutboundFieldKind {
    TEXT,
    SECRET,
    INTEGER,
    BOOLEAN,
    SELECT,
    MULTI_SELECT,
    REFERENCE,
    TEXT_LIST,
    KEY_VALUE,
    MULTILINE,
}

internal enum class OutboundEditorSection {
    SERVER,
    PROTOCOL,
    TLS,
    TRANSPORT,
    MULTIPLEX,
    QUIC,
    DIAL,
}

internal data class OutboundFieldCondition(
    val path: String,
    val values: Set<String> = setOf("true"),
)

internal data class OutboundFieldSpec(
    val path: String,
    @param:StringRes val labelRes: Int,
    val kind: OutboundFieldKind = OutboundFieldKind.TEXT,
    val required: Boolean = false,
    val options: List<String> = emptyList(),
    val conditions: List<OutboundFieldCondition> = emptyList(),
)

internal data class OutboundSectionSpec(
    val section: OutboundEditorSection,
    val fields: List<OutboundFieldSpec>,
)

internal data class OutboundEditorSchema(
    val type: String,
    val title: String,
    val sections: List<OutboundSectionSpec>,
) {
    val fields: List<OutboundFieldSpec>
        get() = sections.flatMap(OutboundSectionSpec::fields)
}

internal data class OutboundEditorDescriptor(
    val type: String,
    val title: String,
)

internal object OutboundEditorRegistry {
    val descriptors: List<OutboundEditorDescriptor> = listOf(
        OutboundEditorDescriptor("socks", "SOCKS"),
        OutboundEditorDescriptor("http", "HTTP"),
        OutboundEditorDescriptor("shadowsocks", "Shadowsocks"),
        OutboundEditorDescriptor("vmess", "VMess"),
        OutboundEditorDescriptor("trojan", "Trojan"),
        OutboundEditorDescriptor("hysteria", "Hysteria"),
        OutboundEditorDescriptor("vless", "VLESS"),
        OutboundEditorDescriptor("shadowtls", "ShadowTLS"),
        OutboundEditorDescriptor("tuic", "TUIC"),
        OutboundEditorDescriptor("hysteria2", "Hysteria 2"),
        OutboundEditorDescriptor("anytls", "AnyTLS"),
        OutboundEditorDescriptor("snell", "Snell"),
        OutboundEditorDescriptor("ssh", "SSH"),
    )
    private val schemaCache = mutableMapOf<String, OutboundEditorSchema>()

    fun descriptor(type: String): OutboundEditorDescriptor =
        descriptors.firstOrNull { it.type == type }
            ?: throw IllegalArgumentException("Unsupported outbound type: $type")

    @Synchronized
    fun schema(type: String): OutboundEditorSchema =
        schemaCache.getOrPut(type) { createSchema(type) }

    private fun createSchema(type: String): OutboundEditorSchema = when (type) {
        "socks" -> schema(type, descriptor(type).title, socksOutboundFields())
        "http" -> schema(type, descriptor(type).title, httpOutboundFields(), tls = true)
        "shadowsocks" -> schema(
            type,
            descriptor(type).title,
            shadowsocksOutboundFields(),
            multiplex = true,
        )
        "vmess" -> schema(
            type,
            descriptor(type).title,
            vmessOutboundFields(),
            tls = true,
            transport = true,
            multiplex = true,
        )
        "trojan" -> schema(
            type,
            descriptor(type).title,
            trojanOutboundFields(),
            tls = true,
            transport = true,
            multiplex = true,
        )
        "hysteria" -> schema(
            type,
            descriptor(type).title,
            hysteriaOutboundFields(),
            tls = true,
            quic = true,
        )
        "vless" -> schema(
            type,
            descriptor(type).title,
            vlessOutboundFields(),
            tls = true,
            transport = true,
            multiplex = true,
        )
        "shadowtls" -> schema(type, descriptor(type).title, shadowTlsOutboundFields(), tls = true)
        "tuic" -> schema(
            type,
            descriptor(type).title,
            tuicOutboundFields(),
            tls = true,
            quic = true,
        )
        "hysteria2" -> schema(
            type,
            descriptor(type).title,
            hysteria2OutboundFields(),
            tls = true,
            quic = true,
        )
        "anytls" -> schema(type, descriptor(type).title, anyTlsOutboundFields(), tls = true)
        "snell" -> schema(type, descriptor(type).title, snellOutboundFields())
        "ssh" -> schema(type, descriptor(type).title, sshOutboundFields())
        else -> throw IllegalArgumentException("Unsupported outbound type: $type")
    }

    private fun schema(
        type: String,
        title: String,
        protocolFields: List<OutboundFieldSpec>,
        tls: Boolean = false,
        transport: Boolean = false,
        multiplex: Boolean = false,
        quic: Boolean = false,
    ): OutboundEditorSchema = OutboundEditorSchema(
        type = type,
        title = title,
        sections = buildList {
            add(OutboundSectionSpec(OutboundEditorSection.SERVER, serverFields()))
            add(OutboundSectionSpec(OutboundEditorSection.PROTOCOL, protocolFields))
            if (tls) add(OutboundSectionSpec(OutboundEditorSection.TLS, tlsFields()))
            if (transport) add(OutboundSectionSpec(OutboundEditorSection.TRANSPORT, transportFields()))
            if (multiplex) add(OutboundSectionSpec(OutboundEditorSection.MULTIPLEX, multiplexFields()))
            if (quic) add(OutboundSectionSpec(OutboundEditorSection.QUIC, quicFields()))
            add(OutboundSectionSpec(OutboundEditorSection.DIAL, dialFields()))
        },
    )

    private fun serverFields() = listOf(
        field("server", "Server", required = true),
        field("server_port", "Server port", OutboundFieldKind.INTEGER, required = true),
    )

    private fun tlsFields() = listOf(
        field("tls.enabled", "TLS", OutboundFieldKind.BOOLEAN),
        field("tls.server_name", "Server name", conditions = on("tls.enabled")),
        field("tls.insecure", "Allow insecure", OutboundFieldKind.BOOLEAN, conditions = on("tls.enabled")),
        field("tls.alpn", "ALPN", OutboundFieldKind.TEXT_LIST, conditions = on("tls.enabled")),
        select(
            "tls.min_version",
            "Minimum TLS version",
            listOf("", "1.0", "1.1", "1.2", "1.3"),
            conditions = on("tls.enabled"),
        ),
        select(
            "tls.max_version",
            "Maximum TLS version",
            listOf("", "1.0", "1.1", "1.2", "1.3"),
            conditions = on("tls.enabled"),
        ),
        field(
            "tls.cipher_suites",
            "Cipher suites",
            OutboundFieldKind.MULTI_SELECT,
            options = SingBoxTlsCipherSuites,
            conditions = on("tls.enabled"),
        ),
        field(
            "tls.curve_preferences",
            "Curve preferences",
            OutboundFieldKind.MULTI_SELECT,
            options = SingBoxTlsCurvePreferences,
            conditions = on("tls.enabled"),
        ),
        field("tls.certificate_path", "CA certificate path", conditions = on("tls.enabled")),
        field(
            "tls.certificate",
            "CA certificate",
            OutboundFieldKind.MULTILINE,
            conditions = on("tls.enabled"),
        ),
        field(
            "tls.certificate_public_key_sha256",
            "Certificate public key SHA-256",
            OutboundFieldKind.TEXT_LIST,
            conditions = on("tls.enabled"),
        ),
        field("tls.client_certificate_path", "Client certificate path", conditions = on("tls.enabled")),
        field("tls.client_key_path", "Client key path", conditions = on("tls.enabled")),
        field(
            "tls.client_certificate",
            "Client certificate",
            OutboundFieldKind.MULTILINE,
            conditions = on("tls.enabled"),
        ),
        field(
            "tls.client_key",
            "Client key",
            OutboundFieldKind.MULTILINE,
            conditions = on("tls.enabled"),
        ),
        field("tls.utls.enabled", "uTLS", OutboundFieldKind.BOOLEAN, conditions = on("tls.enabled")),
        select(
            "tls.utls.fingerprint",
            "uTLS fingerprint",
            listOf("", "chrome", "firefox", "edge", "safari", "360", "qq", "ios", "android", "random", "randomized"),
            conditions = listOf(
                OutboundFieldCondition("tls.enabled"),
                OutboundFieldCondition("tls.utls.enabled"),
            ),
        ),
        field("tls.reality.enabled", "Reality", OutboundFieldKind.BOOLEAN, conditions = on("tls.enabled")),
        field(
            "tls.reality.public_key",
            "Reality public key",
            conditions = listOf(
                OutboundFieldCondition("tls.enabled"),
                OutboundFieldCondition("tls.reality.enabled"),
            ),
        ),
        field(
            "tls.reality.short_id",
            "Reality short ID",
            conditions = listOf(
                OutboundFieldCondition("tls.enabled"),
                OutboundFieldCondition("tls.reality.enabled"),
            ),
        ),
    )

    private fun transportFields() = listOf(
        select("transport.type", "Transport", listOf("", "http", "ws", "quic", "grpc", "httpupgrade")),
        field(
            "transport.host",
            "Host",
            conditions = listOf(OutboundFieldCondition("transport.type", setOf("http", "httpupgrade"))),
        ),
        field(
            "transport.path",
            "Path",
            conditions = listOf(OutboundFieldCondition("transport.type", setOf("http", "ws", "httpupgrade"))),
        ),
        field(
            "transport.method",
            "HTTP method",
            conditions = listOf(OutboundFieldCondition("transport.type", setOf("http"))),
        ),
        field(
            "transport.headers",
            "Headers",
            OutboundFieldKind.KEY_VALUE,
            conditions = listOf(OutboundFieldCondition("transport.type", setOf("http", "ws", "httpupgrade"))),
        ),
        field(
            "transport.max_early_data",
            "Maximum early data",
            OutboundFieldKind.INTEGER,
            conditions = listOf(OutboundFieldCondition("transport.type", setOf("ws", "httpupgrade"))),
        ),
        field(
            "transport.early_data_header_name",
            "Early data header",
            conditions = listOf(OutboundFieldCondition("transport.type", setOf("ws", "httpupgrade"))),
        ),
        field(
            "transport.service_name",
            "gRPC service name",
            conditions = listOf(OutboundFieldCondition("transport.type", setOf("grpc"))),
        ),
        field(
            "transport.idle_timeout",
            "Idle timeout",
            conditions = listOf(OutboundFieldCondition("transport.type", setOf("http", "grpc"))),
        ),
        field(
            "transport.ping_timeout",
            "Ping timeout",
            conditions = listOf(OutboundFieldCondition("transport.type", setOf("http", "grpc"))),
        ),
        field(
            "transport.permit_without_stream",
            "Permit without stream",
            OutboundFieldKind.BOOLEAN,
            conditions = listOf(OutboundFieldCondition("transport.type", setOf("grpc"))),
        ),
    )

    private fun multiplexFields() = listOf(
        field("multiplex.enabled", "Multiplex", OutboundFieldKind.BOOLEAN),
        select(
            "multiplex.protocol",
            "Multiplex protocol",
            listOf("", "smux", "yamux", "h2mux"),
            conditions = on("multiplex.enabled"),
        ),
        field(
            "multiplex.max_connections",
            "Maximum connections",
            OutboundFieldKind.INTEGER,
            conditions = on("multiplex.enabled"),
        ),
        field(
            "multiplex.min_streams",
            "Minimum streams",
            OutboundFieldKind.INTEGER,
            conditions = on("multiplex.enabled"),
        ),
        field(
            "multiplex.max_streams",
            "Maximum streams",
            OutboundFieldKind.INTEGER,
            conditions = on("multiplex.enabled"),
        ),
        field("multiplex.padding", "Padding", OutboundFieldKind.BOOLEAN, conditions = on("multiplex.enabled")),
        field(
            "multiplex.brutal.enabled",
            "Brutal",
            OutboundFieldKind.BOOLEAN,
            conditions = on("multiplex.enabled"),
        ),
        field(
            "multiplex.brutal.up_mbps",
            "Brutal upload (Mbps)",
            OutboundFieldKind.INTEGER,
            conditions = listOf(
                OutboundFieldCondition("multiplex.enabled"),
                OutboundFieldCondition("multiplex.brutal.enabled"),
            ),
        ),
        field(
            "multiplex.brutal.down_mbps",
            "Brutal download (Mbps)",
            OutboundFieldKind.INTEGER,
            conditions = listOf(
                OutboundFieldCondition("multiplex.enabled"),
                OutboundFieldCondition("multiplex.brutal.enabled"),
            ),
        ),
    )

    private fun quicFields() = listOf(
        select("congestion_control", "QUIC congestion control", listOf("", "cubic", "new_reno", "bbr")),
        field("max_idle_timeout", "QUIC maximum idle timeout"),
        field("keep_alive_period", "QUIC keep-alive period"),
    )

    private fun dialFields() = listOf(
        field("detour", "Detour outbound", OutboundFieldKind.REFERENCE),
        field("bind_interface", "Bind interface"),
        field("inet4_bind_address", "IPv4 bind address"),
        field("inet6_bind_address", "IPv6 bind address"),
        field("bind_address_no_port", "Bind address without port", OutboundFieldKind.BOOLEAN),
        field("routing_mark", "Routing mark", OutboundFieldKind.INTEGER),
        field("reuse_addr", "Reuse address", OutboundFieldKind.BOOLEAN),
        field("connect_timeout", "Connect timeout"),
        field("tcp_fast_open", "TCP Fast Open", OutboundFieldKind.BOOLEAN),
        field("tcp_multi_path", "TCP MultiPath", OutboundFieldKind.BOOLEAN),
        field("disable_tcp_keep_alive", "Disable TCP keep-alive", OutboundFieldKind.BOOLEAN),
        field("tcp_keep_alive", "TCP keep-alive"),
        field("tcp_keep_alive_interval", "TCP keep-alive interval"),
        field("udp_fragment", "UDP fragmentation", OutboundFieldKind.BOOLEAN),
        field("domain_resolver", "Domain resolver", OutboundFieldKind.REFERENCE),
        select(
            "network_strategy",
            "Network strategy",
            listOf("", "default", "hybrid", "fallback"),
        ),
        field(
            "network_type",
            "Network types",
            OutboundFieldKind.MULTI_SELECT,
            options = SingBoxNetworkTypes,
        ),
        field(
            "fallback_network_type",
            "Fallback network types",
            OutboundFieldKind.MULTI_SELECT,
            options = SingBoxNetworkTypes,
        ),
        field("fallback_delay", "Fallback delay"),
    )

    private fun field(
        path: String,
        label: String,
        kind: OutboundFieldKind = OutboundFieldKind.TEXT,
        required: Boolean = false,
        options: List<String> = emptyList(),
        conditions: List<OutboundFieldCondition> = emptyList(),
    ) = OutboundFieldSpec(
        path = path,
        labelRes = outboundFieldLabelResource(label),
        kind = kind,
        required = required,
        options = options,
        conditions = conditions,
    )

    private fun select(
        path: String,
        label: String,
        options: List<String>,
        conditions: List<OutboundFieldCondition> = emptyList(),
    ) = field(path, label, OutboundFieldKind.SELECT, options = options, conditions = conditions)

    private fun on(path: String) = listOf(OutboundFieldCondition(path))
}

internal fun outboundField(
    path: String,
    label: String,
    kind: OutboundFieldKind = OutboundFieldKind.TEXT,
    required: Boolean = false,
    options: List<String> = emptyList(),
    conditions: List<OutboundFieldCondition> = emptyList(),
) = OutboundFieldSpec(
    path = path,
    labelRes = outboundFieldLabelResource(label),
    kind = kind,
    required = required,
    options = options,
    conditions = conditions,
)

internal fun outboundSelect(
    path: String,
    label: String,
    options: List<String>,
    required: Boolean = false,
    conditions: List<OutboundFieldCondition> = emptyList(),
) = outboundField(
    path = path,
    label = label,
    kind = OutboundFieldKind.SELECT,
    required = required,
    options = options,
    conditions = conditions,
)

internal val SingBoxNetworkTypes = listOf("wifi", "cellular", "ethernet", "other")

internal val SingBoxTlsCurvePreferences = listOf(
    "P256",
    "P384",
    "P521",
    "X25519",
    "X25519MLKEM768",
)

internal val SingBoxTlsCipherSuites = listOf(
    "TLS_RSA_WITH_AES_128_CBC_SHA",
    "TLS_RSA_WITH_AES_256_CBC_SHA",
    "TLS_RSA_WITH_AES_128_GCM_SHA256",
    "TLS_RSA_WITH_AES_256_GCM_SHA384",
    "TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA",
    "TLS_ECDHE_ECDSA_WITH_AES_256_CBC_SHA",
    "TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA",
    "TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA",
    "TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256",
    "TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384",
    "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256",
    "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384",
    "TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256",
    "TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256",
)

internal val MandatoryTlsOutboundTypes = setOf(
    "hysteria",
    "shadowtls",
    "tuic",
    "hysteria2",
    "anytls",
)

@StringRes
internal fun outboundFieldLabelResource(label: String): Int = when (label) {
    "0-RTT handshake" -> R.string.outbound_field_zero_rtt_handshake
    "Allow insecure" -> R.string.outbound_field_allow_insecure
    "ALPN" -> R.string.outbound_field_alpn
    "Alter ID" -> R.string.outbound_field_alter_id
    "Authenticated length" -> R.string.outbound_field_authenticated_length
    "Authentication bytes" -> R.string.outbound_field_authentication_bytes
    "Authentication string" -> R.string.outbound_field_authentication_string
    "Bind address without port" -> R.string.outbound_field_bind_address_without_port
    "Bind interface" -> R.string.outbound_field_bind_interface
    "Brutal" -> R.string.outbound_field_brutal
    "Brutal download (Mbps)" -> R.string.outbound_field_brutal_download_mbps
    "Brutal upload (Mbps)" -> R.string.outbound_field_brutal_upload_mbps
    "CA certificate" -> R.string.outbound_field_ca_certificate
    "CA certificate path" -> R.string.outbound_field_ca_certificate_path
    "Certificate public key SHA-256" -> R.string.outbound_field_certificate_public_key_sha256
    "Cipher suites" -> R.string.outbound_field_cipher_suites
    "Ciphers" -> R.string.outbound_field_ciphers
    "Client certificate" -> R.string.outbound_field_client_certificate
    "Client certificate path" -> R.string.outbound_field_client_certificate_path
    "Client key" -> R.string.outbound_field_client_key
    "Client key path" -> R.string.outbound_field_client_key_path
    "Client version" -> R.string.outbound_field_client_version
    "Congestion control" -> R.string.outbound_field_congestion_control
    "Connect timeout" -> R.string.outbound_field_connect_timeout
    "Connection reuse" -> R.string.outbound_field_connection_reuse
    "Curve preferences" -> R.string.outbound_field_curve_preferences
    "Detour outbound" -> R.string.outbound_field_detour_outbound
    "Disable TCP keep-alive" -> R.string.outbound_field_disable_tcp_keep_alive
    "Domain resolver" -> R.string.outbound_field_domain_resolver
    "Download bandwidth (Mbps)" -> R.string.outbound_field_download_bandwidth_mbps
    "Early data header" -> R.string.outbound_field_early_data_header
    "Encryption method" -> R.string.outbound_field_encryption_method
    "Fallback delay" -> R.string.outbound_field_fallback_delay
    "Fallback network types" -> R.string.outbound_field_fallback_network_types
    "Flow" -> R.string.outbound_field_flow
    "Global padding" -> R.string.outbound_field_global_padding
    "gRPC service name" -> R.string.outbound_field_grpc_service_name
    "Headers" -> R.string.outbound_field_headers
    "Heartbeat interval" -> R.string.outbound_field_heartbeat_interval
    "Host" -> R.string.outbound_field_host
    "Host key algorithms" -> R.string.outbound_field_host_key_algorithms
    "Host keys" -> R.string.outbound_field_host_keys
    "HTTP method" -> R.string.outbound_field_http_method
    "Idle session check interval" -> R.string.outbound_field_idle_session_check_interval
    "Idle session timeout" -> R.string.outbound_field_idle_session_timeout
    "Idle timeout" -> R.string.outbound_field_idle_timeout
    "IPv4 bind address" -> R.string.outbound_field_ipv4_bind_address
    "IPv6 bind address" -> R.string.outbound_field_ipv6_bind_address
    "Key exchange algorithms" -> R.string.outbound_field_key_exchange_algorithms
    "MAC algorithms" -> R.string.outbound_field_mac_algorithms
    "Maximum connections" -> R.string.outbound_field_maximum_connections
    "Maximum early data" -> R.string.outbound_field_maximum_early_data
    "Maximum streams" -> R.string.outbound_field_maximum_streams
    "Maximum TLS version" -> R.string.outbound_field_maximum_tls_version
    "Minimum idle sessions" -> R.string.outbound_field_minimum_idle_sessions
    "Minimum streams" -> R.string.outbound_field_minimum_streams
    "Minimum TLS version" -> R.string.outbound_field_minimum_tls_version
    "Multiplex" -> R.string.outbound_field_multiplex
    "Multiplex protocol" -> R.string.outbound_field_multiplex_protocol
    "Network" -> R.string.outbound_field_network
    "Network strategy" -> R.string.outbound_field_network_strategy
    "Network types" -> R.string.outbound_field_network_types
    "Obfuscation" -> R.string.outbound_field_obfuscation
    "Obfuscation host" -> R.string.outbound_field_obfuscation_host
    "Obfuscation mode" -> R.string.outbound_field_obfuscation_mode
    "Obfuscation password" -> R.string.outbound_field_obfuscation_password
    "Packet encoding" -> R.string.outbound_field_packet_encoding
    "Padding" -> R.string.outbound_field_padding
    "Password" -> R.string.outbound_field_password
    "Path" -> R.string.outbound_field_path
    "Permit without stream" -> R.string.outbound_field_permit_without_stream
    "Ping timeout" -> R.string.outbound_field_ping_timeout
    "Plugin" -> R.string.outbound_field_plugin
    "Plugin options" -> R.string.outbound_field_plugin_options
    "Port hopping interval" -> R.string.outbound_field_port_hopping_interval
    "Pre-shared key" -> R.string.outbound_field_pre_shared_key
    "Private key" -> R.string.outbound_field_private_key
    "Private key passphrase" -> R.string.outbound_field_private_key_passphrase
    "Private key path" -> R.string.outbound_field_private_key_path
    "QUIC congestion control" -> R.string.outbound_field_quic_congestion_control
    "QUIC keep-alive period" -> R.string.outbound_field_quic_keep_alive_period
    "QUIC maximum idle timeout" -> R.string.outbound_field_quic_maximum_idle_timeout
    "Reality" -> R.string.outbound_field_reality
    "Reality public key" -> R.string.outbound_field_reality_public_key
    "Reality short ID" -> R.string.outbound_field_reality_short_id
    "Request path" -> R.string.outbound_field_request_path
    "Reuse address" -> R.string.outbound_field_reuse_address
    "Routing mark" -> R.string.outbound_field_routing_mark
    "Security" -> R.string.outbound_field_security
    "Server" -> R.string.outbound_field_server
    "Server name" -> R.string.outbound_field_server_name
    "Server port" -> R.string.outbound_field_server_port
    "Server ports" -> R.string.outbound_field_server_ports
    "ShadowTLS version" -> R.string.outbound_field_shadowtls_version
    "Snell version" -> R.string.outbound_field_snell_version
    "SOCKS version" -> R.string.outbound_field_socks_version
    "TCP Fast Open" -> R.string.outbound_field_tcp_fast_open
    "TCP keep-alive" -> R.string.outbound_field_tcp_keep_alive
    "TCP keep-alive interval" -> R.string.outbound_field_tcp_keep_alive_interval
    "TCP MultiPath" -> R.string.outbound_field_tcp_multipath
    "TLS" -> R.string.outbound_field_tls
    "Traffic shaping mode" -> R.string.outbound_field_traffic_shaping_mode
    "Transport" -> R.string.outbound_field_transport
    "UDP fragmentation" -> R.string.outbound_field_udp_fragmentation
    "UDP over stream" -> R.string.outbound_field_udp_over_stream
    "UDP over TCP" -> R.string.outbound_field_udp_over_tcp
    "UDP over TCP version" -> R.string.outbound_field_udp_over_tcp_version
    "UDP relay mode" -> R.string.outbound_field_udp_relay_mode
    "Upload bandwidth (Mbps)" -> R.string.outbound_field_upload_bandwidth_mbps
    "User" -> R.string.outbound_field_user
    "User key" -> R.string.outbound_field_user_key
    "Username" -> R.string.outbound_field_username
    "uTLS" -> R.string.outbound_field_utls
    "uTLS fingerprint" -> R.string.outbound_field_utls_fingerprint
    "UUID" -> R.string.outbound_field_uuid
    else -> error("Missing outbound field string resource: $label")
}

internal data class OutboundEditorValidationError(
    val path: String,
    val reason: OutboundEditorValidationReason,
)

internal enum class OutboundEditorValidationReason {
    REQUIRED,
    INVALID_PORT,
    INVALID_INTEGER,
    INVALID_REFERENCE,
}

internal data class OutboundEditorDocument(
    val value: JsonObject,
) {
    val type: String
        get() = text("type")

    fun text(path: String): String {
        val element = element(path) ?: return ""
        return when (element) {
            is JsonArray -> element.joinToString("\n") { item ->
                (item as? JsonPrimitive)?.contentOrNull ?: item.toString()
            }
            is JsonObject -> SingBoxJson.encodeToString(JsonElement.serializer(), element)
            is JsonPrimitive -> element.contentOrNull.orEmpty()
        }
    }

    fun boolean(path: String): Boolean =
        (element(path) as? JsonPrimitive)?.booleanOrNull == true

    fun entries(path: String): List<Pair<String, String>> =
        (element(path) as? JsonObject)
            ?.map { (key, value) ->
                key to ((value as? JsonPrimitive)?.contentOrNull ?: value.toString())
            }
            .orEmpty()

    fun isVisible(field: OutboundFieldSpec): Boolean =
        field.conditions.all { condition -> text(condition.path) in condition.values }

    fun setText(path: String, text: String): OutboundEditorDocument {
        val field = runCatching {
            OutboundEditorRegistry.schema(type).fields.firstOrNull { it.path == path }
        }.getOrNull()
        val kind = field?.kind
        val normalized = text.trim()
        val element = when {
            normalized.isEmpty() -> null
            kind == OutboundFieldKind.INTEGER -> normalized.toLongOrNull()?.let(::JsonPrimitive)
                ?: JsonPrimitive(normalized)
            kind == OutboundFieldKind.SELECT &&
                field.options.filter(String::isNotBlank).all { it.toIntOrNull() != null } ->
                normalized.toLongOrNull()?.let(::JsonPrimitive) ?: JsonPrimitive(normalized)
            kind in setOf(OutboundFieldKind.TEXT_LIST, OutboundFieldKind.MULTI_SELECT) -> JsonArray(
                text.lineSequence()
                    .flatMap { line -> line.split(',').asSequence() }
                    .map(String::trim)
                    .filter(String::isNotBlank)
                    .map(::JsonPrimitive)
                    .toList(),
            )
            else -> JsonPrimitive(text)
        }
        return copy(value = value.setPath(path, element))
    }

    fun setBoolean(path: String, enabled: Boolean): OutboundEditorDocument =
        copy(value = value.setPath(path, if (enabled) JsonPrimitive(true) else null))

    fun setEntries(path: String, entries: List<Pair<String, String>>): OutboundEditorDocument {
        val values = entries
            .map { (key, value) -> key.trim() to value.trim() }
            .filter { (key, _) -> key.isNotBlank() }
            .associate { (key, value) -> key to JsonPrimitive(value) }
        return copy(value = value.setPath(path, JsonObject(values).takeIf { it.isNotEmpty() }))
    }

    fun validate(): List<OutboundEditorValidationError> {
        val schema = OutboundEditorRegistry.schema(type)
        val errors = buildList {
            schema.fields.filter { it.required && isVisible(it) }.forEach { field ->
                if (text(field.path).isBlank()) {
                    add(OutboundEditorValidationError(field.path, OutboundEditorValidationReason.REQUIRED))
                }
            }
            val port = text("server_port").toIntOrNull()
            if (port == null || port !in 1..65535) {
                add(
                    OutboundEditorValidationError(
                        "server_port",
                        OutboundEditorValidationReason.INVALID_PORT,
                    ),
                )
            }
            schema.fields.filter { it.kind == OutboundFieldKind.INTEGER }.forEach { field ->
                val value = text(field.path)
                if (value.isNotBlank() && value.toLongOrNull() == null) {
                    add(
                        OutboundEditorValidationError(
                            field.path,
                            OutboundEditorValidationReason.INVALID_INTEGER,
                        ),
                    )
                }
            }
        }
        if (errors.isEmpty()) {
            val root = buildJsonObject { put("outbounds", JsonArray(listOf(value))) }
            SingBoxDeprecatedConfigValidator.validate(root)
        }
        return errors.distinctBy(OutboundEditorValidationError::path)
    }

    fun validateReferences(
        referenceOptions: Map<String, List<String>>,
    ): List<OutboundEditorValidationError> {
        val schema = OutboundEditorRegistry.schema(type)
        return schema.fields
            .filter { field -> field.kind == OutboundFieldKind.REFERENCE }
            .mapNotNull { field ->
                val value = text(field.path).trim()
                if (value.isBlank() || value in referenceOptions[field.path].orEmpty()) {
                    null
                } else {
                    OutboundEditorValidationError(
                        path = field.path,
                        reason = OutboundEditorValidationReason.INVALID_REFERENCE,
                    )
                }
            }
    }

    fun toImported(remarks: String): ImportedSingBoxOutbound {
        val errors = validate()
        require(errors.isEmpty()) { "Invalid outbound field: ${errors.first().path}" }
        val tag = text("tag").trim()
        require(tag.isNotBlank()) { "Tag is required" }
        return ImportedSingBoxOutbound(
            sourceTag = tag,
            remarks = remarks.trim(),
            type = type,
            json = SingBoxJson.encodeToString(JsonElement.serializer(), value),
        )
    }

    private fun element(path: String): JsonElement? {
        var current: JsonElement = value
        path.split('.').forEach { part ->
            current = (current as? JsonObject)?.get(part) ?: return null
        }
        return current
    }

    companion object {
        fun create(type: String, tag: String): OutboundEditorDocument {
            OutboundEditorRegistry.schema(type)
            var document = OutboundEditorDocument(
                buildJsonObject {
                    put("type", type)
                    put("tag", tag)
                },
            )
            when (type) {
                "socks" -> document = document.setText("version", "5")
                "shadowsocks" -> document = document.setText("method", "aes-128-gcm")
                "vmess" -> document = document.setText("security", "auto")
                "shadowtls" -> document = document.setText("version", "3")
                "tuic" -> {
                    document = document
                        .setText("congestion_control", "bbr")
                        .setText("udp_relay_mode", "native")
                }
                "hysteria2" -> Unit
                "snell" -> document = document.setText("version", "4")
            }
            if (type in MandatoryTlsOutboundTypes) {
                document = document.setBoolean("tls.enabled", true)
            }
            return document
        }
    }
}

private fun JsonObject.setPath(path: String, replacement: JsonElement?): JsonObject {
    fun update(current: JsonObject, parts: List<String>): JsonObject {
        val key = parts.first()
        val values = current.toMutableMap()
        if (parts.size == 1) {
            if (replacement == null || replacement is JsonArray && replacement.isEmpty()) {
                values.remove(key)
            } else {
                values[key] = replacement
            }
        } else {
            val child = current[key] as? JsonObject ?: JsonObject(emptyMap())
            val updatedChild = update(child, parts.drop(1))
            if (updatedChild.isEmpty()) values.remove(key) else values[key] = updatedChild
        }
        return JsonObject(values)
    }
    return update(this, path.split('.'))
}
