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
import io.nekohasekai.libbox.OutboundGroupItemIterator
import io.nekohasekai.libbox.OutboundGroupIterator
import io.nekohasekai.libbox.RemoteConnectionOptions
import io.nekohasekai.libbox.StatusMessage
import io.nekohasekai.libbox.StringIterator

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

    fun connect() {
        disconnect()
        val options = CommandClientOptions().apply {
            addCommand(Libbox.CommandStatus)
            addCommand(Libbox.CommandGroup)
            addCommand(Libbox.CommandConnections)
            addCommand(Libbox.CommandLog)
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
                    setURL(target.control.baseUrl)
                    secret = target.control.secret
                },
            )
        }
        synchronized(access) {
            client = nextClient
            connections = Libbox.newConnections()
        }
        runCatching { nextClient.connect() }.onFailure { error ->
            synchronized(access) {
                if (client === nextClient) client = null
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

    fun closeConnection(connectionId: String) {
        requireClient().closeConnection(connectionId)
    }

    fun closeConnections() {
        requireClient().closeConnections()
    }

    fun setMode(mode: String) {
        requireClient().setClashMode(mode.toOfficialClashMode())
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
        listener.onDisconnected(message.orEmpty())
    }

    override fun writeStatus(message: StatusMessage) {
        listener.onStatus(message)
    }

    override fun initializeClashMode(modeList: StringIterator, currentMode: String) {
        modeList.consume()
    }

    override fun updateClashMode(newMode: String) = Unit

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
    }
}

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
    else -> "Rule"
}

private fun StringIterator.consume(): List<String> = buildList {
    while (this@consume.hasNext()) {
        add(this@consume.next())
    }
}
