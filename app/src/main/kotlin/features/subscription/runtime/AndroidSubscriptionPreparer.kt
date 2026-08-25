// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.subscription.runtime

import app.SubscriptionInfo
import engine.singbox.config.SingBoxConfigChecker
import features.importing.MaxImportErrorPreviewBytes
import features.importing.readImportUtf8WithinLimit
import features.importing.requireImportTextWithinLimit
import features.importing.sanitizeImportErrorPreview
import features.subscription.usecase.SubscriptionSyncStage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.nio.charset.StandardCharsets
import kage.Age
import kage.crypto.x25519.X25519Identity
import kotlin.coroutines.cancellation.CancellationException
import kotlin.io.encoding.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal sealed interface AndroidSubscriptionPreparation {
    data class Success(
        val content: String,
        val subscriptionInfo: SubscriptionInfo,
        val etag: String = "",
        val lastModified: String = "",
    ) : AndroidSubscriptionPreparation

    data class NotModified(
        val subscriptionInfo: SubscriptionInfo,
        val etag: String,
        val lastModified: String,
    ) : AndroidSubscriptionPreparation

    data class Failure(
        val stage: SubscriptionSyncStage,
        val error: Throwable,
        val content: String? = null,
        val subscriptionInfo: SubscriptionInfo? = null,
        val etag: String = "",
        val lastModified: String = "",
    ) : AndroidSubscriptionPreparation
}

internal class AndroidSubscriptionPreparer(
    private val installationHwid: String,
) {
    suspend fun prepare(
        sourceContent: String?,
        sourceUrl: String,
        userAgent: String,
        ageSecretKey: String,
        fetchOptions: AndroidSubscriptionFetchOptions,
        etag: String = "",
        lastModified: String = "",
        verifyConfiguration: Boolean = true,
        onStage: (SubscriptionSyncStage) -> Unit = {},
    ): AndroidSubscriptionPreparation {
        var stage = if (sourceContent == null) {
            SubscriptionSyncStage.Downloading
        } else {
            SubscriptionSyncStage.Decrypting
        }
        var downloaded: DownloadedSubscription? = null
        var decryptedContent: String? = null
        return try {
            val source = if (sourceContent == null) {
                onStage(SubscriptionSyncStage.Downloading)
                stage = SubscriptionSyncStage.Downloading
                withContext(Dispatchers.IO) {
                    downloadSubscription(
                        sourceUrl = sourceUrl,
                        userAgent = userAgent,
                        hwid = fetchOptions.hwid.trim().ifBlank { installationHwid },
                        proxy = fetchOptions.toHttpProxy(),
                        etag = etag,
                        lastModified = lastModified,
                    )
                }.also { result ->
                    downloaded = result
                    if (result is DownloadedSubscription.NotModified) {
                        return AndroidSubscriptionPreparation.NotModified(
                            subscriptionInfo = result.subscriptionInfo,
                            etag = result.etag,
                            lastModified = result.lastModified,
                        )
                    }
                }.let { result -> (result as DownloadedSubscription.Content).content }
            } else {
                requireImportTextWithinLimit(sourceContent)
            }

            onStage(SubscriptionSyncStage.Decrypting)
            stage = SubscriptionSyncStage.Decrypting
            decryptedContent = withContext(Dispatchers.Default) {
                requireImportTextWithinLimit(source.decryptAgeIfNeeded(ageSecretKey))
            }.takeIf(String::isNotBlank)
                ?: error("Configuration file is empty")

            if (verifyConfiguration) {
                onStage(SubscriptionSyncStage.Verifying)
                stage = SubscriptionSyncStage.Verifying
                withContext(Dispatchers.Default) {
                    SingBoxConfigChecker.check(decryptedContent)
                }
            }

            AndroidSubscriptionPreparation.Success(
                content = decryptedContent,
                subscriptionInfo = downloaded?.subscriptionInfo ?: SubscriptionInfo(),
                etag = downloaded?.etag.orEmpty(),
                lastModified = downloaded?.lastModified.orEmpty(),
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            AndroidSubscriptionPreparation.Failure(
                stage = stage,
                error = error,
                content = decryptedContent,
                subscriptionInfo = downloaded?.subscriptionInfo,
                etag = downloaded?.etag.orEmpty(),
                lastModified = downloaded?.lastModified.orEmpty(),
            )
        }
    }
}

private sealed interface DownloadedSubscription {
    val subscriptionInfo: SubscriptionInfo
    val etag: String
    val lastModified: String

    data class Content(
        val content: String,
        override val subscriptionInfo: SubscriptionInfo,
        override val etag: String,
        override val lastModified: String,
    ) : DownloadedSubscription

    data class NotModified(
        override val subscriptionInfo: SubscriptionInfo,
        override val etag: String,
        override val lastModified: String,
    ) : DownloadedSubscription
}

private fun downloadSubscription(
    sourceUrl: String,
    userAgent: String,
    hwid: String,
    proxy: AndroidSubscriptionProxy?,
    etag: String,
    lastModified: String,
): DownloadedSubscription {
    val url = URI(sourceUrl).toURL()
    val connection = (
        if (proxy == null) url.openConnection() else url.openConnection(proxy.proxy)
    ) as HttpURLConnection
    try {
        connection.instanceFollowRedirects = true
        connection.connectTimeout = ConnectTimeoutMillis
        connection.readTimeout = ReadTimeoutMillis
        connection.setRequestProperty("Accept", "application/json, text/plain, */*")
        connection.setRequestProperty("User-Agent", userAgent.ifBlank { DefaultUserAgent })
        connection.setRequestProperty("X-Hwid", hwid)
        etag.takeIf(String::isNotBlank)?.let { value ->
            connection.setRequestProperty("If-None-Match", value)
        }
        lastModified.takeIf(String::isNotBlank)?.let { value ->
            connection.setRequestProperty("If-Modified-Since", value)
        }
        proxy?.basicAuthorizationOrNull()?.let { authorization ->
            connection.setRequestProperty("Proxy-Authorization", authorization)
        }
        val responseCode = connection.responseCode
        val responseEtag = connection.getHeaderField(EtagHeader).orEmpty().ifBlank { etag }
        val responseLastModified = connection
            .getHeaderField(LastModifiedHeader)
            .orEmpty()
            .ifBlank { lastModified }
        val subscriptionInfo = connection
            .getHeaderField(SubscriptionUserInfoHeader)
            .toSubscriptionInfo()
        if (responseCode == HttpURLConnection.HTTP_NOT_MODIFIED) {
            return DownloadedSubscription.NotModified(
                subscriptionInfo = subscriptionInfo,
                etag = responseEtag,
                lastModified = responseLastModified,
            )
        }
        if (responseCode !in 200..299) {
            val message = connection.errorStream
                ?.use { it.readUtf8Preview(MaxImportErrorPreviewBytes) }
                ?.let(::sanitizeImportErrorPreview)
                ?.trim()
                .orEmpty()
            error(
                buildString {
                    append("Subscription request failed: HTTP ")
                    append(responseCode)
                    if (message.isNotBlank()) append(" ($message)")
                },
            )
        }
        val content = connection.inputStream.use(InputStream::readImportUtf8WithinLimit)
        return DownloadedSubscription.Content(
            content = content,
            subscriptionInfo = subscriptionInfo,
            etag = responseEtag,
            lastModified = responseLastModified,
        )
    } finally {
        connection.disconnect()
    }
}

private fun AndroidSubscriptionProxy.basicAuthorizationOrNull(): String? {
    if (username.isBlank() && password.isBlank()) return null
    val credentials = "$username:$password".toByteArray(StandardCharsets.UTF_8)
    return "Basic ${Base64.Default.encode(credentials)}"
}

private fun String?.toSubscriptionInfo(): SubscriptionInfo {
    val values = orEmpty()
        .split(';')
        .mapNotNull { item ->
            val separator = item.indexOf('=')
            if (separator <= 0) return@mapNotNull null
            item.substring(0, separator).trim().lowercase() to
                item.substring(separator + 1).trim().toLongOrNull()
        }
        .toMap()
    return SubscriptionInfo(
        uploadBytes = values["upload"]?.coerceAtLeast(0L) ?: 0L,
        downloadBytes = values["download"]?.coerceAtLeast(0L) ?: 0L,
        totalBytes = values["total"]?.coerceAtLeast(0L) ?: 0L,
        expireAtSeconds = values["expire"]?.coerceAtLeast(0L) ?: 0L,
    )
}

private fun InputStream.readUtf8Preview(maxBytes: Int): String {
    val output = ByteArrayOutputStream(minOf(maxBytes, DefaultReadBufferBytes))
    val buffer = ByteArray(DefaultReadBufferBytes)
    var remaining = maxBytes
    while (remaining > 0) {
        val read = read(buffer, 0, minOf(buffer.size, remaining))
        if (read < 0) break
        output.write(buffer, 0, read)
        remaining -= read
    }
    return output.toString(StandardCharsets.UTF_8.name())
}

private fun String.decryptAgeIfNeeded(ageSecretKey: String): String {
    val key = ageSecretKey
        .lineSequence()
        .map(String::trim)
        .firstOrNull { line -> line.startsWith(AgeSecretKeyPrefix) }
        ?: return this
    val trimmed = trimStart()
    if (
        !trimmed.startsWith(AgeHeader) &&
        !trimmed.startsWith(ArmoredAgeHeader)
    ) {
        return this
    }
    val decrypted = ByteArrayOutputStream()
    Age.decryptStream(
        identities = listOf(X25519Identity.decode(key)),
        srcStream = ByteArrayInputStream(toByteArray(StandardCharsets.UTF_8)),
        dstStream = decrypted,
    )
    return decrypted.toString(StandardCharsets.UTF_8.name())
}

private const val SubscriptionUserInfoHeader = "subscription-userinfo"
private const val EtagHeader = "ETag"
private const val LastModifiedHeader = "Last-Modified"
private const val DefaultUserAgent = "sing-box"
private const val AgeSecretKeyPrefix = "AGE-SECRET-KEY-"
private const val AgeHeader = "age-encryption.org/v1"
private const val ArmoredAgeHeader = "-----BEGIN AGE ENCRYPTED FILE-----"
private const val ConnectTimeoutMillis = 15_000
private const val ReadTimeoutMillis = 30_000
private const val DefaultReadBufferBytes = 8 * 1024
