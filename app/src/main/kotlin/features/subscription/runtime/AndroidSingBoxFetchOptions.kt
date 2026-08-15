// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.subscription.runtime

import engine.proxy.LocalProxyLoopbackAddress
import engine.proxy.LocalProxyRuntime
import java.net.InetSocketAddress
import java.net.Proxy

internal data class AndroidSubscriptionFetchOptions(
    val useRunningProxy: Boolean = false,
    val hwid: String = "",
)

internal data class AndroidSubscriptionProxy(
    val proxy: Proxy,
    val username: String,
    val password: String,
)

internal fun toSubscriptionFetchOptions(
    useRunningProxy: Boolean,
    hwid: String = "",
): AndroidSubscriptionFetchOptions = AndroidSubscriptionFetchOptions(
    useRunningProxy = useRunningProxy,
    hwid = hwid,
)

internal fun AndroidSubscriptionFetchOptions.toHttpProxy(): AndroidSubscriptionProxy? {
    if (!useRunningProxy) return null
    val runtimeOptions = LocalProxyRuntime.current() ?: return null
    return AndroidSubscriptionProxy(
        proxy = Proxy(
            Proxy.Type.HTTP,
            InetSocketAddress(LocalProxyLoopbackAddress, runtimeOptions.port),
        ),
        username = runtimeOptions.username,
        password = runtimeOptions.password,
    )
}
