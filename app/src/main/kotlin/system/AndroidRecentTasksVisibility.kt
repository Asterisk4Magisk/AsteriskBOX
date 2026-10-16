// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package system

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.view.WindowManager

internal fun Context.setRecentTasksHidden(hidden: Boolean) {
    getSystemService(ActivityManager::class.java).appTasks.forEach { task ->
        task.setExcludeFromRecents(hidden)
    }
}

/**
 * Toggle screenshot / screen-recording protection for the current window.
 *
 * When `allowed == false`, the system blocks screenshots (recents thumbnail
 * included on Tiramisu+) and screen recording for this window. Mirrors
 * husi's privacy-mode flag.
 */
internal fun android.app.Activity.applyScreenshotProtection(allowed: Boolean) {
    if (allowed) {
        window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    } else {
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        setRecentsScreenshotEnabled(allowed)
    }
}