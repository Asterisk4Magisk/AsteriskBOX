// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.resources.runtime

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import app.CustomResourceFileState
import app.CustomResourceFileStatus
import app.ResourceFileKind
import app.ResourceFileStatus
import app.ResourceFilesStatus
import app.sanitizeCustomResourceFileName
import features.resources.ResourceFileSourceDefault
import features.resources.hasSingBoxRuleSetExtension
import utils.writeAtomically
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream

internal class AndroidResourceFileStore(
    context: Context,
) {
    private val appContext = context.applicationContext
    val dataDir: File = appContext.singBoxResourceFilesDir()

    fun status(customResourceFiles: List<CustomResourceFileState> = emptyList()): ResourceFilesStatus {
        return currentStatus(customResourceFiles)
    }

    fun currentStatus(customResourceFiles: List<CustomResourceFileState> = emptyList()): ResourceFilesStatus {
        return ResourceFilesStatus(
            resourceFiles = ResourceFileKind.entries.associateWith { kind -> file(kind).toStatus() },
            customResourceFiles = customResourceFiles.map { customFile ->
                CustomResourceFileStatus(
                    file = customFile,
                    status = file(customFile).toStatus(),
                )
            },
        )
    }

    fun file(kind: ResourceFileKind): File {
        return File(dataDir, kind.fileName)
    }

    fun file(customFile: CustomResourceFileState): File {
        return File(
            dataDir,
            sanitizeCustomResourceFileName(
                value = customFile.name,
                fallback = "custom-resource-${customFile.id}.dat",
            ),
        )
    }

    fun singBoxRuleSetFiles(customResourceFiles: List<CustomResourceFileState>): List<File> {
        val bundledFiles = ResourceFileKind.entries
            .filter { kind -> kind.fileName.hasSingBoxRuleSetExtension() }
            .map(::file)
        val customFiles = customResourceFiles
            .filter { customFile -> customFile.name.hasSingBoxRuleSetExtension() }
            .map(::file)
        return (bundledFiles + customFiles)
            .filter { resourceFile -> resourceFile.isFile && resourceFile.length() > 0L }
            .distinctBy { resourceFile -> resourceFile.absolutePath }
    }

    fun restoreBundledDefaults(resourceFileSource: Int = ResourceFileSourceDefault) {
        val bundledUpdatedAtMillis = appContext.packageUpdatedAtMillis()
        ResourceFileKind.entries.forEach { kind ->
            val target = file(kind)
            if (!target.needsBundledRestore(kind, resourceFileSource, bundledUpdatedAtMillis)) return@forEach
            if (!hasBundledFile(kind)) return@forEach
            runCatching { restoreBundled(kind) }
                .onFailure { error ->
                    AndroidResourceFileLogger.warn(
                        "Failed to restore bundled resource file: ${kind.fileName}",
                        error,
                    )
                }
        }
    }

    private fun hasBundledFile(kind: ResourceFileKind): Boolean {
        return when (kind) {
            ResourceFileKind.SingBoxCore -> bundledSingBoxCoreFileOrNull() != null
            else -> runCatching {
                appContext.assets.open(kind.bundledAssetPath()).use { input -> input.read() >= 0 }
            }.getOrDefault(false)
        }
    }

    private fun ResourceFileKind.bundledAssetPath(): String {
        return "sing-box/$fileName"
    }

    fun restoreBundled(kind: ResourceFileKind) {
        when (kind) {
            ResourceFileKind.SingBoxCore -> restoreBundledSingBoxCore()
            else -> restoreBundledResourceFile(kind)
        }
    }

    private fun restoreBundledResourceFile(kind: ResourceFileKind) {
        dataDir.mkdirs()
        appContext.assets.open(kind.bundledAssetPath()).use { input ->
            writeAtomically(file(kind)) { output -> input.copyTo(output) }
        }
        kind.applyPermissions(file(kind))
    }

    private fun restoreBundledSingBoxCore() {
        val source = bundledSingBoxCoreFileOrNull()
            ?: error("Bundled ${ResourceFileKind.SingBoxCore.fileName} is not available for ${currentRuntimeAbi()}")
        dataDir.mkdirs()
        source.inputStream().use { input ->
            writeAtomically(file(ResourceFileKind.SingBoxCore)) { output -> input.copyTo(output) }
        }
        ResourceFileKind.SingBoxCore.applyPermissions(file(ResourceFileKind.SingBoxCore))
    }

    private fun bundledSingBoxCoreFileOrNull(): File? {
        return File(appContext.applicationInfo.nativeLibraryDir, SingBoxCoreLibraryName)
            .takeIf { it.isFile && it.length() > 0 }
    }

    fun replace(kind: ResourceFileKind, uri: Uri) {
        dataDir.mkdirs()
        val replaceTempFile = file(kind).resolveSibling("${kind.fileName}.replace.tmp")
        appContext.contentResolver.openInputStream(uri)?.use { input ->
            replaceTempFile.outputStream().use { output -> input.copyTo(output) }
        } ?: throw FileNotFoundException(uri.toString())

        when {
            kind == ResourceFileKind.SingBoxCore && replaceTempFile.extractZipEntry("sing-box", file(kind)) -> {
                replaceTempFile.delete()
            }

            kind == ResourceFileKind.SingBoxCore && replaceTempFile.extractGzip(file(kind)) -> {
                replaceTempFile.delete()
            }

            else -> replaceFile(replaceTempFile, file(kind))
        }
        kind.applyPermissions(file(kind))
    }

    fun replaceCustom(customFile: CustomResourceFileState, uri: Uri) {
        val target = file(customFile)
        if (ResourceFileKind.entries.any { kind -> kind.fileName == target.name }) return
        dataDir.mkdirs()
        val replaceTempFile = target.resolveSibling("${target.name}.replace.tmp")
        appContext.contentResolver.openInputStream(uri)?.use { input ->
            replaceTempFile.outputStream().use { output -> input.copyTo(output) }
        } ?: throw FileNotFoundException(uri.toString())

        replaceFile(replaceTempFile, target)
    }

    fun applyPermissions(kind: ResourceFileKind) {
        kind.applyPermissions(file(kind))
    }

    fun deleteCustom(customFile: CustomResourceFileState) {
        val target = file(customFile)
        if (ResourceFileKind.entries.any { kind -> kind.fileName == target.name }) return
        deleteResourceFile(target)
    }

    fun renameCustom(previousFile: CustomResourceFileState, customFile: CustomResourceFileState) {
        val source = file(previousFile)
        val target = file(customFile)
        if (ResourceFileKind.entries.any { kind -> kind.fileName == source.name || kind.fileName == target.name }) return
        if (source.absolutePath == target.absolutePath) return
        dataDir.mkdirs()
        renameResourceFile(source, target)
    }

    fun preparePaths(): SingBoxResourceFilePaths {
        dataDir.mkdirs()
        return SingBoxResourceFilePaths(
            dataDir = dataDir.absolutePath,
            setuidgidPath = File(appContext.applicationInfo.nativeLibraryDir, SetuidgidLibraryName).absolutePath,
            asteriskdPath = File(appContext.applicationInfo.nativeLibraryDir, AsteriskdLibraryName).absolutePath,
            bpfMatcherPath = File(appContext.applicationInfo.nativeLibraryDir, BpfMatcherLibraryName).absolutePath,
            bpf2socksPath = File(appContext.applicationInfo.nativeLibraryDir, Bpf2SocksLibraryName).absolutePath,
            singBoxCorePath = file(ResourceFileKind.SingBoxCore).absolutePath,
            directCidrIpv4Path = file(ResourceFileKind.DirectCidrIpv4).absolutePath,
            directCidrIpv6Path = file(ResourceFileKind.DirectCidrIpv6).absolutePath,
            hevSocks5TunnelPath = File(appContext.applicationInfo.nativeLibraryDir, HevSocks5TunnelLibraryName).absolutePath,
        )
    }
}

internal fun deleteResourceFile(target: File) {
    if (target.exists() && !target.delete()) {
        throw IOException("Failed to delete resource file: ${target.absolutePath}")
    }
}

internal fun renameResourceFile(
    source: File,
    target: File,
) {
    if (!source.isFile || source.length() <= 0) {
        throw FileNotFoundException(source.absolutePath)
    }
    if (!target.exists() && source.renameTo(target)) {
        check(target.isFile && target.length() > 0) {
            "Renamed resource file is unavailable: ${target.absolutePath}"
        }
        return
    }
    source.inputStream().use { input ->
        writeAtomically(target) { output -> input.copyTo(output) }
    }
    if (!target.isFile || target.length() <= 0) {
        throw IOException("Renamed resource file is unavailable: ${target.absolutePath}")
    }
    if (!source.delete()) {
        throw IOException("Failed to remove renamed resource file: ${source.absolutePath}")
    }
}

private fun File.needsBundledRestore(
    kind: ResourceFileKind,
    resourceFileSource: Int,
    bundledUpdatedAtMillis: Long,
): Boolean {
    if (!exists() || length() <= 0) return true
    if (kind != ResourceFileKind.SingBoxCore && resourceFileSource != ResourceFileSourceDefault) {
        return false
    }
    return bundledUpdatedAtMillis > 0 && lastModified() < bundledUpdatedAtMillis
}

internal data class SingBoxResourceFilePaths(
    val dataDir: String,
    val setuidgidPath: String,
    val asteriskdPath: String,
    val bpfMatcherPath: String,
    val bpf2socksPath: String,
    val singBoxCorePath: String,
    val directCidrIpv4Path: String,
    val directCidrIpv6Path: String,
    val hevSocks5TunnelPath: String,
)

internal fun Context.singBoxResourceFilesDir(): File {
    return File(filesDir, SingBoxHomeDirName)
}

internal fun Context.prepareSingBoxResourceFilePaths(): SingBoxResourceFilePaths {
    return AndroidResourceFileStore(this).preparePaths()
}

internal fun Context.singBoxRuleSetFiles(
    customResourceFiles: List<CustomResourceFileState>,
): List<File> = AndroidResourceFileStore(this).singBoxRuleSetFiles(customResourceFiles)

private fun currentRuntimeAbi(): String {
    return Build.SUPPORTED_ABIS.firstOrNull { abi -> abi in SupportedAndroidAbis }
        ?: error("Unsupported CPU ABI: ${Build.SUPPORTED_ABIS.joinToString()}")
}

private fun Context.packageUpdatedAtMillis(): Long {
    return runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager
                .getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
                .lastUpdateTime
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, 0).lastUpdateTime
        }
    }.getOrDefault(0L)
}

private const val SetuidgidLibraryName = "libsetuidgid.so"
private const val AsteriskdLibraryName = "libasteriskd.so"
private const val BpfMatcherLibraryName = "libbpf-matcher.so"
private const val Bpf2SocksLibraryName = "libbpf2socks.so"
private const val SingBoxCoreLibraryName = "libsing-box.so"
private const val HevSocks5TunnelLibraryName = "libhev-socks5-tunnel-cli.so"
private const val SingBoxHomeDirName = "sing-box"

private val SupportedAndroidAbis = setOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")

private fun File.toStatus(): ResourceFileStatus {
    return ResourceFileStatus(
        exists = exists() && length() > 0,
        sizeBytes = takeIf { exists() }?.length() ?: 0,
        updatedAtMillis = takeIf { exists() }?.lastModified() ?: 0,
    )
}

private fun File.extractZipEntry(entryName: String, target: File): Boolean {
    return runCatching {
        ZipInputStream(inputStream()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory && entry.name.substringAfterLast('/') == entryName) {
                    writeAtomically(target) { output -> zip.copyTo(output) }
                    return@runCatching true
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
            false
        }
    }.onFailure { error ->
        AndroidResourceFileLogger.warn("Failed to extract $entryName from $absolutePath", error)
    }.getOrDefault(false)
}

private fun File.extractGzip(target: File): Boolean {
    return runCatching {
        GZIPInputStream(inputStream()).use { input ->
            writeAtomically(target) { output -> input.copyTo(output) }
        }
        true
    }.onFailure { error ->
        AndroidResourceFileLogger.warn("Failed to extract gzip ${absolutePath}", error)
    }.getOrDefault(false)
}

private fun replaceFile(source: File, target: File) {
    if (source.length() <= 0) {
        source.delete()
        error("${target.name} is empty")
    }
    if (target.exists()) {
        target.delete()
    }
    if (!source.renameTo(target)) {
        source.inputStream().use { input ->
            writeAtomically(target) { output -> input.copyTo(output) }
        }
        source.delete()
    }
}

private fun ResourceFileKind.applyPermissions(file: File) {
    if (this == ResourceFileKind.SingBoxCore) {
        file.setExecutable(true, false)
    }
}
