// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package engine.singbox.plugin

import engine.singbox.config.SingBoxJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import java.io.ByteArrayOutputStream
import java.util.Base64

/**
 * Mieru traffic pattern codec. Share links carry `traffic-pattern` as standard
 * base64 of the protobuf `TrafficPattern` message (mieru pkg/appctl/url.go),
 * while the client JSON config takes its protojson form. The editor stores the
 * JSON form.
 */

/** The protojson object for [raw], which is either that JSON or a share-link base64 string. */
internal fun decodeMieruTrafficPattern(raw: String): JsonObject? {
    val text = raw.trim()
    if (text.isEmpty()) return null
    if (text.startsWith('{')) {
        val parsed = runCatching { SingBoxJson.parseToJsonElement(text) }.getOrNull() as? JsonObject
            ?: return null
        return (parsed["trafficPattern"] as? JsonObject) ?: parsed
    }
    val bytes = runCatching {
        // A '+' turns into a space when a link is query-decoded without escaping.
        val normalized = text.replace(' ', '+').replace('-', '+').replace('_', '/')
        val padded = normalized + "=".repeat((4 - normalized.length % 4) % 4)
        Base64.getDecoder().decode(padded)
    }.getOrNull() ?: return null
    return runCatching { ProtoReader(bytes).readMessage(TrafficPatternSchema) }.getOrNull()
}

/** Share-link form of [raw] (JSON or base64), or null when it is not a valid pattern. */
internal fun encodeMieruTrafficPatternBase64(raw: String): String? {
    val pattern = decodeMieruTrafficPattern(raw) ?: return null
    val bytes = runCatching { encodeMessage(pattern, TrafficPatternSchema) }.getOrNull() ?: return null
    return Base64.getEncoder().encodeToString(bytes)
}

private sealed interface ProtoKind {
    data object Int32 : ProtoKind
    data object Bool : ProtoKind
    data object RepeatedString : ProtoKind
    class Enum(val names: Map<Int, String>) : ProtoKind
    class Message(val schema: () -> ProtoSchema) : ProtoKind
}

private class ProtoField(val number: Int, val name: String, val kind: ProtoKind)

private class ProtoSchema(fields: List<ProtoField>) {
    val byNumber = fields.associateBy(ProtoField::number)
    val byName = fields.associateBy(ProtoField::name)
}

private val NonceTypes = mapOf(
    0 to "NONCE_TYPE_RANDOM",
    1 to "NONCE_TYPE_PRINTABLE",
    2 to "NONCE_TYPE_PRINTABLE_SUBSET",
    3 to "NONCE_TYPE_FIXED",
)

private val LowEntropyModes = mapOf(
    0 to "LOW_ENTROPY_MODE_OFF",
    1 to "LOW_ENTROPY_MODE_32",
    2 to "LOW_ENTROPY_MODE_40",
    3 to "LOW_ENTROPY_MODE_48",
    4 to "LOW_ENTROPY_MODE_56",
)

private val LowEntropyMaskRotations = buildMap {
    put(0, "LOW_ENTROPY_MASK_NO_ROTATION")
    (1..15).forEach { n ->
        put(n, "LOW_ENTROPY_MASK_ROTATE_RIGHT_$n")
        put(16 * n, "LOW_ENTROPY_MASK_ROTATE_LEFT_$n")
    }
}

private val TcpFragmentSchema = ProtoSchema(
    listOf(
        ProtoField(1, "enable", ProtoKind.Bool),
        ProtoField(2, "maxSleepMs", ProtoKind.Int32),
    ),
)

private val NonceSchema = ProtoSchema(
    listOf(
        ProtoField(1, "type", ProtoKind.Enum(NonceTypes)),
        ProtoField(2, "applyToAllUDPPacket", ProtoKind.Bool),
        ProtoField(3, "minLen", ProtoKind.Int32),
        ProtoField(4, "maxLen", ProtoKind.Int32),
        ProtoField(5, "customHexStrings", ProtoKind.RepeatedString),
    ),
)

private val PaddingSchema = ProtoSchema(
    listOf(
        ProtoField(1, "maxMiddlePaddingLen", ProtoKind.Int32),
        ProtoField(2, "maxEndPaddingLen", ProtoKind.Int32),
    ),
)

private val LowEntropySchema = ProtoSchema(
    listOf(
        ProtoField(1, "mode", ProtoKind.Enum(LowEntropyModes)),
        ProtoField(2, "maskRotation", ProtoKind.Enum(LowEntropyMaskRotations)),
    ),
)

private val TrafficPatternSchema = ProtoSchema(
    listOf(
        ProtoField(1, "seed", ProtoKind.Int32),
        ProtoField(2, "unlockAll", ProtoKind.Bool),
        ProtoField(3, "tcpFragment", ProtoKind.Message { TcpFragmentSchema }),
        ProtoField(4, "nonce", ProtoKind.Message { NonceSchema }),
        ProtoField(5, "padding", ProtoKind.Message { PaddingSchema }),
        ProtoField(6, "lowEntropy", ProtoKind.Message { LowEntropySchema }),
    ),
)

private class ProtoReader(private val bytes: ByteArray, private var position: Int = 0, private val end: Int = bytes.size) {

    fun readMessage(schema: ProtoSchema): JsonObject {
        val values = linkedMapOf<String, JsonElement>()
        val repeated = linkedMapOf<String, MutableList<JsonElement>>()
        while (position < end) {
            val key = readVarint()
            val number = (key ushr 3).toInt()
            val wireType = (key and 7).toInt()
            val field = schema.byNumber[number]
            if (field == null) {
                skip(wireType)
                continue
            }
            when (val kind = field.kind) {
                ProtoKind.Int32 -> values[field.name] = JsonPrimitive(expectVarint(wireType).toInt())
                ProtoKind.Bool -> values[field.name] = JsonPrimitive(expectVarint(wireType) != 0L)
                is ProtoKind.Enum -> {
                    val value = expectVarint(wireType).toInt()
                    values[field.name] = kind.names[value]?.let(::JsonPrimitive) ?: JsonPrimitive(value)
                }
                ProtoKind.RepeatedString -> {
                    val content = readLengthDelimited(wireType)
                    repeated.getOrPut(field.name) { mutableListOf() } +=
                        JsonPrimitive(String(content, Charsets.UTF_8))
                }
                is ProtoKind.Message -> {
                    val content = readLengthDelimited(wireType)
                    values[field.name] = ProtoReader(content).readMessage(kind.schema())
                }
            }
        }
        check(position == end) { "truncated protobuf message" }
        repeated.forEach { (name, items) -> values[name] = JsonArray(items) }
        return JsonObject(values)
    }

    private fun expectVarint(wireType: Int): Long {
        check(wireType == 0) { "unexpected wire type $wireType" }
        return readVarint()
    }

    private fun readLengthDelimited(wireType: Int): ByteArray {
        check(wireType == 2) { "unexpected wire type $wireType" }
        val length = readVarint()
        check(length >= 0 && length <= end - position) { "truncated protobuf field" }
        val content = bytes.copyOfRange(position, position + length.toInt())
        position += length.toInt()
        return content
    }

    private fun skip(wireType: Int) {
        when (wireType) {
            0 -> readVarint()
            1 -> advance(8)
            2 -> advance(readVarint().toInt())
            5 -> advance(4)
            else -> error("unsupported wire type $wireType")
        }
    }

    private fun advance(count: Int) {
        check(count >= 0 && count <= end - position) { "truncated protobuf field" }
        position += count
    }

    private fun readVarint(): Long {
        var result = 0L
        var shift = 0
        while (true) {
            check(position < end && shift < 64) { "truncated protobuf varint" }
            val byte = bytes[position++].toInt()
            result = result or ((byte and 0x7f).toLong() shl shift)
            if (byte and 0x80 == 0) return result
            shift += 7
        }
    }
}

private fun encodeMessage(message: JsonObject, schema: ProtoSchema): ByteArray {
    val output = ByteArrayOutputStream()
    // Field-number order, as proto.Marshal writes it.
    schema.byNumber.values.sortedBy(ProtoField::number).forEach { field ->
        val value = message[field.name] ?: return@forEach
        when (val kind = field.kind) {
            ProtoKind.Int32 -> output.writeVarintField(field.number, value.requireInt(field.name).toLong())
            ProtoKind.Bool -> output.writeVarintField(field.number, if (value.requireBoolean(field.name)) 1 else 0)
            is ProtoKind.Enum -> output.writeVarintField(field.number, kind.numberOf(value, field.name).toLong())
            ProtoKind.RepeatedString -> {
                val items = value as? JsonArray ?: error("${field.name} must be an array")
                items.forEach { item ->
                    val text = (item as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content
                        ?: error("${field.name} must contain strings")
                    output.writeBytesField(field.number, text.toByteArray(Charsets.UTF_8))
                }
            }
            is ProtoKind.Message -> {
                val nested = value as? JsonObject ?: error("${field.name} must be an object")
                output.writeBytesField(field.number, encodeMessage(nested, kind.schema()))
            }
        }
    }
    message.keys.firstOrNull { key -> key !in schema.byName }?.let { key -> error("unknown field $key") }
    return output.toByteArray()
}

private fun JsonElement.requireInt(name: String): Int =
    (this as? JsonPrimitive)?.let { primitive -> primitive.intOrNull ?: primitive.contentOrNull?.toIntOrNull() }
        ?: error("$name must be an integer")

private fun JsonElement.requireBoolean(name: String): Boolean =
    (this as? JsonPrimitive)?.booleanOrNull ?: error("$name must be a boolean")

private fun ProtoKind.Enum.numberOf(value: JsonElement, name: String): Int {
    val primitive = value as? JsonPrimitive ?: error("$name must be an enum")
    primitive.intOrNull?.let { number -> return number }
    val text = primitive.content
    return names.entries.firstOrNull { (_, label) -> label == text }?.key ?: error("$name has unknown value $text")
}

private fun ByteArrayOutputStream.writeVarintField(number: Int, value: Long) {
    writeVarint((number shl 3).toLong())
    writeVarint(value)
}

private fun ByteArrayOutputStream.writeBytesField(number: Int, content: ByteArray) {
    writeVarint(((number shl 3) or 2).toLong())
    writeVarint(content.size.toLong())
    write(content)
}

// Negative int32 values are sign-extended to ten bytes, as protobuf requires.
private fun ByteArrayOutputStream.writeVarint(value: Long) {
    var remaining = value
    while (true) {
        if (remaining and 0x7fL.inv() == 0L) {
            write(remaining.toInt())
            return
        }
        write(((remaining and 0x7f) or 0x80).toInt())
        remaining = remaining ushr 7
    }
}
