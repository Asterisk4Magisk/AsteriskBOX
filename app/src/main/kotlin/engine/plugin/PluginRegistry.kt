// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package engine.plugin

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import java.io.File

/**
 * Discovers installed plugin packages. The binary lives in the plugin APK's
 * own native library directory, so nothing is copied: the core execs that path
 * directly (plugins must be packaged with jniLibs.useLegacyPackaging = true so
 * the .so is extracted and executable).
 */
internal object PluginRegistry {

    fun installedPlugins(context: Context): List<InstalledPlugin> {
        val packageManager = context.packageManager
        return candidatePackages(packageManager)
            .mapNotNull { info -> info.asInstalledPlugin(packageManager) }
            .groupBy { plugin -> plugin.pluginId }
            .values
            .map { candidates -> preferred(candidates) }
            .sortedBy { plugin -> plugin.pluginId }
    }

    fun findPlugin(context: Context, pluginId: String): InstalledPlugin =
        installedPlugins(context).firstOrNull { plugin -> plugin.pluginId == pluginId }
            ?: throw PluginNotFoundException(pluginId)

    /**
     * Ask the package manager for packages that answer the plugin action first;
     * a full scan only happens when that comes back empty, so the common path
     * does not walk every installed package (and stays fast enough for the UI
     * thread's callers to keep it off the main thread).
     */
    private fun candidatePackages(packageManager: PackageManager): List<PackageInfo> {
        val flags = PackageManager.GET_PROVIDERS or PackageManager.GET_META_DATA
        val fromProviders = runCatching {
            packageManager.queryIntentContentProviders(Intent(PluginContract.ActionNativePlugin), 0)
                .mapNotNull { provider -> provider.providerInfo?.packageName }
                .distinct()
                .mapNotNull { name ->
                    runCatching { packageManager.getPackageInfo(name, flags) }.getOrNull()
                }
        }.getOrDefault(emptyList())
        if (fromProviders.isNotEmpty()) {
            return fromProviders
        }
        return runCatching { packageManager.getInstalledPackages(flags) }.getOrDefault(emptyList())
    }

    private fun preferred(candidates: List<InstalledPlugin>): InstalledPlugin =
        candidates.firstOrNull { plugin -> plugin.publisher == PluginPublisher.HUSI.displayName }
            ?: candidates.minBy { plugin -> plugin.packageName }

    private fun PackageInfo.asInstalledPlugin(packageManager: PackageManager): InstalledPlugin? {
        val provider = pluginProvider() ?: return null
        val authority = provider.authority?.takeIf(String::isNotBlank) ?: return null
        val publisher = PluginPublisher.ofAuthority(authority) ?: return null
        val pluginId = provider.loadMetadataString(packageManager)
            ?.takeIf(String::isNotBlank)
            ?: return null
        // Only plugin ids this build can actually use.
        if (PluginOutbounds.all.none { requirement -> requirement.pluginId == pluginId }) {
            return null
        }
        val certificates = packageManager.signingCertificates(packageName)
        if (certificates.isEmpty() || !publisher.trustedCertificates.containsAll(certificates)) {
            return null
        }
        val allowed = publisher.allowedPluginIds(certificates)
        if (allowed != null && pluginId !in allowed) {
            return null
        }
        val matchedCertificate = certificates.first()
        val publisherName = if (publisher == PluginPublisher.SAGERNET &&
            matchedCertificate == KlzgradCertificate
        ) {
            "klzgrad"
        } else {
            publisher.displayName
        }
        val executable = provider.executablePath() ?: return null
        val versionCode = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            longVersionCode
        } else {
            @Suppress("DEPRECATION")
            versionCode.toLong()
        }
        return InstalledPlugin(
            pluginId = pluginId,
            packageName = packageName,
            versionName = versionName ?: "unknown",
            versionCode = versionCode,
            publisher = publisherName,
            executable = executable,
        )
    }

    private fun android.content.pm.ProviderInfo.executablePath(): File? {
        val relative = metaData?.getString(PluginContract.MetadataKeyExecutablePath)
            ?.takeIf(String::isNotBlank)
            ?: return null
        val nativeLibraryDir = applicationInfo?.nativeLibraryDir ?: return null
        // Resolve first, then verify the result is still inside the plugin's own
        // native library directory: the metadata string is attacker-controlled.
        val root = runCatching { File(nativeLibraryDir).canonicalFile }.getOrNull() ?: return null
        val executable = runCatching { File(root, relative).canonicalFile }.getOrNull() ?: return null
        // The binary is a direct child of the plugin's native library directory;
        // anything that resolves outside it (via "..", symlinks, an absolute
        // path in the metadata) is rejected.
        if (!executable.path.startsWith(root.path + File.separator)) {
            return null
        }
        return executable.takeIf { file -> file.isFile && file.canExecute() }
    }
}
