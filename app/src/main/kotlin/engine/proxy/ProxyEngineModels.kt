// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package engine.proxy

import android.content.Intent
import app.AppState
import engine.root.runtime.model.RootRuntimeOwner
import engine.root.runtime.model.RootRuntimeSnapshot

/** Asks the user for VPN consent; returns whether it was granted. */
typealias VpnPermissionRequester = suspend (Intent) -> Boolean

/** Used by entry points that cannot show the system VPN consent dialog. */
val DenyVpnPermission: VpnPermissionRequester = { false }

internal data class ProxyEngineStartRequest(
    val appState: AppState,
)

data class ProxyEngineStatus(
    val running: Boolean,
    val runMode: Int? = null,
    val appState: AppState? = null,
    val rootSnapshot: RootRuntimeSnapshot? = null,
) {
    companion object {
        fun fromRootSnapshot(
            localOwner: RootRuntimeOwner,
            runMode: Int,
            snapshot: RootRuntimeSnapshot,
        ): ProxyEngineStatus = ProxyEngineStatus(
            running = snapshot.owner == localOwner && snapshot.running,
            runMode = runMode,
            rootSnapshot = snapshot,
        )
    }
}
