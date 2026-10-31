// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package engine.vpn

import android.app.Application
import android.os.Build
import features.logs.AndroidAppLogger
import java.io.File

/** The dedicated process that hosts [AsteriskVpnService] and the sing-box core it runs. */
internal const val VpnServiceProcessName = ":vpn"

private const val LogTag = "VpnServiceProcess"

internal fun Application.isVpnServiceProcess(): Boolean {
    return currentProcessName() == packageName + VpnServiceProcessName
}

private fun Application.currentProcessName(): String {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        // Static helper: returns the name the system assigned, including the ":vpn" suffix.
        Application.getProcessName()?.takeIf { name -> name.isNotEmpty() }?.let { return it }
    }
    val commandLine = runCatching { File("/proc/self/cmdline").readBytes() }.getOrNull()
    if (commandLine != null && commandLine.isNotEmpty()) {
        // argv is NUL separated; only the first entry is the process name.
        return String(commandLine, Charsets.UTF_8).substringBefore('\u0000')
    }
    // Never fail silently: answering "false" here would re-open the state store and the state
    // schedulers inside the VPN service process.
    AndroidAppLogger.platformWarn(LogTag, "Unable to resolve the current process name; assuming the UI process")
    return packageName
}
