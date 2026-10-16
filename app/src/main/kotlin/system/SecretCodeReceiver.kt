// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package system

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.AsteriskApplication
import kotlinx.coroutines.launch

/**
 * Restores the launcher icon when the user dials `*#*#260728#*#*`.
 *
 * The broadcast is dispatched system-wide by the dialer, not by our
 * package, so we can still recover when the launcher entry is hidden.
 *
 * Persists the preference change before finishing the broadcast so the
 * platform may safely reclaim the receiver process at any moment after
 * `pendingResult.finish()`.
 */
class SecretCodeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.data?.host != AndroidLauncherIcon.SECRET_CODE) return
        if (!AndroidLauncherIcon.isHidden(context)) return

        val pendingResult = goAsync()
        val application = context.applicationContext as? AsteriskApplication
        if (application == null) {
            try {
                AndroidLauncherIcon.setHidden(context, false)
            } finally {
                pendingResult.finish()
            }
            return
        }
        application.appScope.launch {
            try {
                AndroidLauncherIcon.setHidden(context, false)
                val current = application.stateStore.state.value
                if (current.hideLauncherIcon) {
                    application.stateStore
                        .replaceAllAndAwaitPersistence(current.copy(hideLauncherIcon = false))
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}