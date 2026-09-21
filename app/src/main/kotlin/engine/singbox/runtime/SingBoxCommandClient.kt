// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package engine.singbox.runtime

import engine.singbox.SingBoxControlConfig
import features.logs.AndroidCoreLogRepository
import features.logs.currentLogTime
import io.nekohasekai.libbox.CommandClient
import io.nekohasekai.libbox.CommandClientHandler
import io.nekohasekai.libbox.CommandClientOptions
import io.nekohasekai.libbox.ConnectionEvents
import io.nekohasekai.libbox.Connections
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.LogIterator
import io.nekohasekai.libbox.NetworkQualityTestHandler
import io.nekohasekai.libbox.NetworkQualityTestSession
import io.nekohasekai.libbox.OutboundGroupItemIterator
import io.nekohasekai.libbox.OutboundGroupIterator
import io.nekohasekai.libbox.RemoteConnectionOptions
import io.nekohasekai.libbox.StatusMessage
import io.nekohasekai.libbox.StringIterator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.time.Duration.Companion.milliseconds

internal data class SingBoxCommandTarget(
    val local: Boolean,
    val control: SingBoxControlConfig,
)

internal interface SingBoxCommandListener {
    fun onConnected()
    fun onDisconnected(message: String)
    fun onStatus(status: StatusMessage)
    fun onProxies(proxies: SingBoxProxiesState)
    fun onConnections(connections: SingBoxConnectionsState)
}

internal class SingBoxCommandClient(
    private val target: SingBoxCommandTarget,
    private val listener: SingBoxCommandListener,
) : CommandClientHandler {
    private val access = Any()
    private val logWriter = SingBoxCommandLogWriter()
    private var client: CommandClient? = null
    private var connections: Connections = Libbox.newConnections()
    private val modeMutex = Mutex()
    private val modeStatus = MutableStateFlow(ClashModeStatus(disconnected = true))

    @JvmOverloads
    fun connect(modeOnly: Boolean = false) {
        disconnect()
        val options = CommandClientOptions().apply {
            if (!modeOnly) {
                addCommand(Libbox.CommandStatus)
                addCommand(Libbox.CommandGroup)
                addCommand(Libbox.CommandConnections)
                addCommand(Libbox.CommandLog)
            }
            addCommand(Libbox.CommandClashMode)
            statusInterval = StatusIntervalNanos
        }
        val nextClient = if (target.local) {
            Libbox.newCommandClient(this, options)
        } else {
            Libbox.newRemoteCommandClient(
                this,
                options,
                RemoteConnectionOptions().apply {
                    url = target.control.baseUrl
                    secret = target.control.secret
                },
            )
        }
        synchronized(access) {
            client = nextClient
            connections = Libbox.newConnections()
            modeStatus.value = ClashModeStatus()
        }
        runCatching { nextClient.connect() }.onFailure { error ->
            synchronized(access) {
                if (client === nextClient) {
                    client = null
                    modeStatus.value = ClashModeStatus(disconnected = true)
                }
            }
            runCatching { nextClient.disconnect() }
            throw error
        }
    }

    fun disconnect() {
        val previous = synchronized(access) {
            client.also {
                client = null
                connections = Libbox.newConnections()
                modeStatus.value = ClashModeStatus(disconnected = true)
            }
        }
        previous?.let { runCatching { it.disconnect() } }
    }

    fun selectOutbound(groupTag: String, outboundTag: String) {
        requireClient().selectOutbound(groupTag, outboundTag)
    }

    fun urlTest(groupTag: String) {
        requireClient().urlTest(groupTag)
    }

    /**
     * Start an Apple networkQuality test through the running sing-box.
     *
     * Forwards to [CommandClient.startNetworkQualityTest], which the aar binds to
     * the `daemon.StartedService/StartNetworkQualityTest` gRPC method on both VPN
     * (process-internal channel) and ROOT (127.0.0.1:9090 gRPC) targets. Caller
     * must `close()` the returned [NetworkQualityTestSession] to cancel; the
     * handler keeps receiving callbacks until either `onResult` or `onError`.
     */
    fun startNetworkQualityTest(
        configURL: String,
        outboundTag: String,
        serial: Boolean,
        maxRuntimeSeconds: Int,
        http3: Boolean,
        handler: NetworkQualityTestHandler,
    ): NetworkQualityTestSession = requireClient().startNetworkQualityTest(
        configURL,
        outboundTag,
        serial,
        maxRuntimeSeconds,
        http3,
        handler,
    )

    fun closeConnection(connectionId: String) {
        requireClient().closeConnection(connectionId)
    }

    fun closeConnections() {
        requireClient().closeConnections()
    }

    suspend fun setMode(mode: String) = modeMutex.withLock {
        val active = requireClient()
        val requested = mode.toOfficialClashMode()
        val confirmed = withTimeoutOrNull(ModeConfirmationTimeoutMillis.milliseconds) {
            val initial = modeStatus.first { it.supported != null || it.disconnected }
            check(!initial.disconnected && requireClient() === active) { "sing-box API disconnected during mode change" }
            require(initial.supported.orEmpty().any { it.equals(requested, ignoreCase = true) }) {
                "sing-box does not support Clash mode $requested"
            }
            suspendCancellableCoroutine { continuation ->
                // gomobile calls are blocking; cancellation must cancel the native gRPC context.
                continuation.invokeOnCancellation { runCatching { active.disconnect() } }
                Dispatchers.IO.asExecutor().execute {
                    runCatching { active.setClashMode(requested) }.fold(
                        onSuccess = { continuation.resume(Unit) },
                        onFailure = continuation::resumeWithException,
                    )
                }
            }
            // The RPC silently accepts unknown modes. Only the subscription confirms application.
            val applied = modeStatus.first { it.current.equals(requested, ignoreCase = true) || it.disconnected }
            check(!applied.disconnected && requireClient() === active) { "sing-box API disconnected during mode change" }
            true
        }
        check(confirmed == true) { "Timed out confirming sing-box Clash mode $requested" }
    }

    fun reloadService() {
        requireClient().serviceReload()
    }

    fun serviceStartedAtMillis(): Long = requireClient().startedAt

    private fun requireClient(): CommandClient =
        synchronized(access) { client } ?: error("sing-box API is not connected")

    override fun connected() {
        listener.onConnected()
    }

    override fun disconnected(message: String?) {
        modeStatus.value = ClashModeStatus(disconnected = true)
        listener.onDisconnected(message.orEmpty())
    }

    override fun writeStatus(message: StatusMessage) {
        listener.onStatus(message)
    }

    override fun initializeClashMode(modeList: StringIterator, currentMode: String) {
        val supported = modeList.consume()
        modeStatus.update { status ->
            if (status.disconnected) status else status.copy(
                supported = supported,
                // Android's stack workaround dispatches initialization on a separate goroutine.
                current = status.current.ifEmpty { currentMode },
            )
        }
    }

    override fun updateClashMode(newMode: String) {
        modeStatus.update { if (it.disconnected) it else it.copy(current = newMode) }
    }

    override fun writeGroups(message: OutboundGroupIterator?) {
        if (message == null) return
        val groups = mutableListOf<SingBoxProxyGroup>()
        val nodes = linkedMapOf<String, SingBoxProxyNode>()
        while (message.hasNext()) {
            val group = message.next()
            val itemNames = mutableListOf<String>()
            val items = group.items
            while (items.hasNext()) {
                val item = items.next()
                itemNames += item.tag
                nodes[item.tag] = singBoxProxyNode(
                    name = item.tag,
                    type = item.type,
                    urlTestDelay = item.urlTestDelay,
                    urlTestTime = item.urlTestTime,
                )
            }
            groups += SingBoxProxyGroup(
                name = group.tag,
                type = group.type,
                now = group.selected,
                all = itemNames,
            )
        }
        listener.onProxies(
            SingBoxProxiesState(
                groups = groups,
                nodes = nodes.values.toList(),
                nodeByName = nodes,
                updatedAtMillis = System.currentTimeMillis(),
            ),
        )
    }

    override fun writeOutbounds(message: OutboundGroupItemIterator?) {
        while (message?.hasNext() == true) {
            message.next()
        }
    }

    override fun writeConnectionEvents(events: ConnectionEvents?) {
        if (events == null) return
        val snapshot = synchronized(access) {
            connections.applyEvents(events)
            connections.filterState(Libbox.ConnectionStateActive.toInt())
            val values = mutableListOf<SingBoxConnection>()
            var uploadTotal = 0L
            var downloadTotal = 0L
            val iterator = connections.iterator()
            while (iterator.hasNext()) {
                val connection = iterator.next()
                val process = connection.processInfo
                val packages = process?.packageNames()?.consume().orEmpty()
                uploadTotal += connection.uplinkTotal
                downloadTotal += connection.downlinkTotal
                values += SingBoxConnection(
                    id = connection.id,
                    network = connection.network.lowercase(),
                    inboundType = connection.inboundType,
                    sourceAddress = connection.source,
                    destinationAddress = connection.displayDestination(),
                    process = packages.firstOrNull() ?: process?.userName.orEmpty(),
                    processPath = process?.processPath.orEmpty(),
                    uid = process?.userID?.toLong()?.takeIf { it >= 0L },
                    outbound = connection.outbound,
                    outboundType = connection.outboundType,
                    chains = connection.chain().consume(),
                    rule = connection.rule,
                    uploadBytes = connection.uplinkTotal,
                    downloadBytes = connection.downlinkTotal,
                    uploadBytesPerSecond = connection.uplink,
                    downloadBytesPerSecond = connection.downlink,
                    startedAtMillis = connection.createdAt.takeIf { it > 0L },
                )
            }
            SingBoxConnectionsState(
                uploadTotalBytes = uploadTotal,
                downloadTotalBytes = downloadTotal,
                connections = values,
                updatedAtMillis = System.currentTimeMillis(),
            )
        }
        listener.onConnections(snapshot)
    }

    override fun setDefaultLogLevel(level: Int) {
        logWriter.setDefaultLogLevel(level)
    }

    override fun clearLogs() {
        logWriter.clear()
    }

    override fun writeLogs(messageList: LogIterator?) {
        while (messageList?.hasNext() == true) {
            val entry = messageList.next()
            logWriter.append(
                level = entry.level,
                message = entry.message,
                time = currentLogTime(),
            )
        }
    }

    private companion object {
        const val StatusIntervalNanos = 1_000_000_000L
        const val ModeConfirmationTimeoutMillis = 5_000L
    }
}

private data class ClashModeStatus(
    val supported: List<String>? = null,
    val current: String = "",
    val disconnected: Boolean = false,
)

internal fun singBoxProxyNode(
    name: String,
    type: String,
    udp: Boolean = false,
    urlTestDelay: Int,
    urlTestTime: Long,
): SingBoxProxyNode = SingBoxProxyNode(
    name = name,
    type = type,
    udp = udp,
    delay = urlTestDelay.takeIf { delay -> delay > 0 },
    delayUpdatedAtEpochSeconds = urlTestTime.takeIf { time -> time > 0L },
)

internal class SingBoxCommandLogWriter(
    private val appendPersisted: (level: String, message: String, time: String) -> Unit =
        AndroidCoreLogRepository::appendPersisted,
    private val clearPersisted: () -> Unit = AndroidCoreLogRepository::clear,
) {
    private var defaultLogLevel = SingBoxLogLevelInfo

    fun setDefaultLogLevel(level: Int) {
        defaultLogLevel = level.takeIf { it in SingBoxLogLevelPanic..SingBoxLogLevelTrace }
            ?: SingBoxLogLevelInfo
    }

    fun append(level: Int, message: String, time: String = currentLogTime()) {
        if (level !in SingBoxLogLevelPanic..defaultLogLevel) return
        appendPersisted(level.toLogLevelName(), message, time)
    }

    fun clear() {
        clearPersisted()
    }
}

private fun Int.toLogLevelName(): String = when (this) {
    0 -> "panic"
    1 -> "fatal"
    2 -> "error"
    3 -> "warn"
    4 -> "info"
    5 -> "debug"
    6 -> "trace"
    else -> "info"
}

private const val SingBoxLogLevelPanic = 0
private const val SingBoxLogLevelInfo = 4
private const val SingBoxLogLevelTrace = 6

private fun String.toOfficialClashMode(): String = when (lowercase()) {
    "global" -> "Global"
    "direct" -> "Direct"
    "rule" -> "Rule"
    else -> error("Unknown Clash mode: $this")
}

private fun StringIterator.consume(): List<String> = buildList {
    while (this@consume.hasNext()) {
        add(this@consume.next())
    }
}
