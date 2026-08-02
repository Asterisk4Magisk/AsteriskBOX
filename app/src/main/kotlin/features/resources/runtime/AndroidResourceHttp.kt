// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.resources.runtime

import engine.network.isPort
import engine.proxy.LocalProxyLoopbackAddress
import engine.proxy.LocalProxyRuntime
import features.resources.ResourceFileUpdateOptions
import java.net.Authenticator
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.PasswordAuthentication
import java.net.Proxy
import java.net.URI

internal data class AndroidResourceHttpProxy(
    val host: String,
    val port: Int,
    val username: String,
    val password: String,
)

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
        toURL().openConnection(proxy.toJavaProxy())
    }
    return (connection as HttpURLConnection).apply {
        connectTimeout = 15_000
        readTimeout = 60_000
        instanceFollowRedirects = true
        requestMethod = "GET"
        headers.forEach { (name, value) -> setRequestProperty(name, value) }
    }
}

internal inline fun <T> AndroidResourceHttpProxy?.withAuthenticator(block: () -> T): T {
    if (this == null || username.isBlank()) return block()
    synchronized(ProxyAuthenticatorLock) {
        Authenticator.setDefault(toAuthenticator())
        return try {
            block()
        } finally {
            Authenticator.setDefault(null)
        }
    }
}

private fun AndroidResourceHttpProxy.toJavaProxy(): Proxy {
    return Proxy(Proxy.Type.SOCKS, InetSocketAddress(host, port))
}

private fun AndroidResourceHttpProxy.toAuthenticator(): Authenticator {
    return object : Authenticator() {
        override fun getPasswordAuthentication(): PasswordAuthentication? {
            if (requestingHost != host || requestingPort != port) return null
            return PasswordAuthentication(username, password.toCharArray())
        }
    }
}

private val ProxyAuthenticatorLock = Any()
