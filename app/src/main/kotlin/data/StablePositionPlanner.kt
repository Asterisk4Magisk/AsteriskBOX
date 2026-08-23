// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package data

internal data class PositionedKey<K>(val key: K, val position: Int)

internal fun <K> stablePositions(
    current: List<PositionedKey<K>>,
    desiredKeys: List<K>,
): Map<K, Int> {
    val currentKeys = current.map(PositionedKey<K>::key)
    require(currentKeys.toSet().size == current.size) { "Current keys must be unique" }
    require(desiredKeys.toSet().size == desiredKeys.size) { "Desired keys must be unique" }

    val sortedCurrent = current.withIndex()
        .sortedWith(compareBy<IndexedValue<PositionedKey<K>>> { it.value.position }.thenBy { it.index })
        .map(IndexedValue<PositionedKey<K>>::value)
    val currentKeySet = currentKeys.toSet()
    val desiredKeySet = desiredKeys.toSet()
    val retained = sortedCurrent.filter { it.key in desiredKeySet }
    val retainedKeys = retained.map(PositionedKey<K>::key)
    val retainedPositions = retained.associate { it.key to it.position }

    if (desiredKeys == retainedKeys) {
        return retainedPositions
    }

    val additionsAreTail = desiredKeys.take(retained.size) == retainedKeys &&
        desiredKeys.drop(retained.size).all { it !in currentKeySet }
    if (additionsAreTail) {
        var nextPosition = (retained.maxOfOrNull(PositionedKey<K>::position) ?: -1) + 1
        return buildMap {
            putAll(retainedPositions)
            desiredKeys.drop(retained.size).forEach { key ->
                put(key, nextPosition++)
            }
        }
    }

    if (current.size == desiredKeys.size && currentKeySet == desiredKeySet) {
        val currentKeysInOrder = sortedCurrent.map(PositionedKey<K>::key)
        val firstMismatch = currentKeysInOrder.indices.firstOrNull { index ->
            currentKeysInOrder[index] != desiredKeys[index]
        }
        if (firstMismatch == null) {
            return sortedCurrent.associate { it.key to it.position }
        }
        val lastMismatch = currentKeysInOrder.indices.last { index ->
            currentKeysInOrder[index] != desiredKeys[index]
        }
        return buildMap {
            currentKeysInOrder.indices
                .filter { it !in firstMismatch..lastMismatch }
                .forEach { index ->
                    put(currentKeysInOrder[index], sortedCurrent[index].position)
                }
            (firstMismatch..lastMismatch).forEach { index ->
                put(desiredKeys[index], sortedCurrent[index].position)
            }
        }
    }

    return desiredKeys.mapIndexed { index, key -> key to index }.toMap()
}
