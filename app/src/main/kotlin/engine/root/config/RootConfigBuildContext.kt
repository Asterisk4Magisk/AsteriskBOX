// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package engine.root.config

import android.content.Context
import app.AppState
import app.effectiveFakeIpEnabled
import app.effectiveLocalDnsEnabled
import engine.network.toPortOrNull
import engine.proxy.ProxyEngineStartRequest
import engine.network.normalizedFakeIpIpv4Pool
import engine.singbox.SingBoxConfigFactory
import engine.singbox.prepareSingBoxCoreLogPaths
import features.resources.runtime.SingBoxResourceFilePaths
import features.resources.runtime.singBoxResourceFilePaths
import java.io.File

internal class RootConfigBuildContext(
    private val androidContext: Context,
    val appState: AppState,
    val resourceFilePaths: SingBoxResourceFilePaths,
) {
    fun buildRootStartConfig(): RootStartConfig {
        return appState.toRootStartConfig(
            singBoxConfigBytes = SingBoxConfigFactory.buildConfigBytes(androidContext, appState),
            resourceFilePaths = resourceFilePaths,
        )
    }

    fun buildRootIptablesConfig(): RootIptablesConfig {
        return RootIptablesConfig().withAppSettings(context = androidContext, appState = appState)
    }
}

internal fun Context.prepareRootConfigBuildContext(request: ProxyEngineStartRequest): RootConfigBuildContext {
    applicationContext.prepareSingBoxCoreLogPaths()
    return RootConfigBuildContext(
        androidContext = applicationContext,
        appState = request.appState,
        resourceFilePaths = singBoxResourceFilePaths(),
    )
}

private fun AppState.toRootStartConfig(
    singBoxConfigBytes: ByteArray,
    resourceFilePaths: SingBoxResourceFilePaths,
): RootStartConfig {
    val dataDirectory = File(resourceFilePaths.dataDir)
    return RootStartConfig(
        singBoxConfigBytes = singBoxConfigBytes,
        runtimePaths = RootConfigRuntimePaths(
            coreExecutablePath = resourceFilePaths.singBoxCorePath,
            coreConfigPath = File(dataDirectory, "config.json").absolutePath,
            matcherExecutablePath = resourceFilePaths.bpfMatcherPath,
            bpf2SocksExecutablePath = resourceFilePaths.bpf2socksPath,
            hevSocks5TunnelExecutablePath = resourceFilePaths.hevSocks5TunnelPath,
            workingDirectory = resourceFilePaths.dataDir,
            statePath = File(dataDirectory, "asteriskd.state").absolutePath,
            logPath = File(File(dataDirectory, "logs"), "asteriskd.log").absolutePath,
        ),
        directCidrIpv4Path = resourceFilePaths.directCidrIpv4Path,
        directCidrIpv6Path = resourceFilePaths.directCidrIpv6Path,
        enableIpv6 = enableIpv6,
        enableRootIpv6Disabler = enableRootIpv6Disabler,
        enableAppListEnhancement = enableAppListEnhancement,
        enableLocalDns = effectiveLocalDnsEnabled,
        enableFakeIp = effectiveFakeIpEnabled,
        fakeIpIpv4Pool = rootFakeIpIpv4Pool(),
        enableBoot = enableRootBootScript,
        serviceControl = serviceControl,
    )
}

internal fun AppState.tun2SocksInternalProxyPortValue(): Int {
    return socks5ProxyPort.toPortOrNull() ?: DefaultRootTun2SocksProxyPort
}

internal fun AppState.bpf2SocksBridgePortValue(): Int {
    return bpf2SocksBridgePort.toPortOrNull() ?: RootBpf2SocksDefaultBridgePort
}

private fun AppState.rootFakeIpIpv4Pool(): String = dnsFakeIpRange.normalizedFakeIpIpv4Pool()
