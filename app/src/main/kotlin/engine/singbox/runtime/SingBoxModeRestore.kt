// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package engine.singbox.runtime

import app.AppState
import engine.singbox.singBoxControlConfig
import engine.singbox.singBoxModeName
import io.nekohasekai.libbox.StatusMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.milliseconds

/** ROOT startup also runs without a dashboard/runtime repository (for example, from a tile). */
internal suspend fun restoreRootSingBoxMode(appState: AppState) = withContext(Dispatchers.IO) {
    val client = SingBoxCommandClient(
        target = SingBoxCommandTarget(local = false, control = appState.singBoxControlConfig()),
        listener = object : SingBoxCommandListener {
            override fun onConnected() = Unit
            override fun onDisconnected(message: String) = Unit
            override fun onStatus(status: StatusMessage) = Unit
            override fun onProxies(proxies: SingBoxProxiesState) = Unit
            override fun onConnections(connections: SingBoxConnectionsState) = Unit
        },
    )
    try {
        // asteriskd may report the child as running just before its API starts listening.
        for (attempt in 0 until 10) {
            try {
                client.connect(modeOnly = true)
                break
            } catch (error: Exception) {
                if (error is CancellationException || attempt == 9) throw error
                delay(500L.milliseconds)
            }
        }
        client.setMode(appState.singBoxModeName())
    } finally {
        client.disconnect()
    }
}
