// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package ui.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

internal fun Modifier.draggedCardShadow(
    alpha: Float,
    color: Color,
    cornerRadius: Dp,
): Modifier {
    if (alpha <= 0f) return this
    return drawBehind {
        val cardCornerRadiusPx = cornerRadius.toPx()
        val maxSpread = 12.dp.toPx()
        val steps = 12
        for (step in steps downTo 1) {
            val progress = step / steps.toFloat()
            val spread = maxSpread * progress
            val layerAlpha = alpha * 0.035f * (1f - (step - 1f) / steps)
            drawRoundRect(
                color = color.copy(alpha = layerAlpha),
                topLeft = Offset(-spread, -spread),
                size = Size(size.width + spread * 2, size.height + spread * 2),
                cornerRadius = CornerRadius(
                    draggedCardShadowLayerCornerRadius(cardCornerRadiusPx, spread),
                ),
            )
        }
    }
}

internal fun draggedCardShadowLayerCornerRadius(
    cardCornerRadiusPx: Float,
    spreadPx: Float,
): Float = cardCornerRadiusPx + spreadPx
