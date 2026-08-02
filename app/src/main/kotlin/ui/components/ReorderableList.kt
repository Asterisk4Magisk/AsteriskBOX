// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package ui.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import sh.calvin.reorderable.ReorderableCollectionItemScope
import sh.calvin.reorderable.ReorderableLazyGridState
import sh.calvin.reorderable.ReorderableLazyListState
import sh.calvin.reorderable.rememberReorderableLazyGridState
import sh.calvin.reorderable.rememberReorderableLazyListState

internal class AsteriskReorderableLazyGridState(
    val reorderableState: ReorderableLazyGridState,
    val hapticFeedback: HapticFeedback,
)

internal class AsteriskReorderableLazyListState(
    val reorderableState: ReorderableLazyListState,
    val hapticFeedback: HapticFeedback,
)

internal fun translateAsteriskReorderableLazyGridMove(
    fromIndex: Int,
    toIndex: Int,
    itemCount: Int,
    indexOffset: Int,
): Pair<Int, Int>? {
    val translatedFromIndex = fromIndex - indexOffset
    val translatedToIndex = toIndex - indexOffset
    if (
        translatedFromIndex == translatedToIndex ||
        translatedFromIndex !in 0 until itemCount ||
        translatedToIndex !in 0 until itemCount
    ) {
        return null
    }
    return translatedFromIndex to translatedToIndex
}

@Composable
internal fun rememberAsteriskReorderableLazyGridState(
    lazyGridState: LazyGridState,
    itemCount: Int,
    indexOffset: Int = 0,
    scrollThresholdPadding: PaddingValues = PaddingValues(),
    onMove: (fromIndex: Int, toIndex: Int) -> Unit,
): AsteriskReorderableLazyGridState {
    val hapticFeedback = LocalHapticFeedback.current
    val reorderableState = rememberReorderableLazyGridState(
        lazyGridState = lazyGridState,
        scrollThresholdPadding = scrollThresholdPadding,
    ) { from, to ->
        val (fromIndex, toIndex) = translateAsteriskReorderableLazyGridMove(
            fromIndex = from.index,
            toIndex = to.index,
            itemCount = itemCount,
            indexOffset = indexOffset,
        ) ?: return@rememberReorderableLazyGridState
        onMove(fromIndex, toIndex)
        hapticFeedback.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
    }
    return AsteriskReorderableLazyGridState(reorderableState, hapticFeedback)
}

@Composable
internal fun rememberAsteriskReorderableLazyListState(
    lazyListState: LazyListState,
    itemCount: Int,
    indexOffset: Int = 0,
    scrollThresholdPadding: PaddingValues = PaddingValues(),
    onMove: (fromIndex: Int, toIndex: Int) -> Unit,
): AsteriskReorderableLazyListState {
    val hapticFeedback = LocalHapticFeedback.current
    val reorderableState = rememberReorderableLazyListState(
        lazyListState = lazyListState,
        scrollThresholdPadding = scrollThresholdPadding,
    ) { from, to ->
        val (fromIndex, toIndex) = translateAsteriskReorderableLazyGridMove(
            fromIndex = from.index,
            toIndex = to.index,
            itemCount = itemCount,
            indexOffset = indexOffset,
        ) ?: return@rememberReorderableLazyListState
        onMove(fromIndex, toIndex)
        hapticFeedback.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
    }
    return AsteriskReorderableLazyListState(reorderableState, hapticFeedback)
}

internal fun Modifier.longPressReorderDragHandle(
    scope: ReorderableCollectionItemScope,
    enabled: Boolean,
    state: AsteriskReorderableLazyGridState,
): Modifier {
    return with(scope) {
        this@longPressReorderDragHandle.longPressDraggableHandle(
            enabled = enabled,
            onDragStarted = {
                state.hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
            },
            onDragStopped = {
                state.hapticFeedback.performHapticFeedback(HapticFeedbackType.GestureEnd)
            },
        )
    }
}

internal fun Modifier.longPressReorderDragHandle(
    scope: ReorderableCollectionItemScope,
    enabled: Boolean,
    state: AsteriskReorderableLazyListState,
): Modifier {
    return with(scope) {
        this@longPressReorderDragHandle.longPressDraggableHandle(
            enabled = enabled,
            onDragStarted = {
                state.hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
            },
            onDragStopped = {
                state.hapticFeedback.performHapticFeedback(HapticFeedbackType.GestureEnd)
            },
        )
    }
}
