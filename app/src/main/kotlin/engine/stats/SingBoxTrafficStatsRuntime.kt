// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package engine.stats

import app.AppState
import app.modes.RunModeVpnService
import engine.singbox.SingBoxControlConfig
import engine.singbox.singBoxControlConfig

internal data class SingBoxTrafficStatsRuntime(
    val control: SingBoxControlConfig,
    val local: Boolean,
)

internal fun AppState.toSingBoxTrafficStatsRuntime(
    runMode: Int = this.runMode,
): SingBoxTrafficStatsRuntime? {
    if (!enableTrafficStatsNotification) return null
    if (runMode != RunModeVpnService) return null
    return SingBoxTrafficStatsRuntime(
        control = singBoxControlConfig(),
        local = true,
    )
}
