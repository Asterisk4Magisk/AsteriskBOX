// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.outbound

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

internal fun encodeStandardOutboundShareUrl(
    type: String,
    outbound: JsonObject,
    remarks: String,
): String? = when (type) {
    "http" -> outbound.encodeHttpShareUrl(remarks)
    "socks" -> outbound.encodeSocksShareUrl(remarks)
    "naive" -> outbound.encodeNaiveShareUrl(remarks)
    "shadowsocks" -> outbound.encodeShadowsocksShareUrl(remarks)
    "anytls" -> outbound.encodeAnyTlsShareUrl(remarks)
    "shadowtls" -> outbound.encodeShadowTlsShareUrl(remarks)
    "snell" -> outbound.encodeSnellShareUrl(remarks)
    "ssh" -> outbound.encodeSshShareUrl(remarks)
    else -> null
}

private fun JsonObject.encodeHttpShareUrl(remarks: String): String {
    requireOnlyShareFields("username", "password", "path", "headers", "tls")
    require(!get("headers").isMeaningfulShareValue()) { "HTTP headers cannot be shared" }
    val tls = readShareTls(required = false)
        .requireOnlyShareParameters("HTTP")
    val endpoint = requireShareEndpoint()
    return buildOutboundShareUri(
        scheme = if (tls.enabled) "https" else "http",
        endpoint = endpoint,
        encodedUserInfo = encodeShareCredentials(stringValue("username"), stringValue("password")),
        path = stringValue("path"),
        parameters = tls.parameters,
        remarks = remarks,
    )
}

private fun JsonObject.encodeSocksShareUrl(remarks: String): String {
    requireOnlyShareFields("version", "username", "password", "network", "udp_over_tcp")
    require(stringValue("network").isBlank()) { "SOCKS network cannot be shared" }
    require(!get("udp_over_tcp").isMeaningfulShareValue()) { "SOCKS UDP over TCP cannot be shared" }
    val scheme = when (stringValue("version").ifBlank { "5" }) {
        "4" -> "socks4"
        "4a" -> "socks4a"
        "5" -> "socks5"
        else -> throw IllegalArgumentException("Unsupported SOCKS version")
    }
    return buildOutboundShareUri(
        scheme = scheme,
        endpoint = requireShareEndpoint(),
        encodedUserInfo = encodeShareCredentials(stringValue("username"), stringValue("password")),
        remarks = remarks,
    )
}

private fun JsonObject.encodeNaiveShareUrl(remarks: String): String {
    requireOnlyShareFields(
        "username",
        "password",
        "insecure_concurrency",
        "extra_headers",
        "udp_over_tcp",
        "quic",
        "quic_congestion_control",
        "tls",
    )
    val tls = readNaiveShareTls()
    val quic = booleanValue("quic")
    val congestionControl = stringValue("quic_congestion_control")
    require(quic || congestionControl.isBlank()) { "Naive QUIC options require QUIC" }
    val parameters = mutableListOf<Pair<String, String>>()
    intValue("insecure_concurrency").takeIf { it > 0 }?.let {
        parameters += "insecure-concurrency" to it.toString()
    }
    objectValue("extra_headers")?.let { headers ->
        val lines = headers.map { (name, value) ->
            val text = (value as? JsonPrimitive)?.contentOrNull
            require(name.isNotBlank() && !text.isNullOrBlank()) { "Invalid Naive extra header" }
            "$name: $text"
        }
        if (lines.isNotEmpty()) parameters += "extra-headers" to lines.joinToString("\n")
    }
    objectValue("udp_over_tcp")?.let { udpOverTcp ->
        udpOverTcp.requireOnlyNestedShareFields("enabled", "version")
        val enabled = udpOverTcp.booleanValue("enabled")
        val version = udpOverTcp.intValue("version")
        require(enabled || version == 0) { "Naive UDP over TCP version requires UDP over TCP" }
        if (enabled) {
            parameters += "udp-over-tcp" to "1"
            version.takeIf { it > 0 }?.let { parameters += "udp-over-tcp-version" to it.toString() }
        }
    }
    congestionControl.takeIf(String::isNotBlank)?.let {
        require(it in setOf("bbr", "bbr2", "cubic", "reno")) {
            "Unsupported Naive QUIC congestion control"
        }
        parameters += "quic-congestion-control" to it
    }
    parameters += tls
    return buildOutboundShareUri(
        scheme = if (quic) "naive+quic" else "naive",
        endpoint = requireShareEndpoint(),
        encodedUserInfo = encodeShareCredentials(stringValue("username"), stringValue("password")),
        parameters = parameters,
        remarks = remarks,
    )
}

private fun JsonObject.readNaiveShareTls(): List<Pair<String, String>> {
    val tls = requireNotNull(objectValue("tls")) { "Naive TLS is required" }
    tls.requireOnlyNestedShareFields("enabled", "server_name", "ech")
    require(tls.booleanValue("enabled")) { "Naive TLS is required" }
    return buildList {
        tls.stringValue("server_name").takeIf(String::isNotBlank)?.let { add("sni" to it) }
        tls.objectValue("ech")?.let { ech ->
            ech.requireOnlyNestedShareFields("enabled", "config")
            val enabled = ech.booleanValue("enabled")
            val configs = ech.stringListValue("config")
            require(enabled || configs.isEmpty()) { "Disabled ECH contains connection options" }
            if (enabled) {
                require(configs.isNotEmpty()) { "ECH config is required" }
                configs.forEach { add("ech" to it) }
            }
        }
    }
}

private fun JsonObject.encodeShadowsocksShareUrl(remarks: String): String {
    requireOnlyShareFields(
        "method",
        "password",
        "plugin",
        "plugin_opts",
        "network",
        "udp_over_tcp",
        "multiplex",
    )
    require(stringValue("network").isBlank()) { "Shadowsocks network cannot be shared" }
    require(!get("udp_over_tcp").isMeaningfulShareValue()) {
        "Shadowsocks UDP over TCP cannot be shared"
    }
    require(!get("multiplex").isMeaningfulShareValue()) { "Shadowsocks multiplex cannot be shared" }
    val method = stringValue("method")
    val password = stringValue("password")
    require(method.isNotBlank() && password.isNotBlank()) { "Shadowsocks credentials are required" }
    val userInfo = if (method.startsWith("2022-")) {
        encodeShareComponent(method) + ":" + encodeShareComponent(password)
    } else {
        encodeShareBase64Url("$method:$password")
    }
    val plugin = stringValue("plugin")
    require(plugin.isBlank() || plugin in setOf("obfs-local", "v2ray-plugin")) {
        "Unsupported Shadowsocks plugin"
    }
    val parameters = if (plugin.isBlank()) {
        emptyList()
    } else {
        val options = stringValue("plugin_opts")
        listOf("plugin" to if (options.isBlank()) plugin else "$plugin;$options")
    }
    return buildOutboundShareUri(
        scheme = "ss",
        endpoint = requireShareEndpoint(),
        encodedUserInfo = userInfo,
        parameters = parameters,
        remarks = remarks,
    )
}

private fun JsonObject.encodeAnyTlsShareUrl(remarks: String): String {
    requireOnlyShareFields(
        "password",
        "idle_session_check_interval",
        "idle_session_timeout",
        "min_idle_session",
        "client_metadata",
        "tls",
    )
    val password = stringValue("password")
    require(password.isNotBlank()) { "AnyTLS password is required" }
    listOf(
        "idle_session_check_interval",
        "idle_session_timeout",
        "min_idle_session",
    ).forEach { field ->
        require(!get(field).isMeaningfulShareValue()) {
            "AnyTLS session option cannot be shared: $field"
        }
    }
    val tls = readShareTls(required = true)
        .requireOnlyShareParameters("AnyTLS", "sni", "insecure")
    val parameters = buildList {
        stringValue("client_metadata").takeIf(String::isNotBlank)?.let {
            add("client-metadata" to it)
        }
        addAll(tls.parameters)
    }
    return buildOutboundShareUri(
        scheme = "anytls",
        endpoint = requireShareEndpoint(),
        encodedUserInfo = encodeShareComponent(password),
        parameters = parameters,
        remarks = remarks,
    )
}

private fun JsonObject.encodeShadowTlsShareUrl(remarks: String): String {
    requireOnlyShareFields("version", "password", "tls")
    val version = intValue("version")
    require(version in 1..3) { "Unsupported ShadowTLS version" }
    val password = stringValue("password")
    require(version == 1 || password.isNotBlank()) { "ShadowTLS password is required" }
    val tls = readShareTls(required = true)
        .requireOnlyShareParameters("ShadowTLS", "sni", "insecure", "fp")
    return buildOutboundShareUri(
        scheme = "shadowtls",
        endpoint = requireShareEndpoint(),
        encodedUserInfo = password.takeIf(String::isNotBlank)?.let(::encodeShareComponent),
        parameters = listOf("version" to version.toString()) + tls.parameters,
        remarks = remarks,
    )
}

private fun JsonObject.encodeSnellShareUrl(remarks: String): String {
    requireOnlyShareFields(
        "version",
        "psk",
        "userkey",
        "reuse",
        "network",
        "obfs_mode",
        "obfs_host",
        "mode",
    )
    require(intValue("version") == 4) { "Only Snell 4 has a supported share URL" }
    require(stringValue("userkey").isBlank()) { "Snell user key cannot be shared" }
    require(!booleanValue("reuse")) { "Snell reuse cannot be shared" }
    require(stringValue("network").isBlank()) { "Snell network cannot be shared" }
    require(stringValue("mode").isBlank()) { "Snell shaping mode cannot be shared" }
    val psk = stringValue("psk")
    require(psk.isNotBlank()) { "Snell PSK is required" }
    val obfsMode = stringValue("obfs_mode")
    require(obfsMode.isBlank() || obfsMode in setOf("none", "http")) { "Unsupported Snell obfuscation" }
    val parameters = buildList {
        add("version" to "4")
        obfsMode.takeIf { it.isNotBlank() && it != "none" }?.let { add("obfs" to it) }
        stringValue("obfs_host").takeIf(String::isNotBlank)?.let { add("obfs-host" to it) }
    }
    return buildOutboundShareUri(
        scheme = "snell",
        endpoint = requireShareEndpoint(),
        encodedUserInfo = encodeShareComponent(psk),
        parameters = parameters,
        remarks = remarks,
    )
}

private fun JsonObject.encodeSshShareUrl(remarks: String): String {
    requireOnlyShareFields(
        "user",
        "password",
        "private_key",
        "private_key_path",
        "private_key_passphrase",
        "host_key",
        "host_key_algorithms",
        "client_version",
        "cipher",
        "mac",
        "kex_algorithm",
    )
    listOf(
        "private_key",
        "private_key_path",
        "private_key_passphrase",
        "host_key",
        "host_key_algorithms",
        "client_version",
        "cipher",
        "mac",
        "kex_algorithm",
    ).forEach { field ->
        require(!get(field).isMeaningfulShareValue()) { "SSH field cannot be shared: $field" }
    }
    return buildOutboundShareUri(
        scheme = "ssh",
        endpoint = requireShareEndpoint(),
        encodedUserInfo = encodeShareCredentials(stringValue("user"), stringValue("password")),
        remarks = remarks,
    )
}
