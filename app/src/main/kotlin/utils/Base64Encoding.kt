// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package utils

import kotlin.io.encoding.Base64

internal fun ByteArray.encodeBase64(): String {
    return Base64.encode(this)
}

internal fun String.encodeBase64(): String {
    return toByteArray(Charsets.UTF_8).encodeBase64()
}

internal fun String.decodeBase64OrNull(): ByteArray? {
    return runCatching {
        Base64.decode(this)
    }.getOrNull()
}

internal fun String.decodeBase64StringOrNull(): String? {
    return decodeBase64OrNull()?.toString(Charsets.UTF_8)
}

internal fun String.decodePaddedBase64OrNull(): ByteArray? {
    val padded = trim().filterNot(Char::isWhitespace).withBase64Padding()
    return sequenceOf(Base64.UrlSafe, Base64.Default).firstNotNullOfOrNull { decoder ->
        runCatching { decoder.decode(padded) }.getOrNull()
    }
}

/** Append the `=` padding a strict decoder needs; the alphabet is left untouched. */
internal fun String.withBase64Padding(): String = this + "=".repeat((4 - length % 4) % 4)
