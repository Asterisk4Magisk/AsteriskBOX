// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package engine.vpn

import android.content.Context
import android.os.Bundle
import android.os.Parcel
import features.logs.AndroidAppLogger
import org.json.JSONObject
import utils.writeAtomically
import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile

/**
 * The cross-process "is the tunnel up?" signal.
 *
 * The VpnService runs in its own process, so the UI process cannot read its memory. The service
 * holds an exclusive file lock for exactly as long as the tunnel is up; a reader takes a
 * non-blocking shared lock and treats "someone else already holds it" as running.
 *
 * A lock is used instead of the recorded pid because the kernel drops it when the process dies,
 * including after a crash, and because `/proc/<pid>` is not a dependable read from another app
 * process.
 */
internal object VpnSessionLease {
    private const val FileName = "vpn-session.lock"
    private const val LogTag = "VpnSessionLease"

    /** Taken by the service process; closing it, or the process dying, ends the lease. */
    fun acquire(context: Context): Closeable? {
        return runCatching {
            val access = RandomAccessFile(file(context), "rw")
            val lock = access.channel.lock()
            object : Closeable {
                override fun close() {
                    runCatching { lock.release() }
                        .onFailure { error ->
                            AndroidAppLogger.warn(LogTag, "Failed to release the VPN session lease", error)
                        }
                    runCatching { access.close() }
                }
            }
        }.onFailure { error ->
            AndroidAppLogger.warn(LogTag, "Failed to acquire the VPN session lease", error)
        }.getOrNull()
    }

    /** Releases the lease and removes the file; called by the service on every stop. */
    fun release(lease: Closeable?, context: Context) {
        runCatching { lease?.close() }
        runCatching { file(context).delete() }
    }

    /**
     * `true` while the service process still holds the lease.
     *
     * Serialized because two overlapping locks on the same file inside one JVM throw
     * [java.nio.channels.OverlappingFileLockException]; status reads are not single-flight.
     */
    fun isHeld(context: Context): Boolean = synchronized(probeLock) {
        val target = file(context)
        if (!target.isFile) return false
        runCatching {
            // Read-only: probing must never create the file that marks a session.
            RandomAccessFile(target, "r").use { access ->
                val shared = access.channel.tryLock(0L, Long.MAX_VALUE, true) ?: return true
                // Nobody holds an exclusive lease: this process just took it provisionally.
                runCatching { shared.release() }
                false
            }
        }.getOrElse { error ->
            AndroidAppLogger.warn(LogTag, "Failed to probe the VPN session lease", error)
            false
        }
    }

    private val probeLock = Any()

    private fun file(context: Context): File = File(vpnRuntimeDirectory(context), FileName)
}

/**
 * Telemetry for the VPN tunnel: whether it runs, in which process and since when.
 *
 * Nothing decides behaviour from this record — [VpnSessionLease] is the authority — but it makes
 * a running (or previously crashed) session visible in support logs without shell access.
 */
internal object VpnSessionStore {
    private const val FileName = "vpn-session.json"
    private const val Version = 1
    private const val KeyVersion = "version"
    private const val KeyRunning = "running"
    private const val KeyPid = "pid"
    private const val KeyStartedAtMillis = "started_at_millis"
    private const val LogTag = "VpnSessionStore"

    fun markRunning(context: Context) {
        write(
            context,
            JSONObject()
                .put(KeyVersion, Version)
                .put(KeyRunning, true)
                .put(KeyPid, android.os.Process.myPid())
                .put(KeyStartedAtMillis, System.currentTimeMillis()),
        )
    }

    fun markStopped(context: Context) {
        write(context, JSONObject().put(KeyVersion, Version).put(KeyRunning, false))
    }

    fun isRunning(context: Context): Boolean {
        return VpnSessionLease.isHeld(context) && read(context)?.optBoolean(KeyRunning, false) == true
    }

    private fun read(context: Context): JSONObject? {
        val file = file(context)
        if (!file.isFile) return null
        return runCatching { JSONObject(file.readText()) }.getOrNull()
    }

    private fun write(context: Context, record: JSONObject) {
        runCatching {
            writeAtomically(file(context)) { output ->
                output.write(record.toString().toByteArray(Charsets.UTF_8))
            }
        }.onFailure { error ->
            AndroidAppLogger.warn(LogTag, "Failed to persist the VPN session state", error)
        }
    }

    private fun file(context: Context): File = File(vpnRuntimeDirectory(context), FileName)
}

/**
 * The configuration of the last successfully started tunnel.
 *
 * The boot receiver and the system's always-on VPN start the service with no configuration and
 * no access to the app state database (which stays owned by the UI process), so the service
 * replays the configuration that produced the last tunnel instead of recompiling it. The record
 * reuses the intent wire format, so the two paths cannot drift apart.
 */
internal object VpnStartConfigStore {
    private const val FileName = "vpn-start-config.parcel"
    private const val LogTag = "VpnStartConfigStore"

    fun save(context: Context, config: VpnServiceStartConfig) {
        val bundle = Bundle().apply { writeVpnServiceStartConfig(config) }
        val parcel = Parcel.obtain()
        try {
            parcel.writeBundle(bundle)
            writeAtomically(file(context)) { output ->
                output.write(parcel.marshall())
            }
        } catch (error: Exception) {
            AndroidAppLogger.warn(LogTag, "Failed to persist the VPN start configuration", error)
        } finally {
            parcel.recycle()
        }
    }

    fun load(context: Context): VpnServiceStartConfig? {
        val file = file(context)
        if (!file.isFile) return null
        val bytes = runCatching { file.readBytes() }.getOrNull() ?: return null
        if (bytes.isEmpty()) return null
        val parcel = Parcel.obtain()
        return try {
            parcel.unmarshall(bytes, 0, bytes.size)
            parcel.setDataPosition(0)
            val bundle = parcel.readBundle(VpnStartConfigStore::class.java.classLoader)
            bundle?.readVpnServiceStartConfig()
        } catch (error: RuntimeException) {
            AndroidAppLogger.warn(LogTag, "Failed to read the VPN start configuration", error)
            null
        } finally {
            parcel.recycle()
        }
    }

    fun clear(context: Context) {
        runCatching { file(context).delete() }
    }

    private fun file(context: Context): File = File(vpnRuntimeDirectory(context), FileName)
}

private fun vpnRuntimeDirectory(context: Context): File {
    return File(context.filesDir, "sing-box").apply { mkdirs() }
}
