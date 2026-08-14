// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package engine.proxy

import app.AppState
import engine.root.runtime.model.RootRuntimeOwner
import engine.root.runtime.model.RootRuntimePhase
import engine.root.runtime.model.RootRuntimeSnapshot

internal data class ProxyEngineStartRequest(
    val appState: AppState,
)

data class ProxyEngineStatus(
    val running: Boolean,
    val runMode: Int? = null,
    val appState: AppState? = null,
    val activeRootOwner: RootRuntimeOwner? = null,
    val rootSnapshot: RootRuntimeSnapshot? = null,
) {
    companion object {
        fun fromRootSnapshot(
            localOwner: RootRuntimeOwner,
            runMode: Int,
            snapshot: RootRuntimeSnapshot,
        ): ProxyEngineStatus = ProxyEngineStatus(
            running = snapshot.owner == localOwner && snapshot.phase == RootRuntimePhase.Running,
            runMode = runMode,
            activeRootOwner = snapshot.owner,
            rootSnapshot = snapshot,
        )
    }
}
