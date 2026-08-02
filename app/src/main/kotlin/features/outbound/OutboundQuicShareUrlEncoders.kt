// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.outbound

import kotlinx.serialization.json.JsonObject

internal fun encodeQuicOutboundShareUrl(
    type: String,
    outbound: JsonObject,
    remarks: String,
): String? = when (type) {
    "hysteria" -> outbound.encodeHysteriaShareUrl(remarks)
    "hysteria2" -> outbound.encodeHysteria2ShareUrl(remarks)
    "tuic" -> outbound.encodeTuicShareUrl(remarks)
    else -> null
}

private fun JsonObject.encodeHysteriaShareUrl(remarks: String): String {
    requireOnlyShareFields(
        "server_ports",
        "hop_interval",
        "up_mbps",
        "down_mbps",
        "obfs",
        "auth",
        "auth_str",
        "network",
        "tls",
        "congestion_control",
        "max_idle_timeout",
        "keep_alive_period",
    )
    require(!get("server_ports").isMeaningfulShareValue()) {
        "Hysteria port ranges cannot be shared"
    }
    require(stringValue("hop_interval").isBlank()) { "Hysteria hop interval cannot be shared" }
    require(!get("auth").isMeaningfulShareValue()) { "Hysteria binary auth cannot be shared" }
    require(stringValue("network").let { it.isBlank() || it == "udp" }) {
        "Hysteria network cannot be shared"
    }
    requireQuicTuningEmpty()
    val upMbps = intValue("up_mbps")
    val downMbps = intValue("down_mbps")
    require(upMbps > 0 && downMbps > 0) {
        "Hysteria upload and download bandwidth are required"
    }
    val alpn = objectValue("tls")?.stringListValue("alpn").orEmpty()
    require(alpn.size <= 1) { "Hysteria URI supports at most one ALPN value" }
    val tls = readShareTls(required = true)
        .requireOnlyShareParameters("Hysteria", "sni", "insecure", "alpn")
    val parameters = buildList {
        add("protocol" to "udp")
        stringValue("auth_str").takeIf(String::isNotBlank)?.let { add("auth" to it) }
        tls.parameterValue("sni").takeIf(String::isNotBlank)?.let { add("peer" to it) }
        tls.parameterValue("insecure").takeIf(String::isNotBlank)?.let { add("insecure" to it) }
        add("upmbps" to upMbps.toString())
        add("downmbps" to downMbps.toString())
        alpn.singleOrNull()?.let { add("alpn" to it) }
        stringValue("obfs").takeIf(String::isNotBlank)?.let {
            add("obfs" to "xplus")
            add("obfsParam" to it)
        }
    }
    return buildOutboundShareUri(
        scheme = "hysteria",
        endpoint = requireShareEndpoint(),
        parameters = parameters,
        remarks = remarks,
    )
}

private fun JsonObject.encodeHysteria2ShareUrl(remarks: String): String {
    requireOnlyShareFields(
        "server_ports",
        "hop_interval",
        "up_mbps",
        "down_mbps",
        "obfs",
        "password",
        "network",
        "tls",
        "congestion_control",
        "max_idle_timeout",
        "keep_alive_period",
    )
    require(stringValue("hop_interval").isBlank()) { "Hysteria2 hop interval cannot be shared" }
    require(intValue("up_mbps") == 0 && intValue("down_mbps") == 0) {
        "Hysteria2 bandwidth is intentionally excluded by its share URI"
    }
    require(stringValue("network").isBlank()) { "Hysteria2 network cannot be shared" }
    requireQuicTuningEmpty()
    val password = stringValue("password")
    require(password.isNotBlank()) { "Hysteria2 password is required" }
    val parameters = mutableListOf<Pair<String, String>>()
    objectValue("obfs")?.let { obfs ->
        obfs.requireOnlyNestedShareFields("type", "password")
        val obfsType = obfs.stringValue("type")
        val obfsPassword = obfs.stringValue("password")
        require(obfsType.isBlank() || obfsType in setOf("salamander", "gecko")) {
            "Unsupported Hysteria2 obfuscation"
        }
        require(obfsType.isBlank() == obfsPassword.isBlank()) {
            "Hysteria2 obfuscation type and password must be provided together"
        }
        if (obfsType.isNotBlank()) {
            parameters += "obfs" to obfsType
            parameters += "obfs-password" to obfsPassword
        }
    }
    val tls = readShareTls(required = true)
        .requireOnlyShareParameters("Hysteria2", "sni", "insecure", "ech")
    parameters += tls.parameters
    val serverPorts = stringListValue("server_ports")
    val encodedServerPorts = serverPorts.map(::encodeHysteriaPortRange)
    val endpoint = if (encodedServerPorts.isEmpty()) {
        requireShareEndpoint()
    } else {
        val host = stringValue("server").removeSurrounding("[", "]")
        require(host.isNotBlank()) { "Hysteria2 server is required" }
        val declaredPort = intValue("server_port")
        require(declaredPort == 0 || declaredPort in 1..65535) {
            "Hysteria2 server port is invalid"
        }
        val firstPort = encodedServerPorts.first().substringBefore('-').toInt()
        OutboundShareEndpoint(host, declaredPort.takeIf { it > 0 } ?: firstPort)
    }
    val authorityPort = if (encodedServerPorts.isEmpty()) {
        endpoint.port.toString()
    } else {
        encodedServerPorts.joinToString(",")
    }
    return buildOutboundShareUri(
        scheme = "hysteria2",
        endpoint = endpoint,
        encodedUserInfo = encodeShareComponent(password),
        authorityPort = authorityPort,
        parameters = parameters,
        remarks = remarks,
    )
}

private fun JsonObject.encodeTuicShareUrl(remarks: String): String {
    requireOnlyShareFields(
        "uuid",
        "password",
        "congestion_control",
        "udp_relay_mode",
        "udp_over_stream",
        "zero_rtt_handshake",
        "heartbeat",
        "network",
        "tls",
        "max_idle_timeout",
        "keep_alive_period",
    )
    require(!booleanValue("udp_over_stream")) { "TUIC UDP over stream cannot be shared" }
    require(stringValue("heartbeat").isBlank()) { "TUIC heartbeat cannot be shared" }
    require(stringValue("network").isBlank()) { "TUIC network cannot be shared" }
    require(stringValue("max_idle_timeout").isBlank()) { "TUIC max idle timeout cannot be shared" }
    require(stringValue("keep_alive_period").isBlank()) { "TUIC keep-alive period cannot be shared" }
    val uuid = stringValue("uuid")
    val password = stringValue("password")
    require(uuid.isNotBlank() && password.isNotBlank()) { "TUIC credentials are required" }
    val tls = readShareTls(required = true)
        .requireOnlyShareParameters("TUIC", "sni", "insecure", "alpn")
    val parameters = buildList {
        stringValue("congestion_control").takeIf(String::isNotBlank)?.let {
            add("congestion_control" to it)
        }
        stringValue("udp_relay_mode").takeIf(String::isNotBlank)?.let {
            add("udp_relay_mode" to it)
        }
        if (booleanValue("zero_rtt_handshake")) add("allow_insecure_0rtt" to "1")
        addAll(tls.parameters.map { (name, value) ->
            if (name == "insecure") "allow_insecure" to value else name to value
        })
    }
    return buildOutboundShareUri(
        scheme = "tuic",
        endpoint = requireShareEndpoint(),
        encodedUserInfo = encodeShareComponent(uuid) + ":" + encodeShareComponent(password),
        parameters = parameters,
        remarks = remarks,
    )
}

private fun JsonObject.requireQuicTuningEmpty() {
    require(stringValue("congestion_control").isBlank()) {
        "QUIC congestion control cannot be shared"
    }
    require(stringValue("max_idle_timeout").isBlank()) { "QUIC max idle timeout cannot be shared" }
    require(stringValue("keep_alive_period").isBlank()) { "QUIC keep-alive period cannot be shared" }
}

private fun encodeHysteriaPortRange(value: String): String {
    val parts = value.split(':')
    require(parts.size in 1..2) { "Invalid Hysteria2 port range" }
    val ports = parts.map { part ->
        part.toIntOrNull()?.takeIf { it in 1..65535 }
            ?: throw IllegalArgumentException("Invalid Hysteria2 port")
    }
    require(ports.size == 1 || ports[1] >= ports[0]) { "Invalid Hysteria2 port range" }
    return ports.joinToString("-")
}

private fun OutboundShareTls.parameterValue(name: String): String =
    parameters.firstOrNull { (parameterName, _) -> parameterName == name }?.second.orEmpty()
