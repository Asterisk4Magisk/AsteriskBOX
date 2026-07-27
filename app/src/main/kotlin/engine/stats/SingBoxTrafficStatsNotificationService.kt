// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package engine.stats

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import app.AsteriskApplication
import org.asterisk.zcc.abox.R
import engine.singbox.SingBoxControlConfig
import engine.singbox.runtime.SingBoxCommandClient
import engine.singbox.runtime.SingBoxCommandListener
import engine.singbox.runtime.SingBoxCommandTarget
import engine.singbox.runtime.SingBoxConnectionsState
import engine.singbox.runtime.SingBoxProxiesState
import engine.singbox.runtime.SingBoxRuntimeRepository
import engine.singbox.runtime.SingBoxTrafficSample
import features.logs.AndroidAppLogger
import io.nekohasekai.libbox.StatusMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.channels.awaitClose
import utils.toReadableBytes
import kotlin.time.Duration.Companion.milliseconds

private const val ActionStart = "engine.stats.action.START"
private const val ActionStop = "engine.stats.action.STOP"
private const val ExtraHost = "host"
private const val ExtraPort = "port"
private const val ExtraSecret = "secret"
private const val ExtraLocal = "local"
private const val ChannelId = "singBox_traffic_stats"
private const val NotificationId = 3001
private const val LogTag = "SingBoxTrafficStats"
private const val StreamRestartDelayMillis = 1_000L
private const val MaxConsecutiveFailures = 5

class SingBoxTrafficStatsNotificationService : Service() {
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(serviceJob + Dispatchers.IO)
    private var monitorJob: Job? = null
    private var activeRuntime: SingBoxTrafficStatsRuntime? = null
    private val notificationManager by lazy { getSystemService(NotificationManager::class.java) }
    private val contentIntent by lazy {
        packageManager
            .getLaunchIntentForPackage(packageName)
            ?.let { intent ->
                PendingIntent.getActivity(
                    this,
                    0,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ActionStop) {
            stopStats()
            stopSelf(startId)
            return START_NOT_STICKY
        }

        // Runtime values are supplied by the owning proxy engine because resolved API
        // options can include dynamic ports that are not safe to reconstruct after a
        // sticky service restart.
        val runtime = intent?.readRuntime()
        if (runtime == null) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (activeRuntime == runtime && monitorJob?.isActive == true) {
            return START_NOT_STICKY
        }

        activeRuntime = runtime
        startForegroundCompat(buildNotification(TrafficStatsSnapshot()))
        startMonitor(runtime)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopStats()
        serviceJob.cancel()
        super.onDestroy()
    }

    private fun startMonitor(runtime: SingBoxTrafficStatsRuntime) {
        monitorJob?.cancel()
        monitorJob = serviceScope.launch {
            val repository = (application as AsteriskApplication).singBoxRuntime
            val accumulator = TrafficStatsAccumulator()
            var consecutiveFailures = 0

            while (currentCoroutineContext().isActive) {
                val result = runCatching {
                    trafficSamples(runtime, repository).collect { sample ->
                        consecutiveFailures = 0
                        updateNotification(accumulator.accept(sample))
                    }
                }

                result.onFailure { error ->
                    if (error is CancellationException) throw error
                    AndroidAppLogger.debug(LogTag, "Traffic stats notification stream failed: ${error.message}")
                }
                consecutiveFailures += 1
                if (consecutiveFailures >= MaxConsecutiveFailures) {
                    AndroidAppLogger.warn(LogTag, "Stopping traffic stats notification after repeated stream failures")
                    stopSelf()
                    return@launch
                }
                delay(StreamRestartDelayMillis.milliseconds)
            }
        }
    }

    private fun trafficSamples(
        runtime: SingBoxTrafficStatsRuntime,
        repository: SingBoxRuntimeRepository,
    ): Flow<SingBoxTrafficSample> {
        return repository.state
            .map { state -> state.traffic.connected }
            .distinctUntilChanged()
            .flatMapLatest { repositoryConnected ->
                AndroidAppLogger.debug(
                    LogTag,
                    if (repositoryConnected) {
                        "Using foreground runtime traffic stream"
                    } else {
                        "Using notification-owned traffic stream"
                    },
                )
                if (repositoryConnected) {
                    repository.state
                        .map { state -> state.traffic }
                        .distinctUntilChanged()
                        .filter { traffic -> traffic.connected }
                        .map { traffic ->
                            traffic.latest.copy(
                                totalUp = traffic.totalUp,
                                totalDown = traffic.totalDown,
                            )
                        }
                } else {
                    commandTraffic(runtime)
                }
            }
    }

    private fun commandTraffic(runtime: SingBoxTrafficStatsRuntime): Flow<SingBoxTrafficSample> = callbackFlow {
        val client = SingBoxCommandClient(
            target = SingBoxCommandTarget(local = runtime.local, control = runtime.control),
            listener = object : SingBoxCommandListener {
                override fun onConnected() = Unit

                override fun onDisconnected(message: String) {
                    close(IllegalStateException(message.ifBlank { "sing-box API disconnected" }))
                }

                override fun onStatus(status: StatusMessage) {
                    trySend(
                        SingBoxTrafficSample(
                            up = status.uplink,
                            down = status.downlink,
                            totalUp = status.uplinkTotal,
                            totalDown = status.downlinkTotal,
                        ),
                    )
                }

                override fun onProxies(proxies: SingBoxProxiesState) = Unit

                override fun onConnections(connections: SingBoxConnectionsState) = Unit
            },
        )
        client.connect()
        awaitClose { client.disconnect() }
    }

    private fun updateNotification(snapshot: TrafficStatsSnapshot) {
        notificationManager.notify(NotificationId, buildNotification(snapshot))
    }

    private fun buildNotification(
        snapshot: TrafficStatsSnapshot,
    ): Notification {
        val speedLine = getString(
            R.string.proxy_traffic_stats_notification_speed,
            "${snapshot.upSpeed.toReadableBytes(keepTrailingZero = true)}/s",
            "${snapshot.downSpeed.toReadableBytes(keepTrailingZero = true)}/s",
        )
        val trafficLine = getString(
            R.string.proxy_traffic_stats_notification_traffic,
            snapshot.totalUp.toReadableBytes(keepTrailingZero = true),
            snapshot.totalDown.toReadableBytes(keepTrailingZero = true),
        )
        val content = trafficStatsNotificationContent(
            appName = getString(R.string.app_name),
            speedLine = speedLine,
            trafficLine = trafficLine,
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, ChannelId)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(content.title)
            .setContentText(content.summary)
            .setStyle(Notification.BigTextStyle().bigText(content.expandedText))
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setLocalOnly(true)
            .setCategory(Notification.CATEGORY_STATUS)
            .build()
    }

    private fun stopStats() {
        monitorJob?.cancel()
        monitorJob = null
        activeRuntime = null
        stopForegroundCompat()
        notificationManager.cancel(NotificationId)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        notificationManager.createNotificationChannel(
            NotificationChannel(
                ChannelId,
                getString(R.string.proxy_traffic_stats_notification_channel),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NotificationId,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NotificationId, notification)
        }
    }

    private fun stopForegroundCompat() {
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    companion object {
        internal fun reconcile(context: Context, runtime: SingBoxTrafficStatsRuntime?) {
            if (runtime == null) {
                stop(context)
            } else {
                start(context, runtime)
            }
        }

        internal fun start(context: Context, runtime: SingBoxTrafficStatsRuntime) {
            val appContext = context.applicationContext
            val intent = Intent(appContext, SingBoxTrafficStatsNotificationService::class.java).apply {
                action = ActionStart
                putRuntime(runtime)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                appContext.startForegroundService(intent)
            } else {
                appContext.startService(intent)
            }
        }

        internal fun stop(context: Context) {
            val appContext = context.applicationContext
            appContext.stopService(Intent(appContext, SingBoxTrafficStatsNotificationService::class.java).apply {
                action = ActionStop
            })
            appContext.getSystemService(NotificationManager::class.java).cancel(NotificationId)
        }
    }
}

private data class TrafficStatsSnapshot(
    val upSpeed: Long = 0L,
    val downSpeed: Long = 0L,
    val totalUp: Long = 0L,
    val totalDown: Long = 0L,
)

private class TrafficStatsAccumulator {
    private var totalUp = 0L
    private var totalDown = 0L

    fun accept(sample: SingBoxTrafficSample): TrafficStatsSnapshot {
        totalUp = sample.totalUp ?: (totalUp + sample.up)
        totalDown = sample.totalDown ?: (totalDown + sample.down)
        return TrafficStatsSnapshot(
            upSpeed = sample.up,
            downSpeed = sample.down,
            totalUp = totalUp,
            totalDown = totalDown,
        )
    }
}

private fun Intent.putRuntime(runtime: SingBoxTrafficStatsRuntime) {
    putExtra(ExtraHost, runtime.control.host)
    putExtra(ExtraPort, runtime.control.port)
    putExtra(ExtraSecret, runtime.control.secret)
    putExtra(ExtraScheme, runtime.control.scheme)
    putExtra(ExtraLocal, runtime.local)
}

private fun Intent.readRuntime(): SingBoxTrafficStatsRuntime? {
    val host = getStringExtra(ExtraHost)?.takeIf(String::isNotBlank) ?: return null
    val port = getIntExtra(ExtraPort, 0).takeIf { value -> value in 1..65535 } ?: return null
    return SingBoxTrafficStatsRuntime(
        control = SingBoxControlConfig(
            host = host,
            port = port,
            secret = getStringExtra(ExtraSecret).orEmpty(),
            scheme = getStringExtra(ExtraScheme).orEmpty().takeIf { it in setOf("http", "https") } ?: "http",
        ),
        local = getBooleanExtra(ExtraLocal, true),
    )
}

private const val ExtraScheme = "singBox_control_scheme"
