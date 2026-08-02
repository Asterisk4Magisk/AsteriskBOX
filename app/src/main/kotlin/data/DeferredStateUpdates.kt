// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package data

internal class DeferredStateUpdates<T> {
    private var replacementInProgress = false
    private val updates = mutableListOf<(T) -> T>()

    fun beginReplacement() {
        check(!replacementInProgress) { "A state replacement is already in progress" }
        replacementInProgress = true
    }

    fun deferIfReplacing(transform: (T) -> T): Boolean {
        if (!replacementInProgress) return false
        updates += transform
        return true
    }

    fun mustRejectSynchronousMutation(): Boolean = replacementInProgress

    fun finishReplacement(
        base: T,
        onFailure: (Throwable) -> Unit = {},
    ): T {
        check(replacementInProgress) { "No state replacement is in progress" }
        val pendingUpdates = updates.toList()
        updates.clear()
        replacementInProgress = false
        var state = base
        pendingUpdates.forEach { transform ->
            try {
                state = transform(state)
            } catch (error: Throwable) {
                onFailure(error)
            }
        }
        return state
    }
}
