// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package ui.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import sh.calvin.reorderable.ReorderableCollectionItemScope
import sh.calvin.reorderable.ReorderableLazyGridState
import sh.calvin.reorderable.rememberReorderableLazyGridState

internal class AsteriskReorderableLazyGridState(
    val reorderableState: ReorderableLazyGridState,
    val hapticFeedback: HapticFeedback,
)

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
        val fromIndex = from.index - indexOffset
        val toIndex = to.index - indexOffset
        if (fromIndex == toIndex || fromIndex !in 0 until itemCount || toIndex !in 0 until itemCount) {
            return@rememberReorderableLazyGridState
        }
        onMove(fromIndex, toIndex)
        hapticFeedback.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
    }
    return AsteriskReorderableLazyGridState(reorderableState, hapticFeedback)
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
