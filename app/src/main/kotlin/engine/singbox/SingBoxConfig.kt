// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package engine.singbox

import android.content.Context
import app.AppState
import app.modes.SingBoxModeDirect
import app.modes.SingBoxModeGlobal
import app.modes.SingBoxTunStackGvisor
import app.modes.SingBoxTunStackMixed
import engine.singbox.config.SingBoxConfigCompiler
import java.security.MessageDigest

internal object SingBoxConfigFactory {
    fun buildConfigBytes(
        context: Context,
        appState: AppState,
        runMode: Int = appState.runMode,
        exposePorts: Boolean = true,
    ): ByteArray = buildConfig(context, appState, runMode, exposePorts).toByteArray(Charsets.UTF_8)

    fun buildConfig(
        context: Context,
        appState: AppState,
        runMode: Int = appState.runMode,
        exposePorts: Boolean = true,
    ): String = SingBoxConfigCompiler.compile(context, appState, runMode, exposePorts)

    fun tunStack(appState: AppState): String =
        when (appState.singBoxTunStack) {
            SingBoxTunStackGvisor -> "gvisor"
            SingBoxTunStackMixed -> "mixed"
            else -> "system"
        }
}

internal fun ByteArray.sha256Hex(): String =
    MessageDigest.getInstance("SHA-256")
        .digest(this)
        .joinToString(separator = "") { byte -> "%02x".format(byte) }

internal fun AppState.singBoxModeName(): String = when (singBoxMode) {
    SingBoxModeGlobal -> "global"
    SingBoxModeDirect -> "direct"
    else -> "rule"
}
