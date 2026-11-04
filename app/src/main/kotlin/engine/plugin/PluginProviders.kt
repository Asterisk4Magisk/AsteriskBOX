// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package engine.plugin

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Which implementation serves a protocol the core also knows. */
internal enum class ProtocolProvider(val wireValue: String) {
    Core("core"),
    Plugin("plugin"),
    ;

    companion object {
        fun fromWire(value: String?): ProtocolProvider =
            entries.firstOrNull { provider -> provider.wireValue == value } ?: Core
    }
}

/** Protocols with both a core and a plugin implementation (husi's provider switch). */
internal object PluginProviderProtocols {
    const val Hysteria2 = "hysteria2"
    const val Naive = "naive"

    val all = listOf(Hysteria2, Naive)
}

/**
 * Per-protocol "sing-box or plugin" choice. Kept in its own preferences file
 * instead of AppState: it only affects config compilation, never UI state, and
 * keeping it out of the state store avoids a schema bump.
 */
internal object PluginProviderPreferences {
    private const val PreferencesName = "plugin_providers"
    private const val KeyPrefix = "provider_"

    private val cache = HashMap<String, MutableStateFlow<ProtocolProvider>>()

    fun get(context: Context, protocol: String): ProtocolProvider = flow(context, protocol).value

    @Synchronized
    fun set(context: Context, protocol: String, provider: ProtocolProvider) {
        // Persist first: a crash between the two writes must not leave the UI
        // showing a provider that the next compile will not use.
        prefs(context).edit { putString(KeyPrefix + protocol, provider.wireValue) }
        flow(context, protocol).value = provider
    }

    fun pluginBacked(context: Context, protocol: String): Boolean =
        get(context, protocol) == ProtocolProvider.Plugin

    @Synchronized
    fun resetCache() {
        cache.clear()
    }

    @Synchronized
    fun flow(context: Context, protocol: String): MutableStateFlow<ProtocolProvider> =
        cache.getOrPut(protocol) {
            MutableStateFlow(
                ProtocolProvider.fromWire(
                    prefs(context).getString(KeyPrefix + protocol, null),
                ),
            )
        }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PreferencesName, Context.MODE_PRIVATE)
}
