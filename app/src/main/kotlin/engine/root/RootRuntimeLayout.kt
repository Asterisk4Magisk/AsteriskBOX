// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package engine.root

import android.content.Context
import features.resources.runtime.SingBoxResourceFilePaths
import features.resources.runtime.prepareSingBoxResourceFilePaths
import java.io.File

internal data class RootRuntimeLayout(
    val configPath: String,
    val singBoxCorePath: String,
    val asteriskdPath: String,
    val bpfMatcherPath: String,
    val bpf2socksPath: String,
    val hevSocks5TunnelPath: String,
    val dataDir: String,
    val pidPath: String,
)

internal fun Context.prepareRootRuntimeLayout(): RootRuntimeLayout {
    val resourceFilePaths = prepareSingBoxResourceFilePaths()
    return resourceFilePaths.toRootRuntimeLayout()
}

internal fun SingBoxResourceFilePaths.toRootRuntimeLayout(): RootRuntimeLayout {
    val dir = File(dataDir)
    return RootRuntimeLayout(
        configPath = File(dir, RootConfigFileName).absolutePath,
        singBoxCorePath = singBoxCorePath,
        asteriskdPath = asteriskdPath,
        bpfMatcherPath = bpfMatcherPath,
        bpf2socksPath = bpf2socksPath,
        hevSocks5TunnelPath = hevSocks5TunnelPath,
        dataDir = dataDir,
        pidPath = File(dir, RootPidFileName).absolutePath,
    )
}
