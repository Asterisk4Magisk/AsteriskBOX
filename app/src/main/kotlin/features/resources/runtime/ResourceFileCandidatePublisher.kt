// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.resources.runtime

import android.system.Os
import android.system.OsConstants
import java.io.File
import java.security.MessageDigest

internal class ResourceFileChangedException(
    fileName: String,
) : IllegalStateException("$fileName changed after it was opened")

internal data class ResourceFileRevision(
    val exists: Boolean,
    val sizeBytes: Long,
    val sha256: String,
)

internal fun File.resourceFileRevision(): ResourceFileRevision {
    if (!isFile) return ResourceFileRevision(exists = false, sizeBytes = 0L, sha256 = "")
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
            replaceStagedFile(staged, target)
            stagedFile = null
        } finally {
            stagedFile?.delete()
            candidate.delete()
        }
    }
}

private fun replaceStagedFile(stagedFile: File, target: File) {
    Os.rename(stagedFile.absolutePath, target.absolutePath)
    syncDirectory(target.parentFile ?: return)
}

private fun syncDirectory(directory: File) {
    val descriptor = Os.open(directory.absolutePath, OsConstants.O_RDONLY, 0)
    try {
        Os.fsync(descriptor)
    } finally {
        Os.close(descriptor)
    }
}

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

private const val DefaultDigestBufferSize = 8192
private const val HexDigits = "0123456789abcdef"
