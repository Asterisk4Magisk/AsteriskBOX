// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package engine.vpn

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.DnsResolver
import android.net.IpPrefix
import android.net.Network
import android.net.NetworkCapabilities
import android.net.ProxyInfo
import android.net.VpnService
import android.net.wifi.WifiManager
import android.os.Build
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.os.Process
import android.system.ErrnoException
import android.system.OsConstants
import app.modes.ProxyAppListModeBlacklist
import app.modes.ProxyAppListModeGlobal
import app.modes.ProxyAppListModeWhitelist
import engine.proxy.LocalProxyLoopbackAddress
import features.logs.AndroidAppLogger
import io.nekohasekai.libbox.BridgeOptions
import io.nekohasekai.libbox.BridgeSession
import io.nekohasekai.libbox.ConnectionOwner
import io.nekohasekai.libbox.ExchangeContext
import io.nekohasekai.libbox.InterfaceUpdateListener
import io.nekohasekai.libbox.LocalDNSTransport
import io.nekohasekai.libbox.NeighborUpdateListener
import io.nekohasekai.libbox.NetworkInterfaceIterator
import io.nekohasekai.libbox.Notification
import io.nekohasekai.libbox.PlatformInterface
import io.nekohasekai.libbox.PlatformUser
import io.nekohasekai.libbox.ShellSession
import io.nekohasekai.libbox.StringIterator
import io.nekohasekai.libbox.TunOptions
import io.nekohasekai.libbox.WIFIState
import system.getInstalledApplicationsCompat
import utils.toTrimmedNonEmptyDistinctList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.runBlocking
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.InterfaceAddress
import java.net.UnknownHostException
import java.net.NetworkInterface
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import io.nekohasekai.libbox.NetworkInterface as LibboxNetworkInterface

internal class AndroidLibboxPlatformInterface(
    private val service: VpnService,
) : PlatformInterface {
    private val connectivityManager =
        service.getSystemService(ConnectivityManager::class.java)
    private var activeConfig: VpnServiceStartConfig? = null
    private var tunFileDescriptor: ParcelFileDescriptor? = null
    private var defaultNetworkCallback: ConnectivityManager.NetworkCallback? = null

    fun prepare(config: VpnServiceStartConfig) {
        activeConfig = config
    }

    fun closeTun() {
        runCatching { tunFileDescriptor?.close() }
            .onFailure { error -> AndroidAppLogger.warn(LogTag, "Failed to close VPN TUN", error) }
        tunFileDescriptor = null
    }

    fun applicationOverride(policy: VpnApplicationPolicy): VpnApplicationOverride {
        val selfPackage = service.packageName
        return when (policy.mode) {
            ProxyAppListModeWhitelist -> {
                val included = policy.packageNames
                    .filterNot { it.trim() == selfPackage }
                    .toTrimmedNonEmptyDistinctList()
                if (included.isEmpty()) {
                    VpnApplicationOverride(
                        excludePackages = installedPackageNames(),
                    )
                } else {
                    VpnApplicationOverride(includePackages = included)
                }
            }

            ProxyAppListModeBlacklist -> VpnApplicationOverride(
                excludePackages = (policy.packageNames + selfPackage).toTrimmedNonEmptyDistinctList(),
            )

            ProxyAppListModeGlobal -> VpnApplicationOverride(
                excludePackages = listOf(selfPackage),
            )

            else -> VpnApplicationOverride()
        }
    }

    override fun usePlatformAutoDetectInterfaceControl(): Boolean = true

    override fun autoDetectInterfaceControl(fd: Int) {
        check(service.protect(fd)) { "android: failed to protect socket" }
    }

    override fun openTun(options: TunOptions): Int {
        check(VpnService.prepare(service) == null) { "android: missing VPN permission" }
        val config = activeConfig ?: error("android: VPN configuration is unavailable")
        val inet4Addresses = options.inet4Address.toList()
        val inet6Addresses = options.inet6Address.toList()

        val builder = service.Builder()
            .setSession(config.sessionName)
            .setMtu(options.mtu)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
        }
        (inet4Addresses + inet6Addresses).forEach { prefix ->
            builder.addAddress(prefix.address(), prefix.prefix())
        }

        if (options.autoRoute) {
            if (options.dnsMode.value != io.nekohasekai.libbox.Libbox.DNSModeDisabled) {
                options.dnsServerAddress.toList().forEach { address -> builder.addDnsServer(address) }
            }
            builder.applyRoutes(options, inet4Addresses.isNotEmpty(), inet6Addresses.isNotEmpty())
            options.includePackage.toList().forEachInstalledApplication { packageName ->
                builder.addAllowedApplication(packageName)
            }
            options.excludePackage.toList().forEachInstalledApplication { packageName ->
                builder.addDisallowedApplication(packageName)
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            when {
                options.isHTTPProxyEnabled -> {
                    builder.setHttpProxy(
                        ProxyInfo.buildDirectProxy(
                            options.httpProxyServer,
                            options.httpProxyServerPort,
                            options.httpProxyBypassDomain.toList(),
                        ),
                    )
                }

                config.appendHttpProxyOptions.enabled -> {
                    builder.setHttpProxy(
                        ProxyInfo.buildDirectProxy(
                            LocalProxyLoopbackAddress,
                            config.appendHttpProxyOptions.port,
                        ),
                    )
                }
            }
        }

        closeTun()
        val descriptor = builder.establish()
            ?: error("android: the application is not prepared or VPN permission was revoked")
        tunFileDescriptor = descriptor
        return descriptor.fd
    }

    private fun VpnService.Builder.applyRoutes(
        options: TunOptions,
        hasIpv4: Boolean,
        hasIpv6: Boolean,
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val inet4Routes = options.inet4RouteAddress.toList()
            val inet6Routes = options.inet6RouteAddress.toList()
            if (inet4Routes.isEmpty() && hasIpv4) {
                addRoute("0.0.0.0", 0)
            } else {
                inet4Routes.forEach { addRoute(IpPrefix(InetAddress.getByName(it.address()), it.prefix())) }
            }
            if (inet6Routes.isEmpty() && hasIpv6) {
                addRoute("::", 0)
            } else {
                inet6Routes.forEach { addRoute(IpPrefix(InetAddress.getByName(it.address()), it.prefix())) }
            }
            options.inet4RouteExcludeAddress.toList().forEach {
                excludeRoute(IpPrefix(InetAddress.getByName(it.address()), it.prefix()))
            }
            options.inet6RouteExcludeAddress.toList().forEach {
                excludeRoute(IpPrefix(InetAddress.getByName(it.address()), it.prefix()))
            }
        } else {
            options.inet4RouteRange.toList().forEach { addRoute(it.address(), it.prefix()) }
            options.inet6RouteRange.toList().forEach { addRoute(it.address(), it.prefix()) }
        }
    }

    override fun useProcFS(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q

    override fun findConnectionOwner(
        ipProtocol: Int,
        sourceAddress: String,
        sourcePort: Int,
        destinationAddress: String,
        destinationPort: Int,
    ): ConnectionOwner {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            error("android: connection owner lookup requires Android 10")
        }
        val uid = connectivityManager.getConnectionOwnerUid(
            ipProtocol,
            InetSocketAddress(sourceAddress, sourcePort),
            InetSocketAddress(destinationAddress, destinationPort),
        )
        check(uid != Process.INVALID_UID) { "android: connection owner not found" }
        val packages = service.packageManager.getPackagesForUid(uid).orEmpty().toList()
        return ConnectionOwner().apply {
            userId = uid
            userName = packages.firstOrNull().orEmpty()
            setAndroidPackageNames(packages.toStringIterator())
        }
    }

    override fun startDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {
        closeDefaultInterfaceMonitor(listener)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = updateDefaultInterface(listener, network)
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) =
                updateDefaultInterface(listener, network)

            override fun onLost(network: Network) = updateDefaultInterface(listener, connectivityManager.activeNetwork)
        }
        defaultNetworkCallback = callback
        connectivityManager.registerDefaultNetworkCallback(callback)
        updateDefaultInterface(listener, connectivityManager.activeNetwork)
    }

    override fun closeDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {
        defaultNetworkCallback?.let { callback ->
            runCatching { connectivityManager.unregisterNetworkCallback(callback) }
        }
        defaultNetworkCallback = null
    }

    private fun updateDefaultInterface(listener: InterfaceUpdateListener, network: Network?) {
        val interfaceName = network
            ?.let(connectivityManager::getLinkProperties)
            ?.interfaceName
            .orEmpty()
        val index = runCatching { NetworkInterface.getByName(interfaceName)?.index ?: -1 }.getOrDefault(-1)
        listener.updateDefaultInterface(interfaceName, index, false, false)
    }

    @Suppress("DEPRECATION")
    override fun getInterfaces(): NetworkInterfaceIterator {
        val androidNetworks = connectivityManager.allNetworks.mapNotNull { network ->
            val linkProperties = connectivityManager.getLinkProperties(network) ?: return@mapNotNull null
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return@mapNotNull null
            linkProperties.interfaceName.orEmpty() to (linkProperties to capabilities)
        }.toMap()
        val interfaces = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().map { networkInterface ->
            val androidNetwork = androidNetworks[networkInterface.name]
            LibboxNetworkInterface().apply {
                index = networkInterface.index
                name = networkInterface.name
                mtu = runCatching { networkInterface.mtu }.getOrDefault(0)
                addresses = networkInterface.interfaceAddresses
                    .map { address -> address.toPrefix() }
                    .toStringIterator()
                flags = networkInterface.toFlags()
                type = androidNetwork?.second?.toLibboxInterfaceType()
                    ?: io.nekohasekai.libbox.Libbox.InterfaceTypeOther
                dnsServer = androidNetwork?.first?.dnsServers
                    .orEmpty()
                    .mapNotNull { it.hostAddress }
                    .toStringIterator()
                metered = androidNetwork?.second
                    ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
                    ?.not()
                    ?: false
            }
        }
        return LibboxNetworkInterfaceIterator(interfaces.iterator())
    }

    override fun underNetworkExtension(): Boolean = false
    override fun includeAllNetworks(): Boolean = false
    override fun clearDNSCache() = Unit
    override fun localDNSTransport(): LocalDNSTransport = AndroidLocalDnsTransport(connectivityManager)
    override fun startNeighborMonitor(listener: NeighborUpdateListener?) = Unit
    override fun closeNeighborMonitor(listener: NeighborUpdateListener?) = Unit
    override fun usePlatformShell(): Boolean = false
    override fun checkPlatformShell(): Unit = unsupported("platform shell")
    override fun openShellSession(
        user: PlatformUser?,
        command: String?,
        environ: StringIterator?,
        term: String?,
        rows: Int,
        cols: Int,
    ): ShellSession = unsupported("platform shell")

    override fun readSystemSSHHostKey(): String = unsupported("system SSH host key")
    override fun lookupSFTPServer(): String = unsupported("SFTP server")
    override fun lookupUser(username: String?): PlatformUser = unsupported("platform user")
    override fun usePlatformBridge(): Boolean = false
    override fun createBridge(options: BridgeOptions?): BridgeSession = unsupported("platform bridge")
    override fun registerMyInterface(name: String?) = Unit

    @Suppress("DEPRECATION")
    override fun readWIFIState(): WIFIState? {
        val wifiManager = service.applicationContext.getSystemService(WifiManager::class.java)
        val wifiInfo = wifiManager?.connectionInfo ?: return null
        val ssid = wifiInfo.ssid
            .orEmpty()
            .removeSurrounding("\"")
            .takeUnless { it == "<unknown ssid>" }
            .orEmpty()
        return WIFIState(ssid, wifiInfo.bssid.orEmpty())
    }

    override fun tailscaleHostname(): String = "${Build.MANUFACTURER} ${Build.MODEL}"

    override fun sendNotification(notification: Notification) {
        AndroidAppLogger.info(
            LogTag,
            listOf(notification.title, notification.body).filter(String::isNotBlank).joinToString(": "),
        )
    }

    private fun installedPackageNames(): List<String> =
        service.packageManager.getInstalledApplicationsCompat()
            .map(ApplicationInfo::packageName)
            .toTrimmedNonEmptyDistinctList()

    private fun List<String>.forEachInstalledApplication(block: (String) -> Unit) {
        toTrimmedNonEmptyDistinctList().forEach { packageName ->
            try {
                block(packageName)
            } catch (_: PackageManager.NameNotFoundException) {
                AndroidAppLogger.warn(LogTag, "VPN application is not installed: $packageName")
            }
        }
    }

    private fun NetworkCapabilities.toLibboxInterfaceType(): Int = when {
        hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ->
            io.nekohasekai.libbox.Libbox.InterfaceTypeWIFI
        hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ->
            io.nekohasekai.libbox.Libbox.InterfaceTypeCellular
        hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ->
            io.nekohasekai.libbox.Libbox.InterfaceTypeEthernet
        else -> io.nekohasekai.libbox.Libbox.InterfaceTypeOther
    }

    private fun NetworkInterface.toFlags(): Int {
        var value = 0
        if (isUp) value = value or OsConstants.IFF_UP or OsConstants.IFF_RUNNING
        if (isLoopback) value = value or OsConstants.IFF_LOOPBACK
        if (isPointToPoint) value = value or OsConstants.IFF_POINTOPOINT
        if (supportsMulticast()) value = value or OsConstants.IFF_MULTICAST
        return value
    }

    private fun InterfaceAddress.toPrefix(): String {
        val host = if (address is Inet6Address) {
            Inet6Address.getByAddress(address.address).hostAddress
        } else {
            address.hostAddress
        }
        return "$host/$networkPrefixLength"
    }

    private fun <T> unsupported(feature: String): T =
        throw UnsupportedOperationException("android: $feature is not supported")

    private companion object {
        const val LogTag = "AndroidLibboxPlatform"
    }
}

internal class LibboxStringIterator(
    private val values: Iterator<String>,
    private val size: Int,
) : StringIterator {
    override fun len(): Int = size
    override fun hasNext(): Boolean = values.hasNext()
    override fun next(): String = values.next()
}

internal fun List<String>.toStringIterator(): StringIterator =
    LibboxStringIterator(iterator(), size)

private class LibboxNetworkInterfaceIterator(
    private val values: Iterator<LibboxNetworkInterface>,
) : NetworkInterfaceIterator {
    override fun hasNext(): Boolean = values.hasNext()
    override fun next(): LibboxNetworkInterface = values.next()
}

@Suppress("DEPRECATION")
private class AndroidLocalDnsTransport(
    private val connectivityManager: ConnectivityManager,
) : LocalDNSTransport {
    override fun raw(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    override fun exchange(context: ExchangeContext, message: ByteArray) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            error("android: raw DNS exchange requires Android 10")
        }
        exchangeRaw(context, message)
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
    private fun exchangeRaw(context: ExchangeContext, message: ByteArray) = runBlocking {
        val network = connectivityManager.activeNetwork
            ?: error("android: default network is unavailable")
        suspendCoroutine { continuation ->
            val cancellation = CancellationSignal()
            context.onCancel(cancellation::cancel)
            DnsResolver.getInstance().rawQuery(
                network,
                message,
                DnsResolver.FLAG_NO_RETRY,
                Dispatchers.IO.asExecutor(),
                cancellation,
                object : DnsResolver.Callback<ByteArray> {
                    override fun onAnswer(answer: ByteArray, rcode: Int) {
                        if (rcode == 0) {
                            context.rawSuccess(answer)
                        } else {
                            context.errorCode(rcode)
                        }
                        continuation.resume(Unit)
                    }

                    override fun onError(error: DnsResolver.DnsException) {
                        val cause = error.cause
                        if (cause is ErrnoException) {
                            context.errnoCode(cause.errno)
                            continuation.resume(Unit)
                        } else {
                            continuation.resumeWithException(error)
                        }
                    }
                },
            )
        }
    }

    override fun lookup(context: ExchangeContext, network: String, domain: String) = runBlocking {
        val defaultNetwork = connectivityManager.activeNetwork
            ?: error("android: default network is unavailable")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            lookupWithDnsResolver(context, defaultNetwork, network, domain)
        } else {
            val answer = try {
                defaultNetwork.getAllByName(domain)
            } catch (_: UnknownHostException) {
                context.errorCode(DnsRcodeNameError)
                return@runBlocking
            }
            context.success(answer.mapNotNull { address -> address.hostAddress }.joinToString("\n"))
        }
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
    private suspend fun lookupWithDnsResolver(
        context: ExchangeContext,
        defaultNetwork: Network,
        network: String,
        domain: String,
    ) {
        suspendCoroutine { continuation ->
            val cancellation = CancellationSignal()
            context.onCancel(cancellation::cancel)
            val callback = object : DnsResolver.Callback<Collection<java.net.InetAddress>> {
                override fun onAnswer(answer: Collection<java.net.InetAddress>, rcode: Int) {
                    if (rcode == 0) {
                        context.success(answer.mapNotNull { address -> address.hostAddress }.joinToString("\n"))
                    } else {
                        context.errorCode(rcode)
                    }
                    continuation.resume(Unit)
                }

                override fun onError(error: DnsResolver.DnsException) {
                    val cause = error.cause
                    if (cause is ErrnoException) {
                        context.errnoCode(cause.errno)
                        continuation.resume(Unit)
                    } else {
                        continuation.resumeWithException(error)
                    }
                }
            }
            val queryType = when {
                network.endsWith("4") -> DnsResolver.TYPE_A
                network.endsWith("6") -> DnsResolver.TYPE_AAAA
                else -> null
            }
            if (queryType == null) {
                DnsResolver.getInstance().query(
                    defaultNetwork,
                    domain,
                    DnsResolver.FLAG_NO_RETRY,
                    Dispatchers.IO.asExecutor(),
                    cancellation,
                    callback,
                )
            } else {
                DnsResolver.getInstance().query(
                    defaultNetwork,
                    domain,
                    queryType,
                    DnsResolver.FLAG_NO_RETRY,
                    Dispatchers.IO.asExecutor(),
                    cancellation,
                    callback,
                )
            }
        }
    }

    private companion object {
        const val DnsRcodeNameError = 3
    }
}

private fun io.nekohasekai.libbox.RoutePrefixIterator.toList(): List<io.nekohasekai.libbox.RoutePrefix> =
    buildList {
        while (this@toList.hasNext()) {
            add(this@toList.next())
        }
    }

private fun StringIterator.toList(): List<String> =
    buildList {
        while (this@toList.hasNext()) {
            add(this@toList.next())
        }
    }
