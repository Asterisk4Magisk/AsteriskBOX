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
import features.resources.bundledRuleSetOrNull
import features.resources.hasSingBoxRuleSetExtension
import features.resources.singBoxRuleSetFormatOrNull
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
            resourceFiles = ResourceFileKind.entries.associateWith { kind ->
                val target = if (kind == ResourceFileKind.SingBoxCore) effectiveSingBoxCoreFile() else file(kind)
                target.toStatus(kind)
            },
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
        val customFiles = customResourceFiles
            .filter { customFile -> customFile.name.hasSingBoxRuleSetExtension() }
            .map(::file)
        return customFiles
            .filter { resourceFile -> resourceFile.isFile && resourceFile.length() > 0L }
            .distinctBy { resourceFile -> resourceFile.absolutePath }
    }

    fun restoreBundledDefaults(resourceFileSource: Int = ResourceFileSourceDefault) {
        val bundledUpdatedAtMillis = appContext.packageUpdatedAtMillis()
        ResourceFileKind.entries.forEach { kind ->
            if (kind == ResourceFileKind.SingBoxCore) return@forEach
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

    fun restoreBundledCustomRuleSets(customResourceFiles: List<CustomResourceFileState>) {
        val installedAtMillis = appContext.packageUpdatedAtMillis()
        check(installedAtMillis > 0L) { "Cannot determine the installed resource bundle version" }
        val preferences = appContext.getSharedPreferences("bundled_rule_sets", Context.MODE_PRIVATE)
        if (preferences.getLong("installed_at", 0L) == installedAtMillis) return
        // This is an install/upgrade action, not a missing-file repair. Absent custom records
        // (including user deletions) and resources with a different URL are left alone.
        customResourceFiles.forEach { customFile ->
            val bundled = customFile.bundledRuleSetOrNull() ?: return@forEach
            dataDir.mkdirs()
            appContext.assets.open("sing-box/${bundled.fileName}").use { input ->
                writeAtomically(file(customFile)) { output -> input.copyTo(output) }
            }
        }
        // Do not consume the install marker on a failed write: the next launch can retry.
        @Suppress("UseKtx")
        val saved = preferences.edit().putLong("installed_at", installedAtMillis).commit()
        check(saved) { "Failed to persist bundled rule set installation" }
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
        require(kind != ResourceFileKind.SingBoxCore) { "sing-box core must be restored through the locked publisher" }
        restoreBundledResourceFile(kind)
    }

    private fun restoreBundledResourceFile(kind: ResourceFileKind) {
        dataDir.mkdirs()
        appContext.assets.open(kind.bundledAssetPath()).use { input ->
            writeAtomically(file(kind)) { output -> input.copyTo(output) }
        }
        kind.applyPermissions(file(kind))
    }

    fun hasCustomSingBoxCore(): Boolean = file(ResourceFileKind.SingBoxCore).coreBinaryOwnerUidOrNull() != null

    fun effectiveSingBoxCoreFile(): File = if (hasCustomSingBoxCore()) {
        file(ResourceFileKind.SingBoxCore)
    } else {
        File(appContext.applicationInfo.nativeLibraryDir, SingBoxCoreLibraryName)
    }

    private fun bundledSingBoxCoreFileOrNull(): File? {
        return File(appContext.applicationInfo.nativeLibraryDir, SingBoxCoreLibraryName)
            .takeIf { it.isFile }
    }

    fun replace(kind: ResourceFileKind, uri: Uri) {
        require(kind != ResourceFileKind.SingBoxCore) { "sing-box core must be replaced through the locked publisher" }
        dataDir.mkdirs()
        val replaceTempFile = file(kind).resolveSibling("${kind.fileName}.replace.tmp")
        appContext.contentResolver.openInputStream(uri)?.use { input ->
            replaceTempFile.outputStream().use { output -> input.copyTo(output) }
        } ?: throw FileNotFoundException(uri.toString())

        replaceFile(replaceTempFile, file(kind))
        kind.applyPermissions(file(kind))
    }

    fun stageSingBoxCoreCandidate(uri: Uri): File {
        val uploaded = appContext.contentResolver.openInputStream(uri)?.use(::writeSingBoxCoreCandidate)
            ?: throw FileNotFoundException(uri.toString())
        return normalizeSingBoxCoreCandidate(uploaded)
    }

    fun createSingBoxCoreDownloadCandidate(): File = createSingBoxCoreCandidateFile("sing-box-download-")

    fun normalizeSingBoxCoreCandidate(uploaded: File): File {
        val extracted = createSingBoxCoreCandidateFile("sing-box-extracted-")
        try {
            val found = uploaded.extractZipEntry("sing-box", extracted) || uploaded.extractGzip(extracted)
            return if (found) {
                uploaded.delete()
                extracted
            } else {
                extracted.delete()
                uploaded
            }
        } catch (error: Throwable) {
            extracted.delete()
            uploaded.delete()
            throw error
        }
    }

    fun installInitialSingBoxCoreCandidate(candidate: File): Boolean {
        return publishCoreBinaryCandidate(candidate, file(ResourceFileKind.SingBoxCore), replaceExisting = false)
    }

    fun replaceSingBoxCoreCandidate(candidate: File) {
        publishCoreBinaryCandidate(candidate, file(ResourceFileKind.SingBoxCore), replaceExisting = true)
    }

    private fun writeSingBoxCoreCandidate(input: java.io.InputStream): File {
        val candidate = createSingBoxCoreCandidateFile("sing-box-core-")
        try {
            candidate.outputStream().use { output ->
                input.copyTo(output)
                output.flush()
                output.fd.sync()
            }
            return candidate
        } catch (error: Throwable) {
            candidate.delete()
            throw error
        }
    }

    private fun createSingBoxCoreCandidateFile(prefix: String): File {
        require(appContext.cacheDir.exists() || appContext.cacheDir.mkdirs())
        return File.createTempFile(prefix, ".candidate", appContext.cacheDir)
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

    fun readCustomTextOrNull(customFile: CustomResourceFileState): String? {
        return file(customFile).readResourceTextOrNull()
    }

    fun stageCustomCandidate(customFile: CustomResourceFileState, uri: Uri): File {
        return appContext.contentResolver.openInputStream(uri)?.use { input ->
            writeCustomCandidate(customFile, input)
        } ?: throw FileNotFoundException(uri.toString())
    }

    fun stageCustomCandidate(customFile: CustomResourceFileState, content: String): File {
        return content.byteInputStream().use { input -> writeCustomCandidate(customFile, input) }
    }

    fun stageCustomCandidate(customFile: CustomResourceFileState, source: File): File {
        return source.inputStream().use { input -> writeCustomCandidate(customFile, input) }
    }

    fun createCustomDownloadCandidate(customFile: CustomResourceFileState): File {
        require(appContext.cacheDir.exists() || appContext.cacheDir.mkdirs())
        val suffix = customFile.name.singBoxRuleSetFormatOrNull()?.fileExtension ?: ".candidate"
        return File.createTempFile("resource-${customFile.id}-", suffix, appContext.cacheDir)
    }

    private fun writeCustomCandidate(
        customFile: CustomResourceFileState,
        input: java.io.InputStream,
    ): File {
        val candidate = createCustomDownloadCandidate(customFile)
        try {
            candidate.outputStream().use { output ->
                input.copyTo(output)
                output.flush()
                output.fd.sync()
            }
            require(candidate.length() > 0L) { "${customFile.name} candidate is empty" }
            return candidate
        } catch (error: Throwable) {
            candidate.delete()
            throw error
        }
    }

    fun applyPermissions(kind: ResourceFileKind) {
        kind.applyPermissions(file(kind))
    }

    fun deleteCustom(customFile: CustomResourceFileState) {
        val target = file(customFile)
        if (ResourceFileKind.entries.any { kind -> kind.fileName == target.name }) return
        deleteResourceFile(target)
    }

    fun preparePaths(): SingBoxResourceFilePaths {
        dataDir.mkdirs()
        return currentPaths()
    }

    fun currentPaths(): SingBoxResourceFilePaths {
        return SingBoxResourceFilePaths(
            dataDir = dataDir.absolutePath,
            asteriskdPath = File(appContext.applicationInfo.nativeLibraryDir, AsteriskdLibraryName).absolutePath,
            bpfMatcherPath = File(appContext.applicationInfo.nativeLibraryDir, BpfMatcherLibraryName).absolutePath,
            bpf2socksPath = File(appContext.applicationInfo.nativeLibraryDir, Bpf2SocksLibraryName).absolutePath,
            singBoxCorePath = effectiveSingBoxCoreFile().absolutePath,
            directCidrIpv4Path = file(ResourceFileKind.DirectCidrIpv4).absolutePath,
            directCidrIpv6Path = file(ResourceFileKind.DirectCidrIpv6).absolutePath,
            hevSocks5TunnelPath = File(appContext.applicationInfo.nativeLibraryDir, HevSocks5TunnelLibraryName).absolutePath,
        )
    }
}

internal fun File.readResourceTextOrNull(): String? {
    return when (resourceFilePathKind()) {
        ResourceFilePathKind.Missing -> null
        ResourceFilePathKind.RegularFile -> readText()
        ResourceFilePathKind.Occupied -> error("$name is not a regular file")
    }
}

internal fun deleteResourceFile(target: File) {
    if (target.exists() && !target.delete()) {
        throw IOException("Failed to delete resource file: ${target.absolutePath}")
    }
}

private fun File.needsBundledRestore(
    kind: ResourceFileKind,
    resourceFileSource: Int,
    bundledUpdatedAtMillis: Long,
): Boolean {
    return shouldRestoreBundledResourceFile(
        kind = kind,
        resourceFileSource = resourceFileSource,
        targetExists = exists(),
        targetLength = takeIf { exists() }?.length() ?: 0L,
        targetLastModifiedMillis = takeIf { exists() }?.lastModified() ?: 0L,
        bundledUpdatedAtMillis = bundledUpdatedAtMillis,
    )
}

internal fun shouldRestoreBundledResourceFile(
    kind: ResourceFileKind,
    resourceFileSource: Int,
    targetExists: Boolean,
    targetLength: Long,
    targetLastModifiedMillis: Long,
    bundledUpdatedAtMillis: Long,
): Boolean {
    if (!targetExists || (kind != ResourceFileKind.SingBoxCore && targetLength <= 0)) return true
    if (kind != ResourceFileKind.SingBoxCore && resourceFileSource != ResourceFileSourceDefault) {
        return false
    }
    return bundledUpdatedAtMillis > 0 && targetLastModifiedMillis < bundledUpdatedAtMillis
}

internal data class SingBoxResourceFilePaths(
    val dataDir: String,
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

internal fun Context.singBoxResourceFilePaths(): SingBoxResourceFilePaths {
    return AndroidResourceFileStore(this).currentPaths()
}

internal fun Context.singBoxRuleSetFiles(
    customResourceFiles: List<CustomResourceFileState>,
): List<File> = AndroidResourceFileStore(this).singBoxRuleSetFiles(customResourceFiles)

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

private const val AsteriskdLibraryName = "libasteriskd.so"
private const val BpfMatcherLibraryName = "libbpf-matcher.so"
private const val Bpf2SocksLibraryName = "libbpf2socks.so"
private const val SingBoxCoreLibraryName = "libsing-box.so"
private const val HevSocks5TunnelLibraryName = "libhev-socks5-tunnel-cli.so"
private const val SingBoxHomeDirName = "sing-box"


internal fun resourceFileExists(
    kind: ResourceFileKind?,
    targetExists: Boolean,
    targetLength: Long,
): Boolean {
    return targetExists && (kind == ResourceFileKind.SingBoxCore || targetLength > 0)
}

private fun File.toStatus(kind: ResourceFileKind? = null): ResourceFileStatus {
    val targetExists = exists()
    val targetLength = takeIf { targetExists }?.length() ?: 0L
    return ResourceFileStatus(
        exists = resourceFileExists(kind, targetExists, targetLength),
        sizeBytes = targetLength,
        updatedAtMillis = takeIf { targetExists }?.lastModified() ?: 0,
    )
}

private fun File.extractZipEntry(entryName: String, target: File): Boolean {
    return runCatching {
        ZipInputStream(inputStream()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory && entry.name.substringAfterLast('/') == entryName) {
                    target.outputStream().use { output ->
                        zip.copyTo(output)
                        output.flush()
                        output.fd.sync()
                    }
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
            target.outputStream().use { output ->
                input.copyTo(output)
                output.flush()
                output.fd.sync()
            }
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
