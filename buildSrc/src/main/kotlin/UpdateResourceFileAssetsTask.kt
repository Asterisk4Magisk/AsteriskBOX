// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import java.io.EOFException
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.util.zip.GZIPInputStream

abstract class UpdateResourceFileAssetsTask : DefaultTask() {
    @get:Input
    abstract val singBoxVersion: Property<String>

    @get:OutputDirectory
    abstract val singBoxCoreJniLibsDir: DirectoryProperty

    @get:OutputDirectory
    abstract val resourceFileAssetsDir: DirectoryProperty

    init {
        group = "resources"
        description = "Download bundled resource file assets."
    }

    @TaskAction
    fun updateAssets() {
        AndroidSingBoxAssets.forEach { asset ->
            val target = File(
                singBoxCoreJniLibsDir.get().asFile,
                "${asset.androidAbi}/libsing-box.so",
            )
            downloadAndExtractSingBox(asset, target)
        }
        AndroidResourceFileAssets.forEach { asset ->
            downloadFile(
                url = asset.url,
                target = File(resourceFileAssetsDir.get().asFile, "sing-box/${asset.fileName}"),
            )
        }
    }

    private fun downloadAndExtractSingBox(asset: SingBoxAsset, target: File) {
        if (useExistingFile(target)) return
        target.parentFile.mkdirs()
        val archive = target.resolveSibling("${target.name}.tar.gz.tmp")
        val extracted = target.resolveSibling("${target.name}.extract.tmp")
        archive.delete()
        extracted.delete()
        try {
            val version = singBoxVersion.get()
            val rawVersion = version.removePrefix("v")
            val releaseName = "sing-box-$rawVersion-android-${asset.releaseArch}.tar.gz"
            val url = "https://github.com/reF1nd/sing-box-releases/releases/download/$version/$releaseName"
            downloadToFile(url, archive)
            extractTarGzipEntry(
                archive = archive,
                target = extracted,
                predicate = { entryName -> entryName.substringAfterLast('/') == "sing-box" },
            )
            if (extracted.length() <= 0L) {
                throw GradleException("Extracted sing-box binary is empty: $releaseName")
            }
            if (target.exists() && !target.delete()) {
                throw GradleException("Unable to replace ${target.absolutePath}")
            }
            if (!extracted.renameTo(target)) {
                throw GradleException("Unable to move ${extracted.absolutePath} to ${target.absolutePath}")
            }
            logger.lifecycle("Updated ${target.absolutePath} (${target.length()} bytes)")
        } finally {
            archive.delete()
            extracted.delete()
        }
    }

    private fun downloadFile(url: String, target: File) {
        if (useExistingFile(target)) return
        target.parentFile.mkdirs()
        val temporary = target.resolveSibling("${target.name}.tmp")
        temporary.delete()
        try {
            downloadToFile(url, temporary)
            if (target.exists() && !target.delete()) {
                throw GradleException("Unable to replace ${target.absolutePath}")
            }
            if (!temporary.renameTo(target)) {
                throw GradleException("Unable to move ${temporary.absolutePath} to ${target.absolutePath}")
            }
            logger.lifecycle("Updated ${target.absolutePath} (${target.length()} bytes)")
        } finally {
            temporary.delete()
        }
    }

    private fun useExistingFile(target: File): Boolean {
        if (!target.isFile) return false
        logger.lifecycle("Using existing ${target.absolutePath} (${target.length()} bytes)")
        return true
    }

    private fun downloadToFile(url: String, target: File) {
        logger.lifecycle("Downloading $url")
        val connection = (URI.create(url).toURL().openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 120_000
            instanceFollowRedirects = true
            requestMethod = "GET"
            setRequestProperty("User-Agent", "AsteriskBOX-Gradle")
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) {
                throw GradleException("Failed to download $url: HTTP $code")
            }
            connection.inputStream.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
        } finally {
            connection.disconnect()
        }
        if (target.length() <= 0L) {
            throw GradleException("Downloaded file is empty: $url")
        }
    }
}

private fun extractTarGzipEntry(
    archive: File,
    target: File,
    predicate: (String) -> Boolean,
) {
    GZIPInputStream(archive.inputStream().buffered()).use { gzip ->
        val header = ByteArray(TarBlockSize)
        while (true) {
            val headerRead = gzip.readBlockOrEof(header)
            if (headerRead == 0 || header.all { it == 0.toByte() }) break
            if (headerRead != TarBlockSize) throw EOFException("Truncated tar header in ${archive.name}")

            val entryName = header.tarString(0, 100)
            val entrySize = header.tarOctal(124, 12)
            val type = header[156].toInt().toChar()
            val selected = type in setOf('\u0000', '0') && predicate(entryName)
            if (selected) {
                target.outputStream().use { output -> gzip.copyExactlyTo(output, entrySize) }
            } else {
                gzip.skipExactly(entrySize)
            }
            val padding = (TarBlockSize - (entrySize % TarBlockSize)) % TarBlockSize
            gzip.skipExactly(padding)
            if (selected) return
        }
    }
    throw GradleException("sing-box executable not found in ${archive.name}")
}

private fun InputStream.readBlockOrEof(buffer: ByteArray): Int {
    var offset = 0
    while (offset < buffer.size) {
        val read = read(buffer, offset, buffer.size - offset)
        if (read < 0) return offset
        offset += read
    }
    return offset
}

private fun InputStream.copyExactlyTo(output: java.io.OutputStream, byteCount: Long) {
    var remaining = byteCount
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    while (remaining > 0L) {
        val read = read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
        if (read < 0) throw EOFException("Unexpected end of tar entry")
        output.write(buffer, 0, read)
        remaining -= read
    }
}

private fun InputStream.skipExactly(byteCount: Long) {
    var remaining = byteCount
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    while (remaining > 0L) {
        val skipped = skip(remaining)
        if (skipped > 0L) {
            remaining -= skipped
            continue
        }
        val read = read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
        if (read < 0) throw EOFException("Unexpected end of tar entry")
        remaining -= read
    }
}

private fun ByteArray.tarString(offset: Int, length: Int): String {
    val end = (offset until offset + length)
        .firstOrNull { index -> this[index] == 0.toByte() }
        ?: (offset + length)
    return copyOfRange(offset, end).toString(Charsets.UTF_8)
}

private fun ByteArray.tarOctal(offset: Int, length: Int): Long {
    val text = tarString(offset, length).trim()
    return text.ifEmpty { "0" }.toLong(radix = 8)
}

private data class SingBoxAsset(
    val androidAbi: String,
    val releaseArch: String,
)

private data class ResourceFileAsset(
    val fileName: String,
    val url: String,
)

private val AndroidSingBoxAssets = listOf(
    SingBoxAsset(
        androidAbi = "arm64-v8a",
        releaseArch = "arm64",
    ),
    SingBoxAsset(
        androidAbi = "armeabi-v7a",
        releaseArch = "arm",
    ),
    SingBoxAsset(
        androidAbi = "x86",
        releaseArch = "386",
    ),
    SingBoxAsset(
        androidAbi = "x86_64",
        releaseArch = "amd64",
    ),
)

private val AndroidResourceFileAssets = listOf(
    ResourceFileAsset(
        fileName = "geosite-category-ads-all.srs",
        url = "https://raw.githubusercontent.com/SagerNet/sing-geosite/rule-set/geosite-category-ads-all.srs",
    ),
    ResourceFileAsset(
        fileName = "geosite-google.srs",
        url = "https://raw.githubusercontent.com/SagerNet/sing-geosite/rule-set/geosite-google.srs",
    ),
    ResourceFileAsset(
        fileName = "geosite-cn.srs",
        url = "https://raw.githubusercontent.com/SagerNet/sing-geosite/rule-set/geosite-cn.srs",
    ),
    ResourceFileAsset(
        fileName = "geoip-cn.srs",
        url = "https://raw.githubusercontent.com/SagerNet/sing-geoip/rule-set/geoip-cn.srs",
    ),
    ResourceFileAsset(
        fileName = "direct-cidr-v4.txt",
        url = "https://raw.githubusercontent.com/mayaxcn/china-ip-list/master/chnroute.txt",
    ),
    ResourceFileAsset(
        fileName = "direct-cidr-v6.txt",
        url = "https://raw.githubusercontent.com/mayaxcn/china-ip-list/master/chnroute_v6.txt",
    ),
)

private const val TarBlockSize = 512
