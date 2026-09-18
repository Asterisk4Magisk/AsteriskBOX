// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import features.settings.sheets.NetworkQualitySettingsSheet
import features.settings.sheets.NetworkQualityTestSheet
import features.settings.sheets.rememberNetworkQualityTestController

/**
 * CompositionLocal for the app-level sheet state. The Settings page and
 * the home dashboard share the same state so the network quality popup is
 * rendered exactly once at the app shell level.
 */
internal val LocalHomeShellSheetState =
    compositionLocalOf<HomeShellSheetState> { error("No HomeShellSheetState provided") }

internal class HomeShellSheetState {
    var showNetworkQualityTest: Boolean by mutableStateOf(false)
        private set

    fun openNetworkQualityTest() {
        showNetworkQualityTest = true
    }

    fun closeNetworkQualityTest() {
        showNetworkQualityTest = false
    }
}

@Composable
internal fun rememberHomeShellSheetState(): HomeShellSheetState {
    return remember { HomeShellSheetState() }
}

/**
 * Renders the network quality popup at app shell level using the shared
 * [HomeShellSheetState] so the home dashboard and the Settings page can
 * open the same sheet without a duplicated implementation. The popup owns
 * its own controller (parameters, outbound selection, report) and renders
 * exactly once even though it is reachable from multiple pages.
 *
 * Must be called inside a Composition that provides [LocalAppServices].
 */
@Composable
internal fun HomeShellSheetsHost(
    state: HomeShellSheetState,
    content: @Composable () -> Unit,
) {
    val controller = rememberNetworkQualityTestController()
    CompositionLocalProvider(LocalHomeShellSheetState provides state) {
        content()
    }
    NetworkQualityTestSheet(
        controller = controller,
        show = state.showNetworkQualityTest,
        onDismiss = {
            controller.close()
            state.closeNetworkQualityTest()
        },
    )
    NetworkQualitySettingsSheet(
        controller = controller,
        onDismissRequest = { controller.showSettings = false },
    )
}
