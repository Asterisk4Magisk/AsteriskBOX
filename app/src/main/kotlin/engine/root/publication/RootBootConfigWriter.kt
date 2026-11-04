// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package engine.root.publication

import java.io.File

internal object RootBootConfigWriter {
    fun write(
        layout: RootRuntimeLayout,
        coreConfigBytes: ByteArray,
        encodedDaemonConfig: String,
        pluginOptionsBytes: ByteArray,
    ) {
        // Reuse the owner-only writer: the envelope next to the config carries
        // plugin credentials in ROOT mode too.
        RootPublicationWriter.write(
            layout = layout,
            coreConfigBytes = coreConfigBytes,
            daemonConfigBytes = encodedDaemonConfig.toByteArray(Charsets.UTF_8),
            pluginOptionsBytes = pluginOptionsBytes,
        )
    }
}
