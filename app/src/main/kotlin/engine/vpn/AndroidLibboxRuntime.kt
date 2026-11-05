// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package engine.vpn

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import engine.singbox.plugin.PluginEnvelopeFileName
import features.logs.AndroidAppLogger
import io.nekohasekai.libbox.CommandServer
import io.nekohasekai.libbox.CommandServerHandler
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.OverrideOptions
import io.nekohasekai.libbox.SetupOptions
import io.nekohasekai.libbox.SystemProxyStatus
import java.io.File
import java.util.Locale

internal object AndroidLibboxRuntime {
    @Volatile
    private var initialized = false

    @Synchronized
    fun setup(context: Context) {
        if (initialized) return

        val appContext = context.applicationContext
        val baseDir = File(appContext.filesDir, "sing-box").apply { mkdirs() }
        val workingDir = File(baseDir, "runtime").apply { mkdirs() }
        val tempDir = File(appContext.cacheDir, "sing-box").apply { mkdirs() }
        val debuggable = appContext.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

        Libbox.setLocale(Locale.getDefault().toLanguageTag())
        Libbox.setup(
            SetupOptions().apply {
                basePath = baseDir.absolutePath
                workingPath = workingDir.absolutePath
                tempPath = tempDir.absolutePath
                fixAndroidStack = debuggable || Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                logMaxLines = 3_000
                debug = debuggable
                crashReportSource = "AsteriskBOX"
            },
        )
        initialized = true
        AndroidAppLogger.info(LogTag, "AndroidLibBoxLite initialized")
    }

    private const val LogTag = "AndroidLibboxRuntime"
}

internal class AndroidLibboxServiceRuntime(
    private val platformInterface: AndroidLibboxPlatformInterface,
    private val onStopRequested: () -> Unit,
) : CommandServerHandler {
    private var commandServer: CommandServer? = null
    private var activeConfig: VpnServiceStartConfig? = null

    @Synchronized
    fun start(config: VpnServiceStartConfig) {
        check(commandServer == null) { "AndroidLibBoxLite service is already running" }
        val configFile = File(config.singBoxConfigPath)
        require(configFile.isFile && configFile.length() > 0L) {
            "sing-box configuration file is unavailable"
        }
        val configContent = configFile.readText()
        require(configContent.isNotBlank()) { "sing-box configuration is empty" }

        platformInterface.prepare(config)
        val server = CommandServer(this, platformInterface)
        runCatching {
            server.start()
            server.startOrReloadServiceWithPlugins(
                configContent,
                config.toOverrideOptions(platformInterface),
                config.pluginEnvelope(),
            )
        }.onFailure {
            runCatching { server.closeService() }
            runCatching { server.close() }
            platformInterface.closeTun()
            throw it
        }
        activeConfig = config
        commandServer = server
        AndroidAppLogger.info(LogTag, "Started AndroidLibBoxLite service with ${configFile.absolutePath}")
    }

    @Synchronized
    fun reload() {
        val server = commandServer ?: error("AndroidLibBoxLite service is not running")
        val config = activeConfig ?: error("AndroidLibBoxLite configuration is unavailable")
        val content = File(config.singBoxConfigPath).readText()
        // An empty envelope also stops the plugins the previous config ran.
        server.startOrReloadServiceWithPlugins(
            content,
            config.toOverrideOptions(platformInterface),
            config.pluginEnvelope(),
        )
    }

    @Synchronized
    fun stop() {
        val server = commandServer
        commandServer = null
        activeConfig = null
        if (server != null) {
            runCatching { server.closeService() }
                .onFailure { error -> AndroidAppLogger.warn(LogTag, "Failed to close sing-box service", error) }
            runCatching { server.close() }
                .onFailure { error -> AndroidAppLogger.warn(LogTag, "Failed to close sing-box command server", error) }
        }
        platformInterface.closeTun()
    }

    override fun serviceStop() {
        onStopRequested()
    }

    override fun serviceReload() {
        reload()
    }

    override fun getSystemProxyStatus(): SystemProxyStatus =
        SystemProxyStatus().apply {
            available = false
            enabled = false
        }

    override fun setSystemProxyEnabled(isEnabled: Boolean) {
        reload()
    }

    override fun connectSSHAgent(): Int = -1

    override fun triggerNativeCrash() {
        error("Native crash requested by sing-box")
    }

    override fun writeDebugMessage(message: String?) {
        AndroidAppLogger.info(LogTag, message.orEmpty())
    }

    private companion object {
        const val LogTag = "AndroidLibboxService"
    }
}

private fun VpnServiceStartConfig.toOverrideOptions(
    platformInterface: AndroidLibboxPlatformInterface,
): OverrideOptions = OverrideOptions().apply {
    val override = platformInterface.applicationOverride(applicationPolicy)
    includePackage = override.includePackages.toStringIterator()
    excludePackage = override.excludePackages.toStringIterator()
}

internal data class VpnApplicationOverride(
    val includePackages: List<String> = emptyList(),
    val excludePackages: List<String> = emptyList(),
)

/** The plugin envelope the app process published beside the config, compiled with it. */
private fun VpnServiceStartConfig.pluginEnvelope(): String =
    File(File(singBoxConfigPath).parentFile, PluginEnvelopeFileName).takeIf(File::isFile)?.readText().orEmpty()
