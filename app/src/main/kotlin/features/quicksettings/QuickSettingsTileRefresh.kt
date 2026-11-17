// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.quicksettings

import app.AppState
import app.modes.SingBoxModeDirect
import engine.singbox.config.APP_GLOBAL_SELECTOR
import engine.singbox.runtime.SingBoxRuntimeState
import features.singbox.activeSingBoxNodeTag

/** The part of the app state the tile shows; the tile needs a refresh only when it changes. */
internal data class QuickSettingsTileState(
    val runMode: Int,
    val proxyRunning: Boolean,
    val languageMode: Int,
    val activeNodeTag: String?,
)

internal fun AppState.quickSettingsTileState(runtime: SingBoxRuntimeState): QuickSettingsTileState =
    QuickSettingsTileState(
        runMode = runMode,
        proxyRunning = proxyRunning,
        languageMode = languageMode,
        activeNodeTag = quickSettingsActiveNodeTag(this, runtime),
    )

/**
 * The node the tile names while the proxy runs. The live snapshot resolves nested groups down to
 * the node; without a runtime session (the tile started the service with no UI around) it falls
 * back to the persisted global selection, which is what the core restores. Nothing is cached on
 * disk, so the tile cannot outlive a stale node. Direct mode routes through no node at all.
 */
internal fun quickSettingsActiveNodeTag(state: AppState, runtime: SingBoxRuntimeState): String? {
    if (state.singBoxMode == SingBoxModeDirect) return null
    val live = if (runtime.running) activeSingBoxNodeTag(runtime.proxies) else null
    return live ?: state.selectorSelections[APP_GLOBAL_SELECTOR]?.takeIf { tag -> tag.isNotBlank() }
}
