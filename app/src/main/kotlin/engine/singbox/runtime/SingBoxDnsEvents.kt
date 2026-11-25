// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package engine.singbox.runtime

import engine.network.DnsEventUpdate
import engine.network.DnsEventsUnavailable
import engine.network.decodeDnsEvent
import io.nekohasekai.libbox.CommandClient
import io.nekohasekai.libbox.DnsEventsHandler
import java.io.IOException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.transform

private sealed interface DnsCallbackUpdate {
    data object Ready : DnsCallbackUpdate
    data class Received(val json: String) : DnsCallbackUpdate
}

internal fun CommandClient.streamDnsEvents(token: String): Flow<DnsEventUpdate> =
    dnsEventCallbacks(token).transform { update ->
        when (update) {
            DnsCallbackUpdate.Ready -> emit(DnsEventUpdate.Ready)
            is DnsCallbackUpdate.Received -> decodeDnsEvent(update.json)?.let {
                emit(DnsEventUpdate.Received(it))
            }
        }
    }

private fun CommandClient.dnsEventCallbacks(token: String): Flow<DnsCallbackUpdate> = callbackFlow {
    if (getDnsEventsVersion(token) != 1) throw DnsEventsUnavailable()
    val handler = object : DnsEventsHandler {
        override fun onReady() {
            trySend(DnsCallbackUpdate.Ready)
        }

        override fun onEvent(eventJSON: String) {
            trySend(DnsCallbackUpdate.Received(eventJSON))
        }

        override fun onError(code: String) {
            close(
                if (code == "UNSUPPORTED") DnsEventsUnavailable()
                else IOException("DNS events command ended ($code)"),
            )
        }
    }
    val subscription = subscribeDnsEvents(token, handler)
        ?: throw IOException("Missing DNS events session")
    awaitClose { runCatching { subscription.close() } }
}.buffer(capacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
