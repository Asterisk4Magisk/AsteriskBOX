// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.subscription.runtime

import app.AppState
import engine.network.isPort
import engine.network.toPortOrNull
import engine.proxy.LocalProxyLoopbackAddress
import engine.proxy.LocalProxyRuntime
import java.net.InetSocketAddress
import java.net.Proxy

internal data class AndroidSubscriptionFetchOptions(
    val useRunningProxy: Boolean = false,
    val fallbackProxyPort: Int? = null,
    val fallbackProxyUsername: String = "",
    val fallbackProxyPassword: String = "",
    val hwid: String = "",
)

internal data class AndroidSubscriptionProxy(
    val proxy: Proxy,
    val username: String,
    val password: String,
)

internal fun AppState.toSubscriptionFetchOptions(
    useRunningProxy: Boolean,
    hwid: String = "",
): AndroidSubscriptionFetchOptions = AndroidSubscriptionFetchOptions(
    useRunningProxy = useRunningProxy,
    fallbackProxyPort = localProxyPort.toPortOrNull(),
    fallbackProxyUsername = localProxyUsername,
    fallbackProxyPassword = localProxyPassword,
    hwid = hwid,
)

internal fun AndroidSubscriptionFetchOptions.toHttpProxy(): AndroidSubscriptionProxy? {
    if (!useRunningProxy) return null
    val runtimeOptions = LocalProxyRuntime.current()
    val port = runtimeOptions?.port
        ?: fallbackProxyPort?.takeIf(Int::isPort)
        ?: error("Local proxy port is unavailable")
    return AndroidSubscriptionProxy(
        proxy = Proxy(
            Proxy.Type.HTTP,
            InetSocketAddress(LocalProxyLoopbackAddress, port),
        ),
        username = runtimeOptions?.username ?: fallbackProxyUsername,
        password = runtimeOptions?.password ?: fallbackProxyPassword,
    )
}
