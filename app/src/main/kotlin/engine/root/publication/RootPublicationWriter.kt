// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package engine.root.publication

import java.io.File

internal object RootPublicationWriter {
    fun write(
        layout: RootRuntimeLayout,
        coreConfigBytes: ByteArray,
        daemonConfigBytes: ByteArray,
        pluginOptionsBytes: ByteArray,
    ) {
        writeFile(File(layout.configPath), coreConfigBytes)
        writeFile(File(layout.asteriskdConfigPath), daemonConfigBytes)
        // An empty envelope means "no plugins": remove the sidecar instead of
        // leaving an empty file the core would have to parse.
        val pluginOptionsFile = File(layout.pluginOptionsPath)
        if (pluginOptionsBytes.isEmpty()) {
            pluginOptionsFile.delete()
        } else {
            writeFile(pluginOptionsFile, pluginOptionsBytes)
        }
    }

    private fun writeFile(file: File, content: ByteArray) {
        val parent = requireNotNull(file.parentFile)
        require(parent.isDirectory && file.canonicalFile == File(parent.canonicalFile, file.name)) {
            "Unsafe ROOT configuration path: ${file.absolutePath}"
        }
        require(file.createNewFile() || file.isFile) {
            "Invalid ROOT configuration file: ${file.absolutePath}"
        }
        check(file.setReadable(false, false) && file.setReadable(true, true)) {
            "Failed to set ROOT configuration read permissions"
        }
        check(file.setWritable(false, false) && file.setWritable(true, true)) {
            "Failed to set ROOT configuration write permissions"
        }
        check(file.setExecutable(false, false)) {
            "Failed to set ROOT configuration execute permissions"
        }
        file.outputStream().use { it.write(content) }
    }
}
