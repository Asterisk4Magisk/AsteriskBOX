// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package engine.singbox.plugin

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ProviderInfo
import app.R
import java.io.File

/**
 * Discovers installed plugin packages. The binary lives in the plugin APK's
 * own native library directory, so nothing is copied: the core execs that path
 * directly (plugins must be packaged with jniLibs.useLegacyPackaging = true so
 * the .so is extracted and executable).
 */
internal object PluginRegistry {

    /** Trusted installed plugins by plugin id. Queries the package manager; keep it off the main thread. */
    fun installedPlugins(context: Context): Map<String, InstalledPlugin> {
        val packageManager = context.packageManager
        // Every plugin answers the SagerNet plugin action; the provider entry
        // already carries its authority, metadata and native library directory.
        val providers = packageManager.queryIntentContentProviders(
            Intent(PluginContract.ActionNativePlugin),
            PackageManager.GET_META_DATA,
        ).mapNotNull { resolveInfo -> resolveInfo.providerInfo }
        return providers
            .mapNotNull { provider -> provider.asInstalledPlugin(packageManager) }
            .groupBy(InstalledPlugin::pluginId)
            .mapValues { (_, candidates) -> preferred(candidates) }
    }

    /** The plugin for every requirement, or the first missing one as a user-facing failure. */
    fun resolve(context: Context, requirements: List<PluginRequirement>): Map<String, InstalledPlugin> {
        val installed = installedPlugins(context)
        requirements.firstOrNull { requirement -> requirement.pluginId !in installed }?.let { missing ->
            throw PluginNotFoundException(
                requirement = missing,
                message = context.getString(R.string.plugin_missing_message, missing.displayName, missing.pluginId),
            )
        }
        return installed
    }

    private fun preferred(candidates: List<InstalledPlugin>): InstalledPlugin =
        candidates.firstOrNull { plugin -> plugin.publisher == PluginPublisher.HUSI.displayName }
            ?: candidates.minBy(InstalledPlugin::packageName)

    private fun ProviderInfo.asInstalledPlugin(packageManager: PackageManager): InstalledPlugin? {
        val publisher = authority?.takeIf(String::isNotBlank)?.let(PluginPublisher::ofAuthority) ?: return null
        val pluginId = loadMetadataString(packageManager)?.takeIf(String::isNotBlank) ?: return null
        // Only plugin ids this build can use; checked before the signature query.
        if (PluginOutbounds.all.none { requirement -> requirement.pluginId == pluginId }) return null
        val signed = packageManager.signedPackage(packageName) ?: return null
        val certificates = signed.certificates
        if (certificates.isEmpty() || !publisher.trustedCertificates.containsAll(certificates)) return null
        val allowed = publisher.allowedPluginIds(certificates)
        if (allowed != null && pluginId !in allowed) return null
        val executable = executablePath() ?: return null
        return InstalledPlugin(
            pluginId = pluginId,
            packageName = packageName,
            versionName = signed.versionName,
            publisher = if (KlzgradCertificate in certificates) "klzgrad" else publisher.displayName,
            executable = executable,
        )
    }

    private fun ProviderInfo.executablePath(): File? {
        val relative = metaData?.getString(PluginContract.MetadataKeyExecutablePath)
            ?.takeIf(String::isNotBlank)
            ?: return null
        val nativeLibraryDir = applicationInfo?.nativeLibraryDir ?: return null
        // Resolve first, then verify the result is still inside the plugin's own
        // native library directory: the metadata string is attacker-controlled.
        val root = runCatching { File(nativeLibraryDir).canonicalFile }.getOrNull() ?: return null
        val executable = runCatching { File(root, relative).canonicalFile }.getOrNull() ?: return null
        // Anything that resolves outside it (via "..", symlinks, an absolute
        // path in the metadata) is rejected.
        if (!executable.path.startsWith(root.path + File.separator)) return null
        return executable.takeIf { file -> file.isFile && file.canExecute() }
    }
}
