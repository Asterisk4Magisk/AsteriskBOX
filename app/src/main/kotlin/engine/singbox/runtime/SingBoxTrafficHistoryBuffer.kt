// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package engine.singbox.runtime

import java.util.ArrayDeque

/**
 * How many traffic samples the runtime keeps, and therefore how many columns the home network
 * chart's fixed time axis has. Keeping one constant for both is deliberate: the chart's x axis must
 * never be wider than the buffer, or its oldest slots could never be filled. Callers that only need
 * the retention window should read this instead of reaching into the repository's internals.
 */
internal const val TrafficHistoryCapacity = 64

internal class SingBoxTrafficHistoryBuffer(
    private val capacity: Int,
) {
    private val samples = ArrayDeque<SingBoxTrafficSample>(capacity)

    init {
        require(capacity > 0) { "capacity must be positive" }
    }

    fun append(sample: SingBoxTrafficSample) {
        if (samples.size == capacity) samples.removeFirst()
        samples.addLast(sample)
    }

    fun snapshot(limit: Int = capacity): List<SingBoxTrafficSample> {
        if (limit <= 0 || samples.isEmpty()) return emptyList()

        val firstIncludedIndex = (samples.size - limit).coerceAtLeast(0)
        return buildList(minOf(samples.size, limit)) {
            samples.forEachIndexed { index, sample ->
                if (index >= firstIncludedIndex) add(sample)
            }
        }
    }

    fun clear() {
        samples.clear()
    }
}
