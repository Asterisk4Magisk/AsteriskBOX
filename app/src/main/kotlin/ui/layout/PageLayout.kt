// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package ui.layout

import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

fun Modifier.pageReadableWidth(maxWidth: Dp = 1120.dp): Modifier = this
    .fillMaxWidth()
    .wrapContentWidth(Alignment.CenterHorizontally)
    .widthIn(max = maxWidth)
    .fillMaxWidth()

@Composable
internal fun rememberRuleGridColumns(maxWidth: Dp, padding: PaddingValues): Int {
    val direction = LocalLayoutDirection.current
    val contentWidth = maxWidth - padding.calculateStartPadding(direction) - padding.calculateEndPadding(direction)
    val minimumCardWidth = 320.dp * LocalDensity.current.fontScale.coerceAtLeast(1f)
    return if (contentWidth >= minimumCardWidth * 2 + 12.dp) 2 else 1
}

internal val SingleColumnPageMaxWidth = 760.dp

internal fun Modifier.singleColumnPageWidth(): Modifier =
    pageReadableWidth(maxWidth = SingleColumnPageMaxWidth)

@Composable
fun Modifier.pageHorizontalPadding(): Modifier = pageReadableWidth()
    .padding(horizontal = rememberPageGutter())

@Composable
fun Modifier.cutoutHorizontalPadding(): Modifier {
    val layoutDirection = LocalLayoutDirection.current
    val cutoutPadding = WindowInsets.displayCutout.asPaddingValues()
    return padding(
        start = cutoutPadding.calculateStartPadding(layoutDirection),
        end = cutoutPadding.calculateEndPadding(layoutDirection),
    )
}

fun Modifier.pageScrollModifiers(
    topAppBarScrollBehavior: TopAppBarScrollBehavior,
): Modifier = this
    .nestedScroll(topAppBarScrollBehavior.nestedScrollConnection)
    .fillMaxHeight()

@Composable
fun Modifier.pageWindowPadding(
    outerPadding: PaddingValues,
): Modifier {
    val layoutDirection = LocalLayoutDirection.current
    return padding(
        start = outerPadding.calculateStartPadding(layoutDirection),
        end = outerPadding.calculateEndPadding(layoutDirection),
    )
}

@Composable
fun pageContentPadding(
    innerPadding: PaddingValues,
    outerPadding: PaddingValues,
    isWideScreen: Boolean,
    extraTop: Dp = 0.dp,
    extraStart: Dp = 0.dp,
    extraEnd: Dp = 0.dp,
): PaddingValues {
    val topPadding = innerPadding.calculateTopPadding() + extraTop
    val outerBottomPadding = if (isWideScreen) {
        WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + outerPadding.calculateBottomPadding()
    } else {
        outerPadding.calculateBottomPadding()
    }
    // Standalone windows have no outer page padding; retain their Scaffold's system-bar inset.
    val bottomPadding = maxOf(innerPadding.calculateBottomPadding(), outerBottomPadding)
    return remember(topPadding, bottomPadding, extraStart, extraEnd) {
        PaddingValues(
            top = topPadding,
            start = extraStart,
            end = extraEnd,
            bottom = bottomPadding,
        )
    }
}

@Composable
fun pageContentPaddingWithCutout(
    innerPadding: PaddingValues,
    outerPadding: PaddingValues,
    isWideScreen: Boolean,
    extraTop: Dp = 0.dp,
): PaddingValues {
    val cutoutPadding = WindowInsets.displayCutout.asPaddingValues()
    return pageContentPadding(
        innerPadding = innerPadding,
        outerPadding = outerPadding,
        isWideScreen = isWideScreen,
        extraTop = extraTop,
        extraStart = cutoutPadding.calculateStartPadding(LayoutDirection.Ltr),
        extraEnd = cutoutPadding.calculateEndPadding(LayoutDirection.Ltr),
    )
}

@Composable
fun pageListPadding(
    contentPadding: PaddingValues,
    bottomExtra: Dp = 12.dp,
    horizontalExtra: Dp = rememberPageGutter(),
    topExtra: Dp = 8.dp,
): PaddingValues {
    val layoutDirection = LocalLayoutDirection.current
    return PaddingValues(
        top = contentPadding.calculateTopPadding() + topExtra,
        start = contentPadding.calculateStartPadding(layoutDirection) + horizontalExtra,
        end = contentPadding.calculateEndPadding(layoutDirection) + horizontalExtra,
        bottom = contentPadding.calculateBottomPadding() + bottomExtra,
    )
}
