// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package engine.vpn

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.HandlerThread
import androidx.core.content.ContextCompat
import features.logs.AndroidAppLogger
import system.getInstalledApplicationsCompat

/** The VPN process owns this platform snapshot; connection lookups only read it. */
internal class AndroidVpnPackageCache(private val context: Context) {
    @Volatile private var packages: Map<Int, List<String>> = emptyMap()
    private var worker: HandlerThread? = null
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            runCatching { refresh() }.onFailure {
                AndroidAppLogger.warn("VpnPackageCache", "Failed to refresh package mapping", it)
            }
        }
    }

    fun start() {
        if (worker != null) return
        val thread = HandlerThread("VpnPackageCache").apply { start() }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        try {
            ContextCompat.registerReceiver(context, receiver, filter, null, Handler(thread.looper),
                ContextCompat.RECEIVER_NOT_EXPORTED)
            worker = thread
            refresh()
        } catch (error: Throwable) {
            if (worker != null) context.unregisterReceiver(receiver)
            worker = null
            thread.quitSafely()
            throw error
        }
    }

    fun packagesForUid(uid: Int): List<String> = packages[uid].orEmpty()

    fun close() {
        val thread = worker ?: return
        worker = null
        context.unregisterReceiver(receiver)
        thread.quitSafely()
    }

    private fun refresh() {
        packages = context.packageManager.getInstalledApplicationsCompat()
            .groupBy({ it.uid }, { it.packageName })
    }
}
