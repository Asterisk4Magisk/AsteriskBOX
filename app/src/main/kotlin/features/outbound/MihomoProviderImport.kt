// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.outbound

import features.importing.ImportIssue
import features.importing.ImportIssueReason
import features.importing.ImportIssueSeverity
import features.importing.ImportOutcome
import features.importing.ImportStage
import features.importing.requireImportCandidateCount
import features.importing.requireImportTextWithinLimit
import java.net.URI
import kotlin.coroutines.cancellation.CancellationException
import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings

internal data class MihomoProviderRequest(
    val url: String,
    val headers: Map<String, List<String>> = emptyMap(),
)

internal fun interface MihomoProviderFetcher {
    suspend fun fetch(request: MihomoProviderRequest): String
}

internal data class MihomoProviderImportContext(
    val fetcher: MihomoProviderFetcher? = null,
    val urls: Set<String> = emptySet(),
    val depth: Int = 0,
    val options: List<MihomoProviderOptions> = emptyList(),
)

/** Provider resources can change while the parent YAML and its HTTP validators stay the same. */
internal fun containsMihomoProviders(content: String): Boolean {
    val candidates = listOfNotNull(content, decodeBase64ImportPayload(content))
    return candidates.any { candidate ->
        runCatching {
            val root = Load(LoadSettings.builder().build()).loadFromString(candidate) as? Map<*, *>
            root?.containsKey("proxy-providers") == true
        }.getOrDefault(false)
    }
}

internal suspend fun importMihomoProviders(
    root: Map<*, *>,
    direct: ImportOutcome<ImportedSingBoxOutbound>,
    formatter: SingBoxOutboundConfigFormatter,
    context: MihomoProviderImportContext,
): ImportOutcome<ImportedSingBoxOutbound> {
    if ("proxy-providers" !in root) return direct
    val providers = root["proxy-providers"] as? Map<*, *>
        ?: return mergeProviderOutcomes(direct, listOf(providerFailure(
            ImportIssueReason.INVALID_DOCUMENT, "Mihomo proxy-providers must be a mapping",
        )))
    requireImportCandidateCount(providers.size)
    val results = mutableListOf<ImportOutcome<ImportedSingBoxOutbound>>()
    var detected = direct.detectedCount
    for (raw in providers.values) {
        val result = try {
            val provider = raw as? Map<*, *> ?: error("Provider must be a mapping")
            importMihomoProvider(provider, formatter, context)
        } catch (error: CancellationException) {
            throw error
        } catch (_: IllegalArgumentException) {
            providerFailure(ImportIssueReason.UNSUPPORTED_OPTION, "Invalid or unsupported Mihomo provider options")
        } catch (_: IllegalStateException) {
            providerFailure(ImportIssueReason.INVALID_ENTRY, "Invalid Mihomo provider entry")
        }
        detected += result.detectedCount
        requireImportCandidateCount(detected)
        results += result
    }
    return mergeProviderOutcomes(direct, results)
}

private suspend fun importMihomoProvider(
    provider: Map<*, *>,
    formatter: SingBoxOutboundConfigFormatter,
    context: MihomoProviderImportContext,
): ImportOutcome<ImportedSingBoxOutbound> {
    val options = MihomoProviderOptions.from(provider)
    val payload = provider["payload"] as? List<*>
    require(provider["payload"] == null || payload != null) { "Provider payload must be a list" }
    val inheritedOptions = listOf(options) + context.options
    fun inline() = MihomoYamlOutboundParser.parseProxies(payload.orEmpty(), inheritedOptions)
    return when (provider["type"]?.toString()?.lowercase()) {
        "inline" -> inline()
        "http" -> {
            val url = provider["url"]?.toString()?.trim().orEmpty()
            val uri = URI(url)
            require(uri.scheme?.lowercase() in setOf("http", "https") && !uri.host.isNullOrBlank()) {
                "Invalid provider URL"
            }
            val headers = providerHeaders(provider["header"])
            if (context.depth >= 4 || url in context.urls) {
                return providerFailure(ImportIssueReason.UNSAFE_EXTERNAL_REFERENCE, "Recursive Mihomo provider reference")
            }
            val fetcher = context.fetcher
                ?: return if (!payload.isNullOrEmpty()) inline() else providerFailure(
                    ImportIssueReason.NETWORK_ERROR, "Mihomo provider downloader is unavailable",
                )
            val fetched = try {
                requireImportTextWithinLimit(fetcher.fetch(MihomoProviderRequest(url, headers)))
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                return if (!payload.isNullOrEmpty()) inline() else providerFailure(
                    ImportIssueReason.NETWORK_ERROR, "Mihomo provider download failed",
                )
            }
            val imported = try {
                OutboundImportPipeline.parseOutcome(
                    fetched, formatter,
                    context.copy(urls = context.urls + url, depth = context.depth + 1, options = inheritedOptions),
                )
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                return if (!payload.isNullOrEmpty()) inline() else providerFailure(
                    ImportIssueReason.INVALID_DOCUMENT, "Invalid Mihomo provider document",
                )
            }
            if (imported.accepted.isEmpty() && !payload.isNullOrEmpty()) inline() else imported
        }
        else -> providerFailure(ImportIssueReason.UNSUPPORTED_TYPE, "Unsupported Mihomo provider type")
    }
}

private fun providerFailure(reason: ImportIssueReason, message: String) = ImportOutcome<ImportedSingBoxOutbound>(
    format = OutboundImportFormat.YAML,
    detectedCount = 1,
    accepted = emptyList(),
    issues = listOf(ImportIssue(reason, ImportIssueSeverity.ERROR, ImportStage.PARSE, message = message)),
)

private fun mergeProviderOutcomes(
    direct: ImportOutcome<ImportedSingBoxOutbound>,
    providers: List<ImportOutcome<ImportedSingBoxOutbound>>,
): ImportOutcome<ImportedSingBoxOutbound> {
    val all = listOf(direct) + providers
    var offset = 0
    val accepted = mutableListOf<ImportedSingBoxOutbound>()
    val issues = mutableListOf<ImportIssue>()
    for (result in all) {
        accepted += result.accepted.map { it.copy(sourceIndex = it.sourceIndex?.plus(offset)) }
        issues += result.issues.map { it.copy(sourceIndex = it.sourceIndex?.plus(offset)) }
        offset += result.detectedCount
    }
    return ImportOutcome(
        format = OutboundImportFormat.YAML,
        detectedCount = offset,
        accepted = accepted,
        duplicateCount = all.sumOf { it.duplicateCount },
        issues = issues,
        mutations = all.flatMap { it.mutations },
        priorOmittedDetailCount = all.sumOf { it.omittedDetailCount },
    )
}

internal fun providerHeaders(value: Any?): Map<String, List<String>> {
    if (value == null) return emptyMap()
    val header = value as? Map<*, *> ?: error("Provider header must be a mapping")
    return header.entries.associate { (rawName, rawValues) ->
        val name = rawName?.toString().orEmpty()
        require(name.matches(Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+"))) { "Invalid provider header name" }
        val values = if (rawValues is Iterable<*>) rawValues.toList() else listOf(rawValues)
        name to values.map {
            require(it is String || it is Number || it is Boolean) { "Invalid provider header value" }
            it.toString().also { text -> require('\r' !in text && '\n' !in text) { "Invalid provider header value" } }
        }
    }
}
