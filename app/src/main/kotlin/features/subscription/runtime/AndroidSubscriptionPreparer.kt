// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.subscription.runtime

import android.os.Build
import android.util.Base64
import app.SubscriptionInfo
import engine.singbox.config.SingBoxConfigChecker
import features.subscription.usecase.SubscriptionSyncStage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.nio.charset.StandardCharsets
import kage.Age
import kage.crypto.x25519.X25519Identity
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal sealed interface AndroidSubscriptionPreparation {
    data class Success(
        val content: String,
        val subscriptionInfo: SubscriptionInfo,
        val updateIntervalMillis: Long? = null,
    ) : AndroidSubscriptionPreparation

    data class Failure(
        val stage: SubscriptionSyncStage,
        val error: Throwable,
        val content: String? = null,
        val subscriptionInfo: SubscriptionInfo? = null,
        val updateIntervalMillis: Long? = null,
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
        verifyConfiguration: Boolean = true,
        onStage: (SubscriptionSyncStage) -> Unit = {},
    ): AndroidSubscriptionPreparation = subscriptionFetchLock.withLock {
        var stage = if (sourceContent == null) {
            SubscriptionSyncStage.Downloading
        } else {
            SubscriptionSyncStage.Decrypting
        }
        var downloaded: DownloadedSubscription? = null
        var decryptedContent: String? = null
        try {
            val source = if (sourceContent == null) {
                onStage(SubscriptionSyncStage.Downloading)
                stage = SubscriptionSyncStage.Downloading
                withContext(Dispatchers.IO) {
                    downloadSubscription(
                        sourceUrl = sourceUrl,
                        userAgent = userAgent,
                        hwid = fetchOptions.hwid.trim().ifBlank { installationHwid },
                        proxy = fetchOptions.toHttpProxy(),
                    )
                }.also { downloaded = it }.content
            } else {
                sourceContent
            }

            onStage(SubscriptionSyncStage.Decrypting)
            stage = SubscriptionSyncStage.Decrypting
            decryptedContent = withContext(Dispatchers.Default) {
                source.decryptAgeIfNeeded(ageSecretKey)
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
                updateIntervalMillis = downloaded?.updateIntervalMillis,
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            AndroidSubscriptionPreparation.Failure(
                stage = stage,
                error = error,
                content = decryptedContent,
                subscriptionInfo = downloaded?.subscriptionInfo,
                updateIntervalMillis = downloaded?.updateIntervalMillis,
            )
        }
    }
}

private data class DownloadedSubscription(
    val content: String,
    val subscriptionInfo: SubscriptionInfo,
    val updateIntervalMillis: Long?,
)

private fun downloadSubscription(
    sourceUrl: String,
    userAgent: String,
    hwid: String,
    proxy: AndroidSubscriptionProxy?,
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
        proxy?.basicAuthorizationOrNull()?.let { authorization ->
            connection.setRequestProperty("Proxy-Authorization", authorization)
        }
        val responseCode = connection.responseCode
        if (responseCode !in 200..299) {
            val message = connection.errorStream
                ?.bufferedReader(StandardCharsets.UTF_8)
                ?.use { it.readText() }
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
        val content = connection.inputStream
            .bufferedReader(StandardCharsets.UTF_8)
            .use { it.readText() }
        return DownloadedSubscription(
            content = content,
            subscriptionInfo = connection
                .getHeaderField(SubscriptionUserInfoHeader)
                .toSubscriptionInfo(),
            updateIntervalMillis = connection
                .getHeaderField(ProfileUpdateIntervalHeader)
                .toUpdateIntervalMillisOrNull(),
        )
    } finally {
        connection.disconnect()
    }
}

private fun AndroidSubscriptionProxy.basicAuthorizationOrNull(): String? {
    if (username.isBlank() && password.isBlank()) return null
    val credentials = "$username:$password".toByteArray(StandardCharsets.UTF_8)
    return "Basic ${Base64.encodeToString(credentials, Base64.NO_WRAP)}"
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

private fun String?.toUpdateIntervalMillisOrNull(): Long? {
    return this
        ?.trim()
        ?.toLongOrNull()
        ?.takeIf { hours -> hours > 0L }
        ?.let { hours -> Math.multiplyExact(hours, MillisPerHour) }
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
    check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        "AGE encrypted subscriptions require Android 8.0 or newer"
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
private const val ProfileUpdateIntervalHeader = "profile-update-interval"
private const val DefaultUserAgent = "sing-box"
private const val AgeSecretKeyPrefix = "AGE-SECRET-KEY-"
private const val AgeHeader = "age-encryption.org/v1"
private const val ArmoredAgeHeader = "-----BEGIN AGE ENCRYPTED FILE-----"
private const val ConnectTimeoutMillis = 15_000
private const val ReadTimeoutMillis = 30_000
private const val MillisPerHour = 60L * 60L * 1000L

private val subscriptionFetchLock = Mutex()
