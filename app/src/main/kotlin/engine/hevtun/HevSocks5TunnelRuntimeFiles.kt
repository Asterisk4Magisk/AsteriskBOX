// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package engine.hevtun

import android.content.Context
import engine.singbox.prepareSingBoxCoreLogPaths

internal fun Context.deleteHevSocks5TunnelLogFile() {
    val file = applicationContext.prepareSingBoxCoreLogPaths().hevSocks5TunnelLogFile(HevSocks5TunnelLogFileName)
    if (file.exists() && !file.delete()) {
        error("Failed to delete ${file.absolutePath}")
    }
}
