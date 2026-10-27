// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.resources.runtime

import app.ProjectInfo
import engine.network.ProxyCredentials
import engine.network.isPort
import engine.network.toSocksProxy
import engine.proxy.LocalProxyLoopbackAddress
import engine.proxy.LocalProxyRuntime
import features.resources.ResourceFileUpdateOptions
import java.net.HttpURLConnection
import java.net.URI

internal data class AndroidResourceHttpProxy(
    override val host: String,
    override val port: Int,
    override val username: String,
    override val password: String,
) : ProxyCredentials

internal fun ResourceFileUpdateOptions.toHttpProxy(): AndroidResourceHttpProxy? {
    if (!useRunningProxy) return null
    val runtimeOptions = LocalProxyRuntime.current()
    val port = runtimeOptions?.port
        ?: fallbackProxyPort?.takeIf(Int::isPort)
        ?: error("Local proxy port is unavailable")
    return AndroidResourceHttpProxy(
        host = LocalProxyLoopbackAddress,
        port = port,
        username = runtimeOptions?.username ?: fallbackProxyUsername,
        password = runtimeOptions?.password ?: fallbackProxyPassword,
    )
}

internal fun URI.toHttpConnection(
    proxy: AndroidResourceHttpProxy?,
    headers: Map<String, String> = emptyMap(),
): HttpURLConnection {
    val connection = if (proxy == null) {
        toURL().openConnection()
    } else {
        toURL().openConnection(proxy.toSocksProxy())
    }
    return (connection as HttpURLConnection).apply {
        connectTimeout = 15_000
        readTimeout = 60_000
        instanceFollowRedirects = true
        requestMethod = "GET"
        setRequestProperty("User-Agent", ResourceFileDefaultUserAgent)
        headers.forEach { (name, value) -> setRequestProperty(name, value) }
    }
}

private const val ResourceFileDefaultUserAgent = "${ProjectInfo.PROJECT_NAME}/v${ProjectInfo.VERSION_NAME}"
