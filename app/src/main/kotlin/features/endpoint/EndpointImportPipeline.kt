// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.endpoint

import features.importing.ImportFormat
import features.importing.ImportIssue
import features.importing.ImportIssueReason
import features.importing.ImportIssueSeverity
import features.importing.ImportOutcome
import features.importing.ImportStage
import features.importing.requireImportTextWithinLimit

internal enum class EndpointImportFormat(
    override val id: String,
) : ImportFormat {
    JSON("json"),
    WIREGUARD_URL("wireguard_url"),
    WIREGUARD_CONFIG("wireguard_config"),
    OPENVPN("openvpn"),
    OPENCONNECT("openconnect"),
}

internal data class RecognizedEndpointImport(
    val outcome: ImportOutcome<ImportedSingBoxEndpoint>,
)

internal object EndpointImportPipeline {
    fun parseOutcome(content: String): ImportOutcome<ImportedSingBoxEndpoint> {
        require(content.isNotBlank()) { "Endpoint import content is empty" }
        val candidate = requireImportTextWithinLimit(content).trim().removePrefix("\uFEFF")
        if (candidate.looksLikeJsonDocument()) {
            return runCatching {
                SingBoxEndpointImporter.parseImportOutcome(candidate)
            }.getOrElse {
                ImportOutcome(
                    format = EndpointImportFormat.JSON,
                    detectedCount = 0,
                    accepted = emptyList(),
                    issues = listOf(
                        ImportIssue(
                            reason = ImportIssueReason.INVALID_DOCUMENT,
                            severity = ImportIssueSeverity.ERROR,
                            stage = ImportStage.PARSE,
                            message = "Invalid sing-box endpoint JSON document",
                        ),
                    ),
                )
            }
        }
        WireGuardEndpointParser.parseUrlOutcomeOrNull(candidate)?.let { return it.outcome }
        WireGuardEndpointParser.parseConfigOutcomeOrNull(candidate)?.let { return it.outcome }
        OpenVpnEndpointParser.parseOutcomeOrNull(candidate)?.let { return it.outcome }
        OpenConnectEndpointParser.parseOutcomeOrNull(candidate)?.let { return it.outcome }
        throw IllegalArgumentException("No supported endpoint format found")
    }
}

private fun String.looksLikeJsonDocument(): Boolean {
    if (firstOrNull() == '{') return true
    if (firstOrNull() != '[') return false
    val nextToken = drop(1).firstOrNull { character -> !character.isWhitespace() }
        ?: return true
    return nextToken in setOf('{', '[', '"', ']', '-', 't', 'f', 'n') ||
        nextToken.isDigit()
}
