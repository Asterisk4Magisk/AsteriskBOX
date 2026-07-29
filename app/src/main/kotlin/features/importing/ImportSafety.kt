// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.importing

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.URI
import java.nio.charset.StandardCharsets

internal const val MaxImportBytes = 8 * 1024 * 1024
internal const val MaxImportCandidates = 10_000
internal const val MaxPersistedImportSummaryCharacters = 512
internal const val MaxImportErrorPreviewBytes = 64 * 1024

internal class ImportLimitException(
    val reason: ImportIssueReason,
    message: String,
) : IllegalArgumentException(message)

internal fun requireImportTextWithinLimit(
    content: String,
    maxBytes: Int = MaxImportBytes,
): String {
    if (content.toByteArray(StandardCharsets.UTF_8).size > maxBytes) {
        throw ImportLimitException(
            reason = ImportIssueReason.INPUT_TOO_LARGE,
            message = "Import content exceeds the allowed size",
        )
    }
    return content
}

internal fun requireImportCandidateCount(
    count: Int,
    maxCandidates: Int = MaxImportCandidates,
) {
    require(count >= 0) { "Candidate count must not be negative" }
    if (count > maxCandidates) {
        throw ImportLimitException(
            reason = ImportIssueReason.TOO_MANY_CANDIDATES,
            message = "Import contains too many candidates",
        )
    }
}

internal fun InputStream.readImportUtf8WithinLimit(
    maxBytes: Int = MaxImportBytes,
): String {
    val output = ByteArrayOutputStream(minOf(maxBytes, ImportReadBufferBytes))
    val buffer = ByteArray(ImportReadBufferBytes)
    var total = 0
    while (true) {
        val read = read(buffer)
        if (read < 0) break
        total += read
        if (total > maxBytes) {
            throw ImportLimitException(
                reason = ImportIssueReason.INPUT_TOO_LARGE,
                message = "Import content exceeds the allowed size",
            )
        }
        output.write(buffer, 0, read)
    }
    return output.toString(StandardCharsets.UTF_8.name())
}

internal fun sanitizeImportMessage(message: String): String {
    val withoutUrlSecrets = UrlPattern.replace(message) { match ->
        sanitizeUrl(match.value)
    }
    return SecretAssignmentPattern.replace(withoutUrlSecrets) { match ->
        "${match.groupValues[1]}[redacted]"
    }
}

internal fun sanitizePersistedImportSummary(message: String): String =
    sanitizeImportMessage(message).take(MaxPersistedImportSummaryCharacters)

internal fun sanitizeImportErrorPreview(message: String): String =
    sanitizeImportMessage(message.truncateUtf8(MaxImportErrorPreviewBytes))

private fun sanitizeUrl(value: String): String {
    return runCatching {
        val uri = URI(value)
        val host = uri.host ?: return@runCatching value
        URI(
            uri.scheme,
            null,
            host,
            uri.port,
            uri.rawPath,
            null,
            null,
        ).toASCIIString()
    }.getOrElse {
        value
            .substringBefore('?')
            .substringBefore('#')
            .replace(UrlUserInfoPattern, "$1")
    }
}

private fun String.truncateUtf8(maxBytes: Int): String {
    val bytes = toByteArray(StandardCharsets.UTF_8)
    if (bytes.size <= maxBytes) return this
    var end = maxBytes
    while (end > 0 && bytes[end].toInt() and 0xC0 == 0x80) {
        end -= 1
    }
    return String(bytes, 0, end, StandardCharsets.UTF_8)
}

private val UrlPattern =
    Regex("""(?i)\b[a-z][a-z0-9+.-]*://[^\s<>"']+""")

private val UrlUserInfoPattern =
    Regex("""(?i)(://)[^/@\s]+@""")

private val SecretAssignmentPattern = Regex(
    """(?i)(["']?\b(?:private[_-]?key|password|passwd|token|cookie|authorization|secret|psk)\b["']?\s*[=:]\s*)(?:"[^"]*"|'[^']*'|[^\s,;}]+)""",
)

private const val ImportReadBufferBytes = 8 * 1024
