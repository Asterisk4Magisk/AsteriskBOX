// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.endpoint

import engine.singbox.config.SingBoxJson
import features.importing.ImportIssue
import features.importing.ImportIssueReason
import features.importing.ImportIssueSeverity
import features.importing.ImportLimitException
import features.importing.ImportMutation
import features.importing.ImportOutcome
import features.importing.ImportStage
import features.importing.MaxImportBytes
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import java.net.URLDecoder
import kotlin.io.encoding.Base64
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal object WireGuardEndpointParser {
    fun parseUrlOutcomeOrNull(content: String): RecognizedEndpointImport? {
        val normalized = decodeWrappedUrlOrSelf(content.trim()) ?: content.trim()
        val scheme = normalized.substringBefore(':', missingDelimiterValue = "").lowercase()
        if (scheme !in RecognizedWireGuardSchemes) return null
        if (scheme in RejectedWireGuardSchemes) {
            return RecognizedEndpointImport(
                wireGuardFailure(
                    format = EndpointImportFormat.WIREGUARD_URL,
                    reason = ImportIssueReason.UNSUPPORTED_TYPE,
                    message = "AmneziaWG and WARP URLs are not supported",
                ),
            )
        }
        return RecognizedEndpointImport(
            runCatching { parseWireGuardUrl(normalized) }
                .getOrElse { error ->
                    wireGuardFailure(
                        format = EndpointImportFormat.WIREGUARD_URL,
                        reason = if (error is ImportLimitException) {
                            error.reason
                        } else {
                            ImportIssueReason.INVALID_FIELD
                        },
                        message = error.message ?: "Invalid WireGuard URL",
                    )
                },
        )
    }

    fun parseConfigOutcomeOrNull(content: String): RecognizedEndpointImport? {
        if (
            !content.lineSequence().any { line ->
                line.trim().equals("[Interface]", ignoreCase = true)
            }
        ) {
            return null
        }
        return RecognizedEndpointImport(
            runCatching { parseWireGuardConfig(content) }
                .getOrElse { error ->
                    wireGuardFailure(
                        format = EndpointImportFormat.WIREGUARD_CONFIG,
                        reason = ImportIssueReason.INVALID_FIELD,
                        message = error.message ?: "Invalid WireGuard configuration",
                    )
                },
        )
    }

    private fun parseWireGuardUrl(content: String): ImportOutcome<ImportedSingBoxEndpoint> {
        val uri = URI(content)
        require(uri.scheme.lowercase() in AllowedWireGuardSchemes) {
            "Unsupported WireGuard URL scheme"
        }
        val query = parseQuery(uri.rawQuery)
        val unknown = query.keys - WireGuardUrlFields
        require(unknown.isEmpty()) { "Unsupported WireGuard URL option" }
        require(query.keys.none { it in AwgOnlyFields }) {
            "AmneziaWG URL options are not supported"
        }
        val privateKey = query.firstValue("privatekey", "pk")
            ?: uri.rawUserInfo?.substringBefore(':')?.percentDecodedOnce()
        val publicKey = query.firstValue(
            "publickey",
            "peerpublickey",
            "pub",
            "peerpub",
        )
        val addresses = query.values("address", "ip")
            .flatMap(::splitCommaValues)
        val allowedIps = query.values("allowedips", "localaddress")
            .flatMap(::splitCommaValues)
            .ifEmpty { DefaultAllowedIps }
        val host = uri.host
            ?.removeSurrounding("[", "]")
            ?.takeIf(String::isNotBlank)
            ?: error("WireGuard peer host is required")
        val port = uri.port.requirePort("WireGuard peer port")
        requireWireGuardKey(privateKey, "WireGuard private key")
        requireWireGuardKey(publicKey, "WireGuard peer public key")
        require(addresses.isNotEmpty()) { "WireGuard interface address is required" }
        addresses.forEach(::requireCidr)
        allowedIps.forEach(::requireCidr)
        val preSharedKey = query.firstValue("presharedkey", "psk")
        preSharedKey?.let { requireWireGuardKey(it, "WireGuard pre-shared key") }
        val keepalive = query.firstValue("keepalive", "persistentkeepalive")
            ?.requireIntIn("WireGuard keepalive", 0, Int.MAX_VALUE)
        val mtu = query.firstValue("mtu")?.requireIntIn("WireGuard MTU", 1, 65_535)
        val listenPort = query.firstValue("listenport")
            ?.requireIntIn("WireGuard listen port", 1, 65_535)
        val workers = query.firstValue("workers")
            ?.requireIntIn("WireGuard worker count", 1, Int.MAX_VALUE)
        val reserved = query.firstValue("reserved")?.let(::parseReservedBytes)
        val remarks = uri.rawFragment?.percentDecodedOnce()?.trim()
            .orEmpty()
            .ifBlank { "wireguard" }
        return singleWireGuardOutcome(
            format = EndpointImportFormat.WIREGUARD_URL,
            remarks = remarks,
            addresses = addresses,
            privateKey = privateKey!!,
            listenPort = listenPort,
            mtu = mtu,
            workers = workers,
            peers = listOf(
                WireGuardPeer(
                    address = host,
                    port = port,
                    publicKey = publicKey!!,
                    preSharedKey = preSharedKey,
                    allowedIps = allowedIps,
                    keepalive = keepalive,
                    reserved = reserved,
                ),
            ),
        )
    }

    private fun parseWireGuardConfig(content: String): ImportOutcome<ImportedSingBoxEndpoint> {
        val interfaceValues = linkedMapOf<String, MutableList<String>>()
        val peerValues = mutableListOf<MutableMap<String, MutableList<String>>>()
        val mutations = mutableListOf<ImportMutation>()
        val ignoredOptions = linkedSetOf<String>()
        var section: String? = null
        var interfaceCount = 0
        content.lineSequence().forEach { rawLine ->
            val line = rawLine.substringBefore('#').substringBefore(';').trim()
            if (line.isBlank()) return@forEach
            if (line.startsWith("[") && line.endsWith("]")) {
                section = line.substring(1, line.length - 1).trim().lowercase()
                when (section) {
                    "interface" -> {
                        interfaceCount += 1
                        require(interfaceCount == 1) {
                            "Multiple WireGuard Interface sections are not supported"
                        }
                    }
                    "peer" -> peerValues += linkedMapOf()
                    else -> error("Unsupported WireGuard section")
                }
                return@forEach
            }
            val separator = line.indexOf('=')
            require(separator > 0) { "Invalid WireGuard configuration line" }
            val key = line.substring(0, separator).trim().lowercase()
            val value = line.substring(separator + 1).trim()
            require(value.isNotBlank()) { "WireGuard option value is empty" }
            when (section) {
                "interface" -> {
                    when (key) {
                        "address", "privatekey", "listenport", "mtu" ->
                            interfaceValues.getOrPut(key) { mutableListOf() } += value
                        "dns", "table", "saveconfig" -> ignoredOptions += key
                        "preup", "postup", "predown", "postdown" ->
                            error("Unsafe WireGuard interface directive is not supported")
                        else -> ignoredOptions += key
                    }
                }
                "peer" -> {
                    require(peerValues.isNotEmpty()) { "WireGuard Peer section is missing" }
                    when (key) {
                        "endpoint", "publickey", "presharedkey", "allowedips",
                        "persistentkeepalive", "reserved",
                        -> peerValues.last().getOrPut(key) { mutableListOf() } += value
                        else -> ignoredOptions += key
                    }
                }
                else -> error("WireGuard option appears before a section")
            }
        }
        require(interfaceCount == 1) { "WireGuard Interface section is required" }
        val privateKey = interfaceValues.singleValue("privatekey", "WireGuard private key")
        requireWireGuardKey(privateKey, "WireGuard private key")
        val addresses = interfaceValues["address"].orEmpty().flatMap(::splitCommaValues)
        require(addresses.isNotEmpty()) { "WireGuard interface address is required" }
        addresses.forEach(::requireCidr)
        require(peerValues.isNotEmpty()) { "At least one WireGuard Peer is required" }
        val peers = peerValues.map { values ->
            val endpoint = parseHostPort(
                values.singleValue("endpoint", "WireGuard peer endpoint"),
            )
            val publicKey = values.singleValue("publickey", "WireGuard peer public key")
            requireWireGuardKey(publicKey, "WireGuard peer public key")
            val allowedIps = values["allowedips"].orEmpty().flatMap(::splitCommaValues)
            require(allowedIps.isNotEmpty()) { "WireGuard peer allowed IPs are required" }
            allowedIps.forEach(::requireCidr)
            val psk = values.optionalSingleValue("presharedkey")
            psk?.let { requireWireGuardKey(it, "WireGuard pre-shared key") }
            WireGuardPeer(
                address = endpoint.first,
                port = endpoint.second,
                publicKey = publicKey,
                preSharedKey = psk,
                allowedIps = allowedIps,
                keepalive = values.optionalSingleValue("persistentkeepalive")
                    ?.requireIntIn("WireGuard keepalive", 0, Int.MAX_VALUE),
                reserved = values.optionalSingleValue("reserved")?.let(::parseReservedBytes),
            )
        }
        mutations += ignoredEndpointOptionMutations("WireGuard", ignoredOptions)
        return singleWireGuardOutcome(
            format = EndpointImportFormat.WIREGUARD_CONFIG,
            remarks = "wireguard",
            addresses = addresses,
            privateKey = privateKey,
            listenPort = interfaceValues.optionalSingleValue("listenport")
                ?.requireIntIn("WireGuard listen port", 1, 65_535),
            mtu = interfaceValues.optionalSingleValue("mtu")
                ?.requireIntIn("WireGuard MTU", 1, 65_535),
            workers = null,
            peers = peers,
            mutations = mutations,
        )
    }
}

private fun singleWireGuardOutcome(
    format: EndpointImportFormat,
    remarks: String,
    addresses: List<String>,
    privateKey: String,
    listenPort: Int?,
    mtu: Int?,
    workers: Int?,
    peers: List<WireGuardPeer>,
    mutations: List<ImportMutation> = emptyList(),
): ImportOutcome<ImportedSingBoxEndpoint> {
    val json = buildJsonObject {
        put("type", "wireguard")
        put("system", false)
        put("address", JsonArray(addresses.map(::JsonPrimitive)))
        put("private_key", privateKey)
        listenPort?.let { put("listen_port", it) }
        mtu?.let { put("mtu", it) }
        workers?.let { put("workers", it) }
        put(
            "peers",
            buildJsonArray {
                peers.forEach { peer ->
                    add(
                        buildJsonObject {
                            put("address", peer.address)
                            put("port", peer.port)
                            put("public_key", peer.publicKey)
                            peer.preSharedKey?.let { put("pre_shared_key", it) }
                            put(
                                "allowed_ips",
                                JsonArray(peer.allowedIps.map(::JsonPrimitive)),
                            )
                            peer.keepalive?.let {
                                put("persistent_keepalive_interval", it)
                            }
                            peer.reserved?.let {
                                put("reserved", JsonArray(it.map(::JsonPrimitive)))
                            }
                        },
                    )
                }
            },
        )
    }
    return ImportOutcome(
        format = format,
        detectedCount = 1,
        accepted = listOf(
            ImportedSingBoxEndpoint(
                sourceIndex = 0,
                remarks = remarks,
                type = "wireguard",
                json = SingBoxJson.encodeToString(JsonObject.serializer(), json),
            ),
        ),
        mutations = mutations,
    )
}

private fun wireGuardFailure(
    format: EndpointImportFormat,
    reason: ImportIssueReason,
    message: String,
) = ImportOutcome<ImportedSingBoxEndpoint>(
    format = format,
    detectedCount = 1,
    accepted = emptyList(),
    issues = listOf(
        ImportIssue(
            reason = reason,
            severity = ImportIssueSeverity.ERROR,
            stage = ImportStage.PARSE,
            sourceIndex = 0,
            detectedType = "wireguard",
            message = message,
        ),
    ),
)

private fun decodeWrappedUrlOrSelf(content: String): String? {
    if (content.substringBefore(':').lowercase() in RecognizedWireGuardSchemes) return content
    val compact = content.filterNot(Char::isWhitespace)
    if (compact.length * 3L / 4L > MaxImportBytes) {
        throw ImportLimitException(
            reason = ImportIssueReason.INPUT_TOO_LARGE,
            message = "Decoded WireGuard URL exceeds the allowed size",
        )
    }
    val padded = compact + "=".repeat((4 - compact.length % 4) % 4)
    return runCatching {
        Base64.UrlSafe.decode(padded).decodeToString()
    }.getOrNull()?.takeIf {
        it.substringBefore(':').lowercase() in RecognizedWireGuardSchemes
    }
}

private fun parseQuery(rawQuery: String?): Map<String, List<String>> {
    if (rawQuery.isNullOrBlank()) return emptyMap()
    return buildMap<String, MutableList<String>> {
        rawQuery.split('&').forEach { part ->
            val key = part.substringBefore('=').percentDecodedOnce().normalizedOption()
            val value = part.substringAfter('=', "").percentDecodedOnce()
            getOrPut(key) { mutableListOf() } += value
        }
    }
}

private fun Map<String, List<String>>.firstValue(vararg names: String): String? =
    names.asSequence()
        .map(String::normalizedOption)
        .mapNotNull { name -> get(name)?.lastOrNull() }
        .firstOrNull()
        ?.takeIf(String::isNotBlank)

private fun Map<String, List<String>>.values(vararg names: String): List<String> =
    names.flatMap { name -> get(name.normalizedOption()).orEmpty() }

private fun String.normalizedOption(): String =
    lowercase().replace("_", "").replace("-", "")

private fun String.percentDecodedOnce(): String =
    URLDecoder.decode(replace("+", "%2B"), Charsets.UTF_8.name())

private fun splitCommaValues(value: String): List<String> =
    value.split(',').map(String::trim).filter(String::isNotBlank)

private fun requireWireGuardKey(value: String?, label: String) {
    require(!value.isNullOrBlank()) { "$label is required" }
    val padded = value + "=".repeat((4 - value.length % 4) % 4)
    val decoded = sequenceOf(Base64.Default, Base64.UrlSafe)
        .mapNotNull { decoder -> runCatching { decoder.decode(padded) }.getOrNull() }
        .firstOrNull()
    require(decoded?.size == WireGuardKeyBytes) { "$label is invalid" }
}

private fun requireCidr(value: String) {
    val separator = value.lastIndexOf('/')
    require(separator > 0) { "WireGuard address must use CIDR notation" }
    val address = value.substring(0, separator)
    val prefix = value.substring(separator + 1).toIntOrNull()
        ?: error("WireGuard CIDR prefix is invalid")
    val numeric = if (':' in address) {
        runCatching { InetAddress.getByName(address) as? Inet6Address }.getOrNull()
    } else {
        if (
            address.split('.').size == 4 &&
            address.split('.').all { part -> part.toIntOrNull() in 0..255 }
        ) {
            InetAddress.getByAddress(address.split('.').map(String::toInt).map(Int::toByte).toByteArray())
        } else {
            null
        }
    }
    require(numeric != null) { "WireGuard address is invalid" }
    val maximum = if (numeric is Inet6Address) 128 else 32
    require(prefix in 0..maximum) { "WireGuard CIDR prefix is invalid" }
}

private fun parseHostPort(value: String): Pair<String, Int> {
    val uri = URI("wg://$value")
    val host = uri.host
        ?.removeSurrounding("[", "]")
        ?.takeIf(String::isNotBlank)
        ?: error("WireGuard peer endpoint host is invalid")
    return host to uri.port.requirePort("WireGuard peer endpoint port")
}

private fun Int.requirePort(label: String): Int {
    require(this in 1..65_535) { "$label is invalid" }
    return this
}

private fun String.requireIntIn(label: String, minimum: Int, maximum: Int): Int {
    val parsed = toIntOrNull()
    require(parsed != null && parsed in minimum..maximum) { "$label is invalid" }
    return parsed
}

private fun parseReservedBytes(value: String): List<Int> {
    val values = splitCommaValues(value).mapNotNull(String::toIntOrNull)
    require(values.size == 3 && values.all { it in 0..255 }) {
        "WireGuard reserved must contain exactly three bytes"
    }
    return values
}

private fun Map<String, List<String>>.singleValue(key: String, label: String): String {
    val values = get(key).orEmpty()
    require(values.size == 1) { "$label must appear exactly once" }
    return values.single()
}

private fun Map<String, List<String>>.optionalSingleValue(key: String): String? {
    val values = get(key).orEmpty()
    require(values.size <= 1) { "WireGuard option must not be repeated" }
    return values.singleOrNull()
}

private data class WireGuardPeer(
    val address: String,
    val port: Int,
    val publicKey: String,
    val preSharedKey: String?,
    val allowedIps: List<String>,
    val keepalive: Int?,
    val reserved: List<Int>?,
)

private val AllowedWireGuardSchemes = setOf("wg", "wireguard")
private val RejectedWireGuardSchemes = setOf("awg", "warp")
private val RecognizedWireGuardSchemes = AllowedWireGuardSchemes + RejectedWireGuardSchemes
private val DefaultAllowedIps = listOf("0.0.0.0/0", "::/0")
private const val WireGuardKeyBytes = 32
private val AwgOnlyFields = setOf(
    "jc", "jmin", "jmax", "s1", "s2", "h1", "h2", "h3", "h4",
)
private val WireGuardUrlFields = setOf(
    "privatekey",
    "pk",
    "publickey",
    "peerpublickey",
    "pub",
    "peerpub",
    "address",
    "ip",
    "allowedips",
    "localaddress",
    "presharedkey",
    "psk",
    "keepalive",
    "persistentkeepalive",
    "reserved",
    "workers",
    "mtu",
    "listenport",
) + AwgOnlyFields
