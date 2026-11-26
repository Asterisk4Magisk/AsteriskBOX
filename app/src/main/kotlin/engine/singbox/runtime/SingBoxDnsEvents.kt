// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package engine.singbox.runtime

import engine.network.DnsEventUpdate
import engine.network.DnsEventsUnavailable
import engine.network.decodeDnsEvent
import io.nekohasekai.libbox.CommandClient
import io.nekohasekai.libbox.DnsEventsHandler
import java.io.IOException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.launch

private sealed interface DnsCallbackUpdate {
    data object Ready : DnsCallbackUpdate
    data class Received(val json: String) : DnsCallbackUpdate
}

internal fun CommandClient.streamDnsEvents(token: String): Flow<DnsEventUpdate> =
    dnsEventCallbacks(token).transform { update ->
        when (update) {
            DnsCallbackUpdate.Ready -> emit(DnsEventUpdate.Ready)
            is DnsCallbackUpdate.Received -> {
                for (line in update.json.lineSequence()) {
                    if (line.isEmpty()) continue
                    decodeDnsEvent(line)?.let { emit(DnsEventUpdate.Received(it)) }
                }
            }
        }
    }

private fun CommandClient.dnsEventCallbacks(token: String): Flow<DnsCallbackUpdate> = callbackFlow {
    val handshake = launch {
        delay(DnsHandshakeTimeoutMillis)
        close(IOException("DNS events handshake timed out"))
    }
    val handler = object : DnsEventsHandler {
        override fun onReady() {
            handshake.cancel()
            trySend(DnsCallbackUpdate.Ready)
        }

        override fun onEvent(payload: String) {
            trySendBlocking(DnsCallbackUpdate.Received(payload))
        }

        override fun onError(code: String) {
            close(
                if (code == "UNSUPPORTED") DnsEventsUnavailable()
                else IOException("DNS events command ended ($code)"),
            )
        }
    }
    val subscription = subscribeDnsEventBatches(token, handler)
        ?: throw IOException("Missing DNS events session")
    awaitClose {
        handshake.cancel()
        runCatching { subscription.close() }
    }
}.buffer(DnsBatchBufferCapacity)

private const val DnsBatchBufferCapacity = 8
private const val DnsHandshakeTimeoutMillis = 3_000L
