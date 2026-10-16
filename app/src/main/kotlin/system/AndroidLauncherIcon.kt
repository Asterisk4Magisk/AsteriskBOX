// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package system

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/**
 * The launcher entry lives on an alias so it can be disabled to hide the
 * app from the launcher without breaking deep links, shortcuts or the
 * quick settings tile.
 *
 * Recover by dialling `*#*#260728#*#*` in any dialer.
 */
internal object AndroidLauncherIcon {

    private const val ALIAS = "app.ui.LauncherActivityAlias"

    /**
     * AsteriskBOX initial commit date (2026-07-28). Encoded into the dial
     * code that the SecretCodeReceiver listens for, so the user can
     * recover the launcher icon after hiding it.
     */
    const val SECRET_CODE = "260728"

    const val DIAL_CODE = "*#*#$SECRET_CODE#*#*"

    fun isHidden(context: Context): Boolean {
        val state = context.packageManager.getComponentEnabledSetting(component(context))
        return state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED
    }

    fun setHidden(context: Context, hidden: Boolean) {
        context.packageManager.setComponentEnabledSetting(
            component(context),
            if (hidden) {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            } else {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            },
            PackageManager.DONT_KILL_APP,
        )
    }

    private fun component(context: Context): ComponentName =
        ComponentName(context.packageName, ALIAS)
}