// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.resources.runtime

import features.resources.ResourceCatalogEntry
import features.resources.ResourceCatalogSource
import features.resources.ResourceFileUpdateOptions
import java.net.HttpURLConnection
import java.net.URI
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext

internal fun interface ResourceCatalogTextFetcher {
    suspend fun fetch(
        url: String,
        options: ResourceFileUpdateOptions,
        headers: Map<String, String>,
    ): String
}

internal class AndroidResourceCatalogRepository(
    private val textFetcher: ResourceCatalogTextFetcher = AndroidResourceCatalogTextFetcher,
) {
    suspend fun load(
        source: ResourceCatalogSource,
        options: ResourceFileUpdateOptions,
    ): List<ResourceCatalogEntry> {
        val json = textFetcher.fetch(
            url = source.apiUrl,
            options = options,
            headers = GitHubTreeRequestHeaders,
        )
        return parseResourceCatalog(source, json)
    }
}

internal object AndroidResourceCatalogTextFetcher : ResourceCatalogTextFetcher {
    override suspend fun fetch(
        url: String,
        options: ResourceFileUpdateOptions,
        headers: Map<String, String>,
    ): String = withContext(Dispatchers.IO) {
        val coroutineContext = currentCoroutineContext()
        val proxy = options.toHttpProxy()
        proxy.withAuthenticator {
            val connection = URI.create(url).toHttpConnection(proxy, headers)
            val cancellationHandle = coroutineContext[Job]?.invokeOnCompletion { cause ->
                if (cause is CancellationException) connection.disconnect()
            }
            try {
                connection.requireSuccessfulResponse()
                connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader -> reader.readText() }
            } finally {
                cancellationHandle?.dispose()
                connection.disconnect()
            }
        }
    }
}

private fun HttpURLConnection.requireSuccessfulResponse() {
    val code = responseCode
    if (code !in 200..299) error("HTTP $code")
}

private val GitHubTreeRequestHeaders = mapOf(
    "Accept" to "application/vnd.github+json",
    "X-GitHub-Api-Version" to "2026-03-10",
    "User-Agent" to "AsteriskBOX",
)
