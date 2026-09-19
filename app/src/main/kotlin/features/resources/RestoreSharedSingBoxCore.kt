// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.resources

import app.AppServices
import app.AppState
import app.ResourceFileKind
import app.ResourceFilesStatus
import app.modes.isRootRunMode
import features.settings.usecase.RootBootScriptResult

internal suspend fun AppServices.replaceSingBoxCore(
    currentState: () -> AppState,
    onRootStopped: () -> Unit,
): ResourceFilesStatus? {
    val uri = importFilePicker() ?: return null
    val state = currentState()
    return proxyEngine.changeRootCore(state.runMode, onRootStopped) {
        val status = resourceFileUseCase.replaceSingBoxCore(uri, state.customResourceFiles)
        refreshSingBoxCoreBoot(state.copy(proxyRunning = false))
        status
    }
}

internal suspend fun AppServices.restoreSharedSingBoxCore(
    state: AppState,
    onRootStopped: () -> Unit,
): ResourceFilesStatus = proxyEngine.changeRootCore(state.runMode, onRootStopped) {
    val status = resourceFileUseCase.restoreBundled(ResourceFileKind.SingBoxCore, state.customResourceFiles)
    refreshSingBoxCoreBoot(state.copy(proxyRunning = false))
    status
}

internal suspend fun AppServices.refreshSingBoxCoreBoot(state: AppState) {
    if (state.runMode.isRootRunMode() && state.enableRootBootScript) {
        when (val result = rootBootScriptUseCase.refresh(state)) {
            RootBootScriptResult.Success -> Unit
            is RootBootScriptResult.Failed -> throw result.error
            else -> error("Failed to refresh ROOT boot configuration after changing Core: $result")
        }
    }
}
