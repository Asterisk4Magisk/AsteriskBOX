// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package engine.vpn

import android.app.Application
import android.os.Build
import java.io.File

/** The dedicated process that hosts [AsteriskVpnService] and the sing-box core it runs. */
internal const val VpnServiceProcessName = ":vpn"

internal fun Application.isVpnServiceProcess(): Boolean {
    return currentProcessName().endsWith(VpnServiceProcessName)
}

private fun Application.currentProcessName(): String {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        // Static helper: returns the name the system assigned, including the ":vpn" suffix.
        Application.getProcessName()?.takeIf { name -> name.isNotEmpty() }?.let { return it }
    }
    val commandLine = runCatching { File("/proc/self/cmdline").readBytes() }.getOrNull()
    if (commandLine != null && commandLine.isNotEmpty()) {
        return String(commandLine, Charsets.UTF_8).trimEnd('\u0000')
    }
    return packageName
}
