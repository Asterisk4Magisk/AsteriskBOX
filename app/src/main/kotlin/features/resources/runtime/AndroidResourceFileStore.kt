// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.resources.runtime

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import app.CustomResourceFileState
import app.CustomResourceFileStatus
import app.ResourceFileKind
import app.ResourceFileStatus
import app.ResourceFilesStatus
import app.sanitizeCustomResourceFileName
import features.resources.ResourceFileSourceDefault
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

    fun stageBundledSingBoxCoreCandidate(): File {
        val source = bundledSingBoxCoreFileOrNull()
            ?: error("Bundled ${ResourceFileKind.SingBoxCore.fileName} is not available for ${currentRuntimeAbi()}")
        return source.inputStream().use(::writeSingBoxCoreCandidate)
    }

    fun shouldPublishBundledSingBoxCore(resourceFileSource: Int): Boolean {
        return bundledSingBoxCoreFileOrNull() != null && file(ResourceFileKind.SingBoxCore).needsBundledRestore(
            ResourceFileKind.SingBoxCore,
            resourceFileSource,
            appContext.packageUpdatedAtMillis(),
        )
    }

    private fun bundledSingBoxCoreFileOrNull(): File? {
        return File(appContext.applicationInfo.nativeLibraryDir, SingBoxCoreLibraryName)
            .takeIf { it.isFile && it.length() > 0 }
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
        require(candidate.isFile && candidate.length() > 0) { "sing-box core candidate is empty" }
        require(dataDir.exists() || dataDir.mkdirs())
        val target = file(ResourceFileKind.SingBoxCore)
        return synchronized(writeLockFor(target)) {
            if (target.exists()) return@synchronized false
            val temp = File.createTempFile(".sing-box-initial-", ".tmp", dataDir)
            try {
                candidate.inputStream().use { input ->
                    temp.outputStream().use { output ->
                        input.copyTo(output)
                        output.flush()
                        output.fd.sync()
                    }
                }
                require(temp.length() > 0)
                Os.chmod(temp.absolutePath, SingBoxExecutableMode)
                try {
                    Os.link(temp.absolutePath, target.absolutePath)
                } catch (error: ErrnoException) {
                    if (error.errno == OsConstants.EEXIST) return@synchronized false
                    throw error
                }
                syncDirectory(dataDir)
                true
            } finally {
                temp.delete()
            }
        }
    }

    private fun writeSingBoxCoreCandidate(input: java.io.InputStream): File {
        val candidate = createSingBoxCoreCandidateFile("sing-box-core-")
        try {
            candidate.outputStream().use { output ->
                input.copyTo(output)
                output.flush()
                output.fd.sync()
            }
            require(candidate.length() > 0) { "sing-box core candidate is empty" }
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

    fun readCustomText(customFile: CustomResourceFileState): String {
        val target = file(customFile)
        require(target.isFile && target.length() > 0L) { "${customFile.name} is unavailable" }
        return target.readText()
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

private const val AsteriskdLibraryName = "libasteriskd.so"
private const val BpfMatcherLibraryName = "libbpf-matcher.so"
private const val Bpf2SocksLibraryName = "libbpf2socks.so"
private const val SingBoxCoreLibraryName = "libsing-box.so"
private const val HevSocks5TunnelLibraryName = "libhev-socks5-tunnel-cli.so"
private const val SingBoxHomeDirName = "sing-box"
private const val SingBoxExecutableMode = 493

private val SupportedAndroidAbis = setOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")

internal enum class CoreCandidateInstallPath {
    AtomicPublication,
    InitialNoReplace,
    Defer,
}

internal fun chooseCoreCandidateInstallPath(
    hasRootAccess: Boolean,
    targetExists: Boolean,
): CoreCandidateInstallPath = when {
    hasRootAccess -> CoreCandidateInstallPath.AtomicPublication
    !targetExists -> CoreCandidateInstallPath.InitialNoReplace
    else -> CoreCandidateInstallPath.Defer
}

private fun syncDirectory(directory: File) {
    val descriptor = Os.open(directory.absolutePath, OsConstants.O_RDONLY, 0)
    try {
        Os.fsync(descriptor)
    } finally {
        Os.close(descriptor)
    }
}

private val WriteLocks = mutableMapOf<String, Any>()

private fun writeLockFor(target: File): Any = synchronized(WriteLocks) {
    WriteLocks.getOrPut(target.absolutePath) { Any() }
}

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
