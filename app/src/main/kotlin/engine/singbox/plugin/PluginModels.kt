// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package engine.singbox.plugin

import java.io.File
import java.io.FileNotFoundException

/** Local listen address every plugin exposes its SOCKS inbound on. */
internal const val PluginSocksHost = "127.0.0.1"

/** SagerNet-era contract; husi and its plugins keep the same identifiers. */
internal object PluginContract {
    const val ActionNativePlugin = "io.nekohasekai.sagernet.plugin.ACTION_NATIVE_PLUGIN"
    const val MetadataKeyId = "io.nekohasekai.sagernet.plugin.id"
    const val MetadataKeyExecutablePath = "io.nekohasekai.sagernet.plugin.executable_path"
}

/** One installed plugin package the app can run. */
internal data class InstalledPlugin(
    val pluginId: String,
    val packageName: String,
    val versionName: String,
    val publisher: String,
    val executable: File,
)

/** Plugin requirement declared by an outbound type (see [PluginOutbounds]). */
internal data class PluginRequirement(
    val outboundType: String,
    val pluginId: String,
    val displayName: String,
    val downloadUrl: String,
    /** False when the plugin binary cannot hand sockets back (VPN needs mapping). */
    val canSelfProtect: Boolean = true,
)

/** [message] is user-facing: a start fails with it. */
internal class PluginNotFoundException(
    val requirement: PluginRequirement,
    message: String,
) : FileNotFoundException(message)

/** Known plugin-backed outbound types, in display order. */
internal object PluginOutbounds {
    val mieru = PluginRequirement(
        outboundType = "mieru",
        pluginId = "mieru-plugin",
        displayName = "Mieru",
        downloadUrl = "https://github.com/xchacha20-poly1305/husi/releases?q=plugin-mieru",
    )
    val shadowQuic = PluginRequirement(
        outboundType = "shadowquic",
        pluginId = "shadowquic-plugin",
        displayName = "ShadowQUIC",
        downloadUrl = "https://github.com/xchacha20-poly1305/husi/releases?q=plugin-shadowquic",
    )
    val juicity = PluginRequirement(
        outboundType = "juicity",
        pluginId = "juicity-plugin",
        displayName = "Juicity",
        downloadUrl = "https://github.com/xchacha20-poly1305/husi/releases?q=plugin-juicity",
    )
    val hysteria2 = PluginRequirement(
        outboundType = "hysteria2",
        pluginId = "hysteria2-plugin",
        displayName = "Hysteria2",
        downloadUrl = "https://github.com/xchacha20-poly1305/husi/releases?q=plugin-hysteria2",
    )
    val naive = PluginRequirement(
        outboundType = "naive",
        pluginId = "naive-plugin",
        displayName = "NaiveProxy",
        downloadUrl = "https://github.com/klzgrad/naiveproxy/releases",
        // NaiveProxy has no protect-socket handoff, so its sockets must stay on
        // loopback and the core dials the real server.
        canSelfProtect = false,
    )

    /** Types that only exist as plugins. */
    val pluginOnly: List<PluginRequirement> = listOf(mieru, shadowQuic, juicity)

    /** Types the core also implements; they use the plugin only when the user switches them. */
    val switchable: List<PluginRequirement> = listOf(hysteria2, naive)

    val all: List<PluginRequirement> = pluginOnly + switchable

    /**
     * The plugin that serves [outboundType], or null when the core serves it.
     * [pluginProtocols] lists the switchable types moved to their plugin.
     */
    fun requirementFor(outboundType: String, pluginProtocols: Collection<String>): PluginRequirement? =
        pluginOnly.firstOrNull { requirement -> requirement.outboundType == outboundType }
            ?: switchable.firstOrNull { requirement ->
                requirement.outboundType == outboundType && outboundType in pluginProtocols
            }
}
