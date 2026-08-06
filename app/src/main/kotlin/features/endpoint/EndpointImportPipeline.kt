// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.endpoint

import features.importing.ImportFormat
import features.importing.ImportIssue
import features.importing.ImportIssueReason
import features.importing.ImportIssueSeverity
import features.importing.ImportMutation
import features.importing.ImportMutationCode
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
    fun parseFileOutcome(
        content: String,
        fileName: String?,
    ): ImportOutcome<ImportedSingBoxEndpoint> {
        val outcome = parseOutcome(content)
        val remarks = endpointImportRemarksFromFileName(fileName)
        if (remarks.isBlank() || outcome.accepted.size != 1) return outcome
        return ImportOutcome(
            format = outcome.format,
            detectedCount = outcome.detectedCount,
            accepted = listOf(outcome.accepted.single().copy(remarks = remarks)),
            duplicateCount = outcome.duplicateCount,
            issues = outcome.issues,
            mutations = outcome.mutations,
            priorOmittedDetailCount = outcome.omittedDetailCount,
        )
    }

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

internal fun endpointImportRemarksFromFileName(fileName: String?): String {
    val simpleName = fileName.orEmpty()
        .substringAfterLast('/')
        .substringAfterLast('\\')
        .trim()
    if (simpleName.isBlank()) return ""
    val extensionSeparator = simpleName.lastIndexOf('.')
    if (extensionSeparator <= 0) return simpleName
    return simpleName.substring(0, extensionSeparator).trim().ifBlank { simpleName }
}

internal fun ignoredEndpointOptionMutations(
    profile: String,
    optionNames: Iterable<String>,
): List<ImportMutation> = optionNames
    .map { it.trim().lowercase() }
    .filter(String::isNotBlank)
    .distinct()
    .map { option ->
        ImportMutation(
            code = ImportMutationCode.IGNORED_FIELD,
            message = "Ignored unsupported $profile option: $option",
        )
    }

private fun String.looksLikeJsonDocument(): Boolean {
    if (firstOrNull() == '{') return true
    if (firstOrNull() != '[') return false
    val nextToken = drop(1).firstOrNull { character -> !character.isWhitespace() }
        ?: return true
    return nextToken in setOf('{', '[', '"', ']', '-', 't', 'f', 'n') ||
        nextToken.isDigit()
}
