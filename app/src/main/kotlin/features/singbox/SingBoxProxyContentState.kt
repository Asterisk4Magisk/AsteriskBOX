// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.singbox

internal enum class SingBoxProxyContentState {
    ServiceStopped,
    Loading,
    Empty,
    Ready,
}

internal fun resolveSingBoxProxyContentState(
    serviceRunning: Boolean,
    hasProxyGroups: Boolean,
    refreshing: Boolean,
): SingBoxProxyContentState = when {
    !serviceRunning -> SingBoxProxyContentState.ServiceStopped
    hasProxyGroups -> SingBoxProxyContentState.Ready
    refreshing -> SingBoxProxyContentState.Loading
    else -> SingBoxProxyContentState.Empty
}
