// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.quicksettings

import android.content.ComponentName
import android.content.Context
import android.service.quicksettings.TileService
import app.AppState
import app.modes.SingBoxModeDirect
import engine.singbox.config.APP_GLOBAL_SELECTOR
import engine.singbox.runtime.SingBoxRuntimeState
import features.logs.AndroidAppLogger
import features.singbox.activeSingBoxNodeTag
import java.util.concurrent.atomic.AtomicReference

private const val LogTag = "QuickSettingsTileRefresh"

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

private val tileShownState = AtomicReference<QuickSettingsTileState?>(null)

/**
 * Marks the state that the tile is about to write to the store and then show itself, so the
 * state observer does not rebind the tile only to read the same status again. Call it before
 * the write: the observer runs on another thread and may see the write at once.
 */
internal fun markQuickSettingsTileShowing(state: QuickSettingsTileState) {
    tileShownState.set(state)
}

/**
 * Called by the state observer for every distinct tile state. Every call consumes the mark,
 * so a mark the observer never saw (the write was conflated or changed nothing) cannot skip a
 * later state.
 */
internal fun refreshQuickSettingsTileUnlessShowing(context: Context, state: QuickSettingsTileState) {
    if (tileShownState.getAndSet(null) == state) return
    requestQuickSettingsTileRefresh(context)
}

/** Asks SystemUI to bind the tile, which then reads the current proxy status itself. */
internal fun requestQuickSettingsTileRefresh(context: Context) {
    runCatching {
        TileService.requestListeningState(
            context,
            ComponentName(context, ProxyQuickSettingsTileService::class.java),
        )
    }.onFailure { error ->
        AndroidAppLogger.warn(LogTag, "Failed to request quick settings tile refresh", error)
    }
}
