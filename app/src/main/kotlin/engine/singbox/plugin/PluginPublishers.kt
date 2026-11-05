// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package engine.singbox.plugin

import android.content.pm.PackageManager
import android.content.pm.ProviderInfo
import android.content.pm.Signature
import android.os.Build
import engine.singbox.sha256Hex

/**
 * Plugin provider families AsteriskBOX accepts. Authority prefix plus signing
 * certificate pinning is the whole trust decision: a plugin binary runs as a
 * child of the core, so an untrusted provider would be arbitrary code
 * execution. Hashes are the same pins husi ships, so plugin APKs published by
 * those projects keep working.
 */
internal enum class PluginPublisher(
    val authoritiesPrefix: String,
    val trustedCertificates: Set<String>,
    val displayName: String,
) {
    HUSI(
        "fr.husi.plugin.",
        setOf("55d8507c1b28d64236004ebb5da704e37ace3f102e9f90c6e4ec4182cd390e9e"),
        "husi",
    ),
    EXCLAVE(
        "com.github.dyhkwong.",
        setOf("e9fe39e1ce254c50c2f9470a757b378c0b7cc536119867f7691405b592e6994b"),
        "Exclave",
    ),
    SAGERNET(
        "io.nekohasekai.sagernet.plugin.",
        setOf(
            SagerNetCertificate,
            KlzgradCertificate,
        ),
        "SagerNet",
    ),
    MATSURI(
        "moe.matsuri.exe.",
        setOf("35762758ce86a6ec297d9ccac689469bc43b9fed8ae1b27f100a86bbac00a055"),
        "Matsuri",
    ),
    ;

    companion object {
        fun ofAuthority(authority: String): PluginPublisher? =
            entries.firstOrNull { publisher -> authority.startsWith(publisher.authoritiesPrefix) }
    }
}

/** SagerNet's own plugin signing key. */
internal const val SagerNetCertificate =
    "32250a4b5f3a6733df57a3b9ec16c38d2c7fc5f2f693a9636f8f7b3be3549641"

/**
 * klzgrad publishes the NaiveProxy plugin under the SagerNet authority prefix but
 * signs it with a separate key; show that name instead of "SagerNet".
 */
internal const val KlzgradCertificate =
    "b7baa7fb895988eb731adb41882b53ade139f77de537b7c011ec33bd9677cbae"

/**
 * Plugin ids a certificate may provide. A NaiveProxy plugin signed by klzgrad
 * must not be able to claim, say, `mieru-plugin`; null means "any supported id".
 */
internal fun PluginPublisher.allowedPluginIds(certificates: Set<String>): Set<String>? = when {
    KlzgradCertificate in certificates -> setOf("naive-plugin")
    this == PluginPublisher.SAGERNET -> setOf("naive-plugin", "hysteria2-plugin")
    else -> null
}

@Suppress("DEPRECATION")
internal fun ProviderInfo.loadMetadataString(packageManager: PackageManager): String? =
    when (val value = metaData?.get(PluginContract.MetadataKeyId)) {
        is String -> value
        is Int -> runCatching {
            packageManager.getResourcesForApplication(applicationInfo).getString(value)
        }.getOrNull()
        else -> null
    }

internal data class SignedPackage(
    val versionName: String,
    /** SHA-256 of the certificates that signed the current APK. */
    val certificates: Set<String>,
)

internal fun PackageManager.signedPackage(packageName: String): SignedPackage? {
    val info = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        } else {
            @Suppress("DEPRECATION")
            getPackageInfo(packageName, PackageManager.GET_SIGNATURES)
        }
    } catch (_: PackageManager.NameNotFoundException) {
        return null
    }
    val signatures: Array<Signature> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        // Only the certificates that signed this APK: a rotated-out history
        // entry or an extra attacker signer must not be enough.
        info.signingInfo?.apkContentsSigners
    } else {
        @Suppress("DEPRECATION")
        info.signatures
    } ?: emptyArray()
    return SignedPackage(
        versionName = info.versionName.orEmpty(),
        certificates = signatures.mapTo(HashSet()) { signature -> signature.toByteArray().sha256Hex() },
    )
}
