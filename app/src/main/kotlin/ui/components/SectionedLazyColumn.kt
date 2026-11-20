// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Keeps normal row spacing independent of the 8dp spacing around section titles. */
@Composable
internal fun SectionedLazyColumn(
    itemSpacing: Dp,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    state: LazyListState = rememberLazyListState(),
    content: SectionedListScope.() -> Unit,
) {
    LazyColumn(
        modifier = modifier,
        contentPadding = contentPadding,
        state = state,
    ) {
        SectionedListScope(this, itemSpacing).content()
    }
}

internal class SectionedListScope(
    private val target: LazyListScope,
    private val itemSpacing: Dp,
) : LazyListScope by target {
    private var firstItem = true
    private var previousWasTitle = false

    /** Title content must not add its own vertical padding. */
    fun sectionTitleItem(
        key: Any,
        content: @Composable LazyItemScope.() -> Unit,
    ) {
        target.item(key = key) {
            val itemScope = this
            Column(Modifier.padding(vertical = 8.dp)) { content(itemScope) }
        }
        firstItem = false
        previousWasTitle = true
    }

    override fun item(
        key: Any?,
        contentType: Any?,
        content: @Composable LazyItemScope.() -> Unit,
    ) {
        itemWithPadding(key, contentType) { padding ->
            val itemScope = this
            Column(padding) { content(itemScope) }
        }
    }

    /** Passes section spacing to an item that supplies its own layout root. */
    fun itemWithPadding(
        key: Any?,
        contentType: Any? = null,
        content: @Composable LazyItemScope.(Modifier) -> Unit,
    ) {
        val padding = Modifier.padding(top = nextItemPadding())
        target.item(key, contentType) { content(padding) }
        firstItem = false
        previousWasTitle = false
    }

    override fun items(
        count: Int,
        key: ((Int) -> Any)?,
        contentType: (Int) -> Any?,
        itemContent: @Composable LazyItemScope.(Int) -> Unit,
    ) {
        val firstItemPadding = nextItemPadding()
        val remainingItemPadding = itemSpacing
        target.items(count, key, contentType) { index ->
            val itemScope = this
            Column(Modifier.padding(top = if (index == 0) firstItemPadding else remainingItemPadding)) {
                itemContent(itemScope, index)
            }
        }
        if (count > 0) {
            firstItem = false
            previousWasTitle = false
        }
    }

    private fun nextItemPadding(): Dp = if (firstItem || previousWasTitle) 0.dp else itemSpacing
}
