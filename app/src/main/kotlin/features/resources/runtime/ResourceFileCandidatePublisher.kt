// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.resources.runtime

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import features.resources.ResourceJsonFileOrigin
import java.io.File
import java.io.IOException
import java.security.MessageDigest

internal class ResourceFileChangedException(
    fileName: String,
) : IllegalStateException("$fileName changed after it was opened")

internal data class ResourceFileRevision(
    val exists: Boolean,
    val sizeBytes: Long,
    val sha256: String,
)

internal enum class ResourceFilePathKind {
    Missing,
    RegularFile,
    Occupied,
}

internal enum class ResourceFilePublicationMode {
    Replace,
    CreateNew,
}

internal interface ResourceFileAtomicOperations {
    fun replace(stagedFile: File, target: File)

    fun createNew(stagedFile: File, target: File): Boolean

    fun syncDirectory(directory: File)
}

internal data class ResourceFileIdentity(
    val deviceId: Long,
    val inode: Long,
)

internal interface ExclusiveResourceFileHandle : AutoCloseable {
    val identity: ResourceFileIdentity

    fun copyFromAndSync(stagedFile: File)
}

internal interface ExclusiveResourceFileOperations {
    fun open(target: File): ExclusiveResourceFileHandle?

    fun identity(target: File): ResourceFileIdentity?

    fun delete(target: File)
}

internal fun createNewResourceFile(
    stagedFile: File,
    target: File,
    operations: ExclusiveResourceFileOperations,
): Boolean {
    val handle = operations.open(target) ?: return false
    var failure: Throwable? = null
    try {
        handle.copyFromAndSync(stagedFile)
    } catch (error: Throwable) {
        failure = error
    }
    try {
        handle.close()
    } catch (error: Throwable) {
        val previousFailure = failure
        if (previousFailure == null) {
            failure = error
        } else {
            previousFailure.addSuppressed(error)
        }
    }
    val resultFailure = failure
    if (resultFailure != null) {
        try {
            if (operations.identity(target) == handle.identity) {
                operations.delete(target)
            }
        } catch (cleanupError: Throwable) {
            resultFailure.addSuppressed(cleanupError)
        }
        throw resultFailure
    }
    return true
}

internal fun File.resourceFilePathKind(): ResourceFilePathKind {
    return if (System.getProperty("java.runtime.name") == AndroidRuntimeName) {
        try {
            val status = Os.lstat(absolutePath)
            if (OsConstants.S_ISREG(status.st_mode)) {
                ResourceFilePathKind.RegularFile
            } else {
                ResourceFilePathKind.Occupied
            }
        } catch (error: ErrnoException) {
            if (error.errno == OsConstants.ENOENT) {
                ResourceFilePathKind.Missing
            } else {
                throw IOException("Failed to inspect $absolutePath", error)
            }
        }
    } else {
        hostResourceFilePathKind()
    }
}

internal fun File.resourceFileRevision(): ResourceFileRevision {
    when (resourceFilePathKind()) {
        ResourceFilePathKind.Missing ->
            return ResourceFileRevision(exists = false, sizeBytes = 0L, sha256 = "")
        ResourceFilePathKind.Occupied ->
            return ResourceFileRevision(exists = true, sizeBytes = 0L, sha256 = "")
        ResourceFilePathKind.RegularFile -> Unit
    }
    val digest = MessageDigest.getInstance("SHA-256")
    inputStream().use { input ->
        val buffer = ByteArray(DefaultDigestBufferSize)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return ResourceFileRevision(
        exists = true,
        sizeBytes = length(),
        sha256 = digest.digest().toHexString(),
    )
}

internal fun String.resourceFileRevision(): ResourceFileRevision {
    val bytes = toByteArray()
    return ResourceFileRevision(
        exists = true,
        sizeBytes = bytes.size.toLong(),
        sha256 = MessageDigest.getInstance("SHA-256").digest(bytes).toHexString(),
    )
}

internal fun ResourceJsonFileOrigin.expectedResourceFileRevision(): ResourceFileRevision =
    when (this) {
        ResourceJsonFileOrigin.Missing ->
            ResourceFileRevision(exists = false, sizeBytes = 0L, sha256 = "")
        is ResourceJsonFileOrigin.Existing -> content.resourceFileRevision()
    }

internal fun requireResourceFileRevisionUnchanged(
    target: File,
    expectedRevision: ResourceFileRevision,
) {
    if (target.resourceFileRevision() != expectedRevision) {
        throw ResourceFileChangedException(target.name)
    }
}

internal fun publishValidatedResourceCandidate(
    candidate: File,
    target: File,
    mode: ResourceFilePublicationMode = ResourceFilePublicationMode.Replace,
    atomicOperations: ResourceFileAtomicOperations = AndroidResourceFileAtomicOperations,
    validate: (File) -> Unit,
) {
    synchronized(publicationLockFor(target)) {
        var stagedFile: File? = null
        try {
            require(candidate.isFile && candidate.length() > 0L) {
                "${target.name} candidate is empty"
            }
            validate(candidate)
            val parent = target.parentFile
                ?: error("Parent directory is unavailable for ${target.absolutePath}")
            require(parent.exists() || parent.mkdirs())
            val staged = File.createTempFile(".${target.name}.", ".publish", parent)
            stagedFile = staged
            candidate.inputStream().use { input ->
                staged.outputStream().use { output ->
                    input.copyTo(output)
                    output.flush()
                    output.fd.sync()
                }
            }
            when (mode) {
                ResourceFilePublicationMode.Replace -> {
                    atomicOperations.replace(staged, target)
                    stagedFile = null
                }
                ResourceFilePublicationMode.CreateNew -> {
                    if (!atomicOperations.createNew(staged, target)) {
                        throw ResourceFileChangedException(target.name)
                    }
                }
            }
            atomicOperations.syncDirectory(parent)
        } finally {
            stagedFile?.delete()
            candidate.delete()
        }
    }
}

private object AndroidResourceFileAtomicOperations : ResourceFileAtomicOperations {
    override fun replace(stagedFile: File, target: File) {
        Os.rename(stagedFile.absolutePath, target.absolutePath)
    }

    override fun createNew(stagedFile: File, target: File): Boolean {
        return createNewResourceFile(
            stagedFile = stagedFile,
            target = target,
            operations = AndroidExclusiveResourceFileOperations,
        )
    }

    override fun syncDirectory(directory: File) {
        val descriptor = Os.open(directory.absolutePath, OsConstants.O_RDONLY, 0)
        try {
            Os.fsync(descriptor)
        } finally {
            Os.close(descriptor)
        }
    }
}

private object AndroidExclusiveResourceFileOperations : ExclusiveResourceFileOperations {
    override fun open(target: File): ExclusiveResourceFileHandle? {
        val flags = OsConstants.O_WRONLY or
            OsConstants.O_CREAT or
            OsConstants.O_EXCL or
            LinuxOpenCloseOnExecFlag or
            OsConstants.O_NOFOLLOW
        val descriptor = try {
            Os.open(target.absolutePath, flags, OwnerReadWriteMode)
        } catch (error: ErrnoException) {
            if (error.errno == OsConstants.EEXIST) return null
            throw IOException("Failed to create ${target.absolutePath}", error)
        }
        return try {
            AndroidExclusiveResourceFileHandle(
                descriptor = descriptor,
                targetPath = target.absolutePath,
                identity = Os.fstat(descriptor).toResourceFileIdentity(),
            )
        } catch (error: ErrnoException) {
            runCatching { Os.close(descriptor) }
                .exceptionOrNull()
                ?.let(error::addSuppressed)
            throw IOException("Failed to inspect ${target.absolutePath}", error)
        }
    }

    override fun identity(target: File): ResourceFileIdentity? {
        return try {
            Os.lstat(target.absolutePath).toResourceFileIdentity()
        } catch (error: ErrnoException) {
            if (error.errno == OsConstants.ENOENT) return null
            throw IOException("Failed to inspect ${target.absolutePath}", error)
        }
    }

    override fun delete(target: File) {
        try {
            Os.remove(target.absolutePath)
        } catch (error: ErrnoException) {
            throw IOException("Failed to delete ${target.absolutePath}", error)
        }
    }
}

private class AndroidExclusiveResourceFileHandle(
    private val descriptor: java.io.FileDescriptor,
    private val targetPath: String,
    override val identity: ResourceFileIdentity,
) : ExclusiveResourceFileHandle {
    override fun copyFromAndSync(stagedFile: File) {
        try {
            stagedFile.inputStream().use { input ->
                val buffer = ByteArray(DefaultCopyBufferSize)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    var offset = 0
                    while (offset < count) {
                        val written = Os.write(descriptor, buffer, offset, count - offset)
                        if (written <= 0) {
                            throw IOException("Failed to write $targetPath")
                        }
                        offset += written
                    }
                }
            }
            Os.fsync(descriptor)
        } catch (error: ErrnoException) {
            throw IOException("Failed to write $targetPath", error)
        }
    }

    override fun close() {
        try {
            Os.close(descriptor)
        } catch (error: ErrnoException) {
            throw IOException("Failed to close $targetPath", error)
        }
    }
}

private fun android.system.StructStat.toResourceFileIdentity(): ResourceFileIdentity =
    ResourceFileIdentity(deviceId = st_dev, inode = st_ino)

private val PublicationLocks = mutableMapOf<String, Any>()

private fun publicationLockFor(target: File): Any = synchronized(PublicationLocks) {
    PublicationLocks.getOrPut(target.absolutePath) { Any() }
}

private fun ByteArray.toHexString(): String = buildString(size * 2) {
    this@toHexString.forEach { byte ->
        val value = byte.toInt() and 0xff
        append(HexDigits[value ushr 4])
        append(HexDigits[value and 0x0f])
    }
}

private fun File.hostResourceFilePathKind(): ResourceFilePathKind {
    if (exists()) {
        return if (isFile) {
            ResourceFilePathKind.RegularFile
        } else {
            ResourceFilePathKind.Occupied
        }
    }
    val parent = absoluteFile.parentFile ?: return ResourceFilePathKind.Missing
    if (!parent.exists()) return ResourceFilePathKind.Missing
    if (!parent.isDirectory) return ResourceFilePathKind.Occupied
    val children = parent.list() ?: throw IOException("Failed to inspect ${parent.absolutePath}")
    return if (name in children) ResourceFilePathKind.Occupied else ResourceFilePathKind.Missing
}

private const val DefaultDigestBufferSize = 8192
private const val DefaultCopyBufferSize = 8192
private const val HexDigits = "0123456789abcdef"
private const val AndroidRuntimeName = "Android Runtime"
private const val OwnerReadWriteMode = 384
// O_CLOEXEC is stable Linux UAPI, but OsConstants does not expose it before API 27.
private const val LinuxOpenCloseOnExecFlag = 524288
