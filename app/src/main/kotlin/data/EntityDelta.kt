// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package data

internal data class EntityDelta<K, E>(
    val removedKeys: Set<K>,
    val added: List<E>,
    val updated: List<E>,
)

internal fun <K, E> entityDelta(
    previous: List<E>,
    next: List<E>,
    keyOf: (E) -> K,
): EntityDelta<K, E> {
    val previousByKey = previous.associateBy(keyOf)
    val nextByKey = next.associateBy(keyOf)
    require(previousByKey.size == previous.size) { "Previous entity keys must be unique" }
    require(nextByKey.size == next.size) { "Next entity keys must be unique" }

    return EntityDelta(
        removedKeys = previousByKey.keys - nextByKey.keys,
        added = next.filter { entity -> keyOf(entity) !in previousByKey },
        updated = next.filter { entity ->
            val previousEntity = previousByKey[keyOf(entity)]
            previousEntity != null && previousEntity != entity
        },
    )
}
