// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package engine.singbox.runtime

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.milliseconds

private const val DelayPublishIntervalMillis = 100L

/** Shared requests keep their slots until blocking Command API calls actually return. */
internal class SingBoxSharedDelayTasks<K, R> {
    private class Entry<R>(val resourceKey: Any?) {
        val result = CompletableDeferred<R>()
        val owners = mutableMapOf<String?, Int>()
        lateinit var worker: Job
        var started = false
    }

    private val scope = CoroutineScope(Dispatchers.IO)
    private val tasks = linkedMapOf<K, Entry<R>>()
    private val running = mutableSetOf<Entry<R>>()
    private var limit = 1

    suspend fun run(
        key: K,
        concurrency: Int,
        owner: String? = null,
        resourceKey: Any? = key,
        block: suspend () -> R,
    ): R {
        require(concurrency > 0)
        currentCoroutineContext().ensureActive()
        val entry = synchronized(this) {
            limit = concurrency
            val entry = tasks[key]?.takeUnless { it.result.isCompleted } ?: Entry<R>(resourceKey).also { created ->
                tasks[key] = created
                created.worker = scope.launch(start = CoroutineStart.LAZY) {
                    try {
                        currentCoroutineContext().ensureActive()
                        val result = block()
                        currentCoroutineContext().ensureActive()
                        created.result.complete(result)
                    } catch (error: Throwable) {
                        created.result.completeExceptionally(error)
                        if (error is CancellationException) throw error
                    }
                }
                created.worker.invokeOnCompletion { error ->
                    error?.let(created.result::completeExceptionally)
                    synchronized(this) {
                        if (tasks[key] === created) tasks.remove(key)
                        running.remove(created)
                        dispatch()
                    }
                }
            }
            entry.owners[owner] = entry.owners.getOrDefault(owner, 0) + 1
            dispatch()
            entry
        }
        try {
            return entry.result.await()
        } finally {
            synchronized(this) {
                val remaining = entry.owners.getValue(owner) - 1
                if (remaining == 0) entry.owners.remove(owner) else entry.owners[owner] = remaining
                if (entry.owners.isEmpty() && !entry.result.isCompleted) {
                    entry.result.cancel()
                    entry.worker.cancel()
                }
            }
        }
    }

    @Synchronized
    private fun dispatch() {
        while (running.size < limit) {
            var next: Entry<R>? = null
            for (entry in tasks.values) {
                if (entry.started || entry.result.isCompleted || entry.owners.isEmpty()) continue
                if (running.any { it.resourceKey == entry.resourceKey }) continue
                if (next == null) next = entry
                if (null in entry.owners) {
                    next = entry
                    break
                }
            }
            val task = next ?: return
            task.started = true
            running += task
            task.worker.start()
        }
    }
}

internal suspend fun <T, R : Any> runSingBoxDelayBatch(
    targets: List<T>,
    batchResults: Boolean,
    onResults: (List<R>) -> Unit,
    test: suspend (T) -> R,
): List<R> = coroutineScope {
    fun startMeasurements(onResult: (R) -> Unit) = targets.map { target ->
        this@coroutineScope.async(start = CoroutineStart.UNDISPATCHED) {
            test(target).also { result ->
                currentCoroutineContext().ensureActive()
                onResult(result)
            }
        }
    }

    if (!batchResults) return@coroutineScope startMeasurements { onResults(listOf(it)) }.awaitAll()

    val pending = Channel<R>(Channel.UNLIMITED)
    val finished = CompletableDeferred<Unit>()
    val publisher = launch {
        for (first in pending) {
            // Wait for the window or normal completion without timing out a channel receive.
            withTimeoutOrNull(DelayPublishIntervalMillis.milliseconds) { finished.await() }
            val batch = mutableListOf(first)
            while (true) {
                batch += pending.tryReceive().getOrNull() ?: break
            }
            currentCoroutineContext().ensureActive()
            onResults(batch)
        }
    }
    val results = startMeasurements { pending.trySend(it) }.awaitAll()
    pending.close()
    finished.complete(Unit)
    publisher.join()
    results
}
