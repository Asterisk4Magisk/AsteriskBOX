// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import app.AppState
import app.LocalAppStateStore
import app.LocalUpdateAppState
import app.collectAppState
import kotlinx.coroutines.launch

/**
 * Editable controller for the home dashboard.
 *
 * Owns the working copy of the [HomeDashboardLayout] while the user is in
 * edit mode. On commit the working copy replaces the live state via
 * [updateAppState] (which persists to Room through the same path every
 * other AppState field uses); on cancel the working copy is discarded and
 * the live state is the source of truth again.
 *
 * The controller never mutates [AppState] outside of an active edit
 * session: the entire snapshot/restore contract is local.
 */
@Stable
class HomeDashboardController internal constructor(
    private val commitLayout: (HomeDashboardLayout) -> Unit,
) {
    internal var workingLayout: HomeDashboardLayout? by mutableStateOf(null)
        private set

    /** The layout the dashboard is currently rendering. */
    val layout: HomeDashboardLayout
        get() = workingLayout ?: liveLayout

    private var liveLayout: HomeDashboardLayout = DefaultHomeDashboardLayout
        set(value) {
            field = value
            if (workingLayout == null) layoutRevision.value = value.revision
        }

    private val layoutRevision = androidx.compose.runtime.mutableLongStateOf(0L)
    val revision: Long get() = layoutRevision.longValue

    fun bindLive(layout: HomeDashboardLayout) {
        liveLayout = layout
    }

    /** Begin a new edit session, capturing a snapshot to restore on cancel. */
    fun beginEdit() {
        if (workingLayout != null) return
        workingLayout = liveLayout
    }

    /**
     * Discard the working copy and return to live state. The snapshot taken
     * by [beginEdit] is restored automatically by the next read of [layout].
     */
    fun cancelEdit() {
        workingLayout = null
        layoutRevision.value = liveLayout.revision + 1
    }

    /**
     * Commit the working copy to the persistent AppState. After this
     * returns, the controller is in the live state again.
     */
    fun commit() {
        val pending = workingLayout ?: return
        commitLayout(pending.copy(revision = pending.revision + 1))
        workingLayout = null
    }

    /** True when an edit session is active (working copy is in use). */
    val isEditing: Boolean
        get() = workingLayout != null

    private fun mutate(transform: (HomeDashboardLayout) -> HomeDashboardLayout) {
        val current = workingLayout ?: return
        workingLayout = transform(current)
    }

    /** Reorder the visible cards by id. Hidden cards are untouched. */
    fun reorderVisible(newOrder: List<String>) {
        mutate { layout ->
            layout.copy(cards = layout.cards.reorderVisible(newOrder))
        }
    }

    /** Move the card with [id] to the hidden list (a.k.a. delete from the dashboard). */
    fun hide(id: String) {
        mutate { layout ->
            val target = layout.findById(id) ?: return@mutate layout
            if (!target.visible) return@mutate layout
            layout.copy(
                cards = layout.cards.map { card ->
                    if (card.id == id) card.copy(visible = false) else card
                },
            )
        }
    }

    /** Move the card with [id] back to the visible list. */
    fun show(id: String) {
        mutate { layout ->
            val target = layout.findById(id) ?: return@mutate layout
            if (target.visible) return@mutate layout
            layout.copy(
                cards = layout.cards.map { card ->
                    if (card.id == id) card.copy(visible = true) else card
                },
            )
        }
    }

    /**
     * Toggle size between Small and Large. Singletons that only support a
     * single size are left unchanged.
     */
    fun resize(id: String) {
        mutate { layout ->
            val target = layout.findById(id) ?: return@mutate layout
            if (!target.kind.allowsResize) return@mutate layout
            layout.copy(
                cards = layout.cards.map { card ->
                    if (card.id == id) {
                        card.copy(
                            size = when (card.size) {
                                HomeCardSize.Small -> HomeCardSize.Large
                                HomeCardSize.Large -> HomeCardSize.Small
                            },
                        )
                    } else {
                        card
                    }
                },
            )
        }
    }

    /** Append a new usage card instance bound to the supplied [groupId]. */
    fun addUsage(groupId: Int): HomeCardInstance {
        val newInstance = HomeCardInstance(
            id = newHomeCardInstanceId(),
            kind = HomeCardKind.Usage,
            size = HomeCardSize.Large,
            visible = true,
            config = HomeCardConfig(groupId = groupId),
        )
        mutate { layout ->
            // Append at the end of the visible list so the user sees the
            // new card without losing the current order.
            val visible = layout.visibleCards()
            val hidden = layout.hiddenCards()
            val newVisible = visible + newInstance
            layout.copy(cards = newVisible + hidden)
        }
        return newInstance
    }

    /** Append a new empty stacked card. */
    fun addStacked(): HomeCardInstance {
        val newInstance = HomeCardInstance(
            id = newHomeCardInstanceId(),
            kind = HomeCardKind.Stacked,
            size = HomeCardSize.Large,
            visible = true,
            config = HomeCardConfig(stackedCardIds = emptyList()),
        )
        mutate { layout ->
            val visible = layout.visibleCards()
            val hidden = layout.hiddenCards()
            layout.copy(cards = visible + newInstance + hidden)
        }
        return newInstance
    }

    /** Update the [HomeCardConfig] for a specific card instance. */
    fun updateConfig(id: String, transform: (HomeCardConfig) -> HomeCardConfig) {
        mutate { layout ->
            layout.copy(
                cards = layout.cards.map { card ->
                    if (card.id == id) card.copy(config = transform(card.config)) else card
                },
            )
        }
    }

    /**
     * Add a card instance id to a stacked card's inner list. Used when the
     * user picks "add to a stacked card" from a card's edit menu. The new
     * inner page uses the same instance (so the inner card keeps its
     * configuration); the stack's page cursor stays where it was.
     */
    fun stackInclude(stackedId: String, childId: String) {
        mutate { layout ->
            val target = layout.findById(stackedId) ?: return@mutate layout
            if (target.kind != HomeCardKind.Stacked) return@mutate layout
            if (childId == stackedId) return@mutate layout
            if (childId in target.config.stackedCardIds) return@mutate layout
            layout.copy(
                cards = layout.cards.map { card ->
                    if (card.id == stackedId) {
                        card.copy(
                            config = card.config.copy(
                                stackedCardIds = card.config.stackedCardIds + childId,
                            ),
                        )
                    } else {
                        card
                    }
                },
            )
        }
    }

    fun stackRemove(stackedId: String, childId: String) {
        mutate { layout ->
            val target = layout.findById(stackedId) ?: return@mutate layout
            if (target.kind != HomeCardKind.Stacked) return@mutate layout
            layout.copy(
                cards = layout.cards.map { card ->
                    if (card.id == stackedId) {
                        val newIds = card.config.stackedCardIds.filterNot { it == childId }
                        val newPage = card.config.stackedPage
                            .coerceAtMost((newIds.size - 1).coerceAtLeast(0))
                        card.copy(
                            config = card.config.copy(
                                stackedCardIds = newIds,
                                stackedPage = newPage,
                            ),
                        )
                    } else {
                        card
                    }
                },
            )
        }
    }

    fun stackSetPage(stackedId: String, page: Int) {
        mutate { layout ->
            val target = layout.findById(stackedId) ?: return@mutate layout
            if (target.kind != HomeCardKind.Stacked) return@mutate layout
            val pageCount = target.config.stackedCardIds.size
            val clamped = page.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
            if (clamped == target.config.stackedPage) return@mutate layout
            layout.copy(
                cards = layout.cards.map { card ->
                    if (card.id == stackedId) {
                        card.copy(config = card.config.copy(stackedPage = clamped))
                    } else {
                        card
                    }
                },
            )
        }
    }

    fun stackSetAutoRotate(stackedId: String, autoRotate: Boolean) {
        mutate { layout ->
            val target = layout.findById(stackedId) ?: return@mutate layout
            if (target.kind != HomeCardKind.Stacked) return@mutate layout
            if (target.config.stackedAutoRotate == autoRotate) return@mutate layout
            layout.copy(
                cards = layout.cards.map { card ->
                    if (card.id == stackedId) {
                        card.copy(
                            config = card.config.copy(stackedAutoRotate = autoRotate),
                        )
                    } else {
                        card
                    }
                },
            )
        }
    }
}

internal val HomeCardKind.allowsResize: Boolean
    get() = when (this) {
        HomeCardKind.NetworkActivity,
        HomeCardKind.Usage,
        HomeCardKind.Stacked,
        -> true
        HomeCardKind.Controller,
        HomeCardKind.MonitorResource,
        HomeCardKind.MonitorConnections,
        HomeCardKind.MonitorTraffic,
        HomeCardKind.MonitorNetwork,
        HomeCardKind.NetworkQualityTest,
        -> false
    }

/**
 * Resolves the default groupId for a freshly added usage card.
 *
 * Preference order:
 *  - The group that currently contains the running proxy's selected node, if any.
 *  - The first enabled group.
 *  - The first group.
 *  - `0` (no group available).
 */
internal fun defaultUsageGroupId(state: AppState): Int {
    val selectedOutboundTag = state.selectorSelections
        .values
        .firstOrNull { tag -> tag.isNotBlank() }
        ?: return state.outboundGroups.firstOrNull { it.enabled }?.id
            ?: state.outboundGroups.firstOrNull()?.id
            ?: 0
    val selectedOutbound = state.outbounds.firstOrNull { it.tag == selectedOutboundTag }
    if (selectedOutbound != null) return selectedOutbound.groupId
    return state.outboundGroups.firstOrNull { it.enabled }?.id
        ?: state.outboundGroups.firstOrNull()?.id
        ?: 0
}

@Composable
internal fun rememberHomeDashboardController(): HomeDashboardController {
    val updateAppState = LocalUpdateAppState.current
    val scope = rememberCoroutineScope()
    val commit: (HomeDashboardLayout) -> Unit = remember(updateAppState, scope) {
        { newLayout ->
            scope.launch {
                updateAppState { state -> state.copy(homeDashboard = newLayout) }
            }
        }
    }
    return remember(commit) { HomeDashboardController(commit) }
}

/**
 * Couples the controller to the live [AppState] so the layout always
 * reflects persistence after a restart or a settings import.
 */
@Composable
internal fun BindHomeDashboardController(controller: HomeDashboardController) {
    val appState by LocalAppStateStore.current.collectAppState()
    val latestAppState by rememberUpdatedState(appState)
    LaunchedEffect(latestAppState.homeDashboard.revision) {
        controller.bindLive(latestAppState.homeDashboard)
    }
}
