// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.outbound

import kotlinx.serialization.json.JsonObject
import utils.booleanOrFalse
import utils.intOrZero
import utils.objectValue
import utils.stringListValue
import utils.stringOrEmpty

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
    require(stringOrEmpty("hop_interval").isBlank()) { "Hysteria hop interval cannot be shared" }
    require(!get("auth").isMeaningfulShareValue()) { "Hysteria binary auth cannot be shared" }
    require(stringOrEmpty("network").let { it.isBlank() || it == "udp" }) {
        "Hysteria network cannot be shared"
    }
    requireQuicTuningEmpty()
    val upMbps = intOrZero("up_mbps")
    val downMbps = intOrZero("down_mbps")
    require(upMbps > 0 && downMbps > 0) {
        "Hysteria upload and download bandwidth are required"
    }
    val alpn = objectValue("tls")?.stringListValue("alpn").orEmpty()
    require(alpn.size <= 1) { "Hysteria URI supports at most one ALPN value" }
    val tls = readShareTls(required = true)
        .requireOnlyShareParameters("Hysteria", "sni", "insecure", "alpn")
    val parameters = buildList {
        add("protocol" to "udp")
        stringOrEmpty("auth_str").takeIf(String::isNotBlank)?.let { add("auth" to it) }
        tls.parameterValue("sni").takeIf(String::isNotBlank)?.let { add("peer" to it) }
        tls.parameterValue("insecure").takeIf(String::isNotBlank)?.let { add("insecure" to it) }
        add("upmbps" to upMbps.toString())
        add("downmbps" to downMbps.toString())
        alpn.singleOrNull()?.let { add("alpn" to it) }
        stringOrEmpty("obfs").takeIf(String::isNotBlank)?.let {
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
        HYSTERIA2_DISABLE_CHROME_PARROT,
    )
    require(stringOrEmpty("hop_interval").isBlank()) { "Hysteria2 hop interval cannot be shared" }
    require(intOrZero("up_mbps") == 0 && intOrZero("down_mbps") == 0) {
        "Hysteria2 bandwidth is intentionally excluded by its share URI"
    }
    require(stringOrEmpty("network").isBlank()) { "Hysteria2 network cannot be shared" }
    requireQuicTuningEmpty()
    val password = stringOrEmpty("password")
    require(password.isNotBlank()) { "Hysteria2 password is required" }
    val parameters = mutableListOf<Pair<String, String>>()
    objectValue("obfs")?.let { obfs ->
        obfs.requireOnlyNestedShareFields("type", "password")
        val obfsType = obfs.stringOrEmpty("type")
        val obfsPassword = obfs.stringOrEmpty("password")
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
    // Always explicit: an absent value means disabled on import, but enabled in the core.
    parameters += HYSTERIA2_DISABLE_CHROME_PARROT to
        booleanOrFalse(HYSTERIA2_DISABLE_CHROME_PARROT).toString()
    val serverPorts = stringListValue("server_ports")
    val encodedServerPorts = serverPorts.map(::encodeHysteriaPortRange)
    val endpoint = if (encodedServerPorts.isEmpty()) {
        requireShareEndpoint()
    } else {
        val host = stringOrEmpty("server").removeSurrounding("[", "]")
        require(host.isNotBlank()) { "Hysteria2 server is required" }
        val declaredPort = intOrZero("server_port")
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
    require(!booleanOrFalse("udp_over_stream")) { "TUIC UDP over stream cannot be shared" }
    require(stringOrEmpty("heartbeat").isBlank()) { "TUIC heartbeat cannot be shared" }
    require(stringOrEmpty("network").isBlank()) { "TUIC network cannot be shared" }
    require(stringOrEmpty("max_idle_timeout").isBlank()) { "TUIC max idle timeout cannot be shared" }
    require(stringOrEmpty("keep_alive_period").isBlank()) { "TUIC keep-alive period cannot be shared" }
    val uuid = stringOrEmpty("uuid")
    val password = stringOrEmpty("password")
    require(uuid.isNotBlank() && password.isNotBlank()) { "TUIC credentials are required" }
    val tls = readShareTls(required = true)
        .requireOnlyShareParameters("TUIC", "sni", "insecure", "alpn")
    val parameters = buildList {
        stringOrEmpty("congestion_control").takeIf(String::isNotBlank)?.let {
            add("congestion_control" to it)
        }
        stringOrEmpty("udp_relay_mode").takeIf(String::isNotBlank)?.let {
            add("udp_relay_mode" to it)
        }
        if (booleanOrFalse("zero_rtt_handshake")) add("allow_insecure_0rtt" to "1")
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
    require(stringOrEmpty("congestion_control").isBlank()) {
        "QUIC congestion control cannot be shared"
    }
    require(stringOrEmpty("max_idle_timeout").isBlank()) { "QUIC max idle timeout cannot be shared" }
    require(stringOrEmpty("keep_alive_period").isBlank()) { "QUIC keep-alive period cannot be shared" }
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
