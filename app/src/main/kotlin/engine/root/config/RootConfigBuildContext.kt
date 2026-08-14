// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package engine.root.config

import android.content.Context
import app.AppState
import app.effectiveFakeIpEnabled
import app.effectiveLocalDnsEnabled
import engine.network.parseCidrAddressOrNull
import engine.network.toPortOrNull
import engine.proxy.ProxyEngineStartRequest
import engine.singbox.DefaultSingBoxDnsFakeIpRange
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
            publicationStagingDirectory = androidContext.cacheDir.absolutePath,
            resourceFilePaths = resourceFilePaths,
        )
    }

    fun buildRootIptablesConfig(base: RootIptablesConfig): RootIptablesConfig {
        return base.withAppSettings(context = androidContext, appState = appState)
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
    publicationStagingDirectory: String,
    resourceFilePaths: SingBoxResourceFilePaths,
): RootStartConfig {
    val dataDirectory = File(resourceFilePaths.dataDir)
    return RootStartConfig(
        singBoxConfigBytes = singBoxConfigBytes,
        publicationStagingDirectory = publicationStagingDirectory,
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
        enableLocalDns = effectiveLocalDnsEnabled,
        enableFakeIp = effectiveFakeIpEnabled,
        fakeIpIpv4Pool = rootFakeIpIpv4Pool(),
        enableBoot = enableRootBootScript,
    )
}

internal fun AppState.tun2SocksInternalProxyPortValue(): Int {
    return socks5ProxyPort.toPortOrNull() ?: DefaultRootTun2SocksProxyPort
}

internal fun AppState.bpf2SocksBridgePortValue(): Int {
    return bpf2SocksBridgePort.toPortOrNull() ?: RootBpf2SocksDefaultBridgePort
}

private fun AppState.rootFakeIpIpv4Pool(): String {
    return dnsServers
        .firstOrNull { server -> server.type == "fakeip" }
        ?.inet4Range
        ?.normalizedIpv4CidrOrNull()
        ?: DefaultSingBoxDnsFakeIpRange.normalizedIpv4CidrOrNull()
        ?: "198.18.0.0/16"
}

private fun String.normalizedIpv4CidrOrNull(): String? {
    val cidr = parseCidrAddressOrNull(this) ?: return null
    if (":" in cidr.address) return null
    val octets = cidr.address.split(".")
    if (octets.size != 4) return null
    var addressValue = 0L
    octets.forEach { octet ->
        val value = octet.toIntOrNull() ?: return null
        addressValue = (addressValue shl 8) or value.toLong()
    }
    val mask = if (cidr.prefixLength == 0) 0L else (0xffffffffL shl (32 - cidr.prefixLength)) and 0xffffffffL
    val network = addressValue and mask
    val address = listOf(
        (network shr 24) and 0xff,
        (network shr 16) and 0xff,
        (network shr 8) and 0xff,
        network and 0xff,
    ).joinToString(".")
    return "$address/${cidr.prefixLength}"
}
