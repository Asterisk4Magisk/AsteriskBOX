// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.home

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * Card kinds supported on the home dashboard.
 *
 * Most kinds render one card on the home flow and can never be removed or
 * hidden: the service controller, the live network activity, the four
 * monitoring entries.
 *
 * [Usage] and [Stacked] are user-instantiable: every visible or hidden card of
 * those kinds is a distinct instance keyed by [HomeCardInstance.id]. The model
 * deliberately does not collapse instances of the same kind into a single
 * template, so a user with three usage cards on the dashboard (and two more
 * hidden) keeps five independent [HomeCardInstance] records.
 */
@Serializable
enum class HomeCardKind {
    Controller,
    NetworkActivity,
    NetworkQualityTest,
    Usage,
    Stacked,
    MonitorResource,
    MonitorConnections,
    MonitorTraffic,
    MonitorNetwork,
}

/**
 * Two size tiers the dashboard understands. Every instance carries its own
 * size so the resize buttons can move the slider per card without losing
 * the user's choice after a reorder or hide/restore.
 */
@Serializable
enum class HomeCardSize {
    Small,
    Large,
}

/**
 * Per-instance configuration the dashboard needs but that does not belong in
 * [AppState] itself.
 *
 * The fields used by a card depend on its [HomeCardInstance.kind]:
 *  - [Usage] instances use [groupId] to bind to an [app.OutboundGroupState].
 *  - [Stacked] instances use [stackedCardIds] / [stackedPage] /
 *    [stackedAutoRotate] to model the inner pager and its auto-rotation.
 *  - All other kinds ignore the configuration entirely.
 */
@Serializable
data class HomeCardConfig(
    val groupId: Int = 0,
    val stackedCardIds: List<String> = emptyList(),
    val stackedPage: Int = 0,
    val stackedAutoRotate: Boolean = false,
)

/**
 * One concrete card on the dashboard.
 *
 * The combination of [kind] + [id] is unique. [id] is assigned when an
 * instance is first created and is stable for the lifetime of that
 * instance, so a usage card that is hidden and later restored keeps its
 * configuration across the round-trip.
 *
 * The dashboard never identifies a card by its display title or by its
 * kind alone: the same [HomeCardKind] can have many instances, and the
 * "hidden" list is just another slice of the same instance pool.
 */
@Serializable
data class HomeCardInstance(
    val id: String,
    val kind: HomeCardKind,
    val size: HomeCardSize,
    val visible: Boolean,
    val config: HomeCardConfig = HomeCardConfig(),
)

/**
 * Complete dashboard configuration persisted to SharedPreferences.
 *
 * The visible list always comes first in the render order; the hidden list
 * preserves the most-recently-hidden order so the "hidden cards" section
 * reads naturally even after the user hides several cards in a row.
 */
@Serializable
data class HomeDashboardLayout(
    val cards: List<HomeCardInstance> = emptyList(),
    val revision: Long = 0L,
)

/** Default home dashboard, used on first launch or after a corrupted state. */
val DefaultHomeDashboardLayout: HomeDashboardLayout
    get() = HomeDashboardLayout(
        cards = listOf(
            HomeCardInstance(
                id = HomeCardInstanceId.Controller,
                kind = HomeCardKind.Controller,
                size = HomeCardSize.Large,
                visible = true,
            ),
            HomeCardInstance(
                id = HomeCardInstanceId.NetworkActivity,
                kind = HomeCardKind.NetworkActivity,
                size = HomeCardSize.Large,
                visible = true,
            ),
            HomeCardInstance(
                id = HomeCardInstanceId.MonitorResource,
                kind = HomeCardKind.MonitorResource,
                size = HomeCardSize.Small,
                visible = true,
            ),
            HomeCardInstance(
                id = HomeCardInstanceId.MonitorConnections,
                kind = HomeCardKind.MonitorConnections,
                size = HomeCardSize.Small,
                visible = true,
            ),
            HomeCardInstance(
                id = HomeCardInstanceId.MonitorTraffic,
                kind = HomeCardKind.MonitorTraffic,
                size = HomeCardSize.Small,
                visible = true,
            ),
            HomeCardInstance(
                id = HomeCardInstanceId.MonitorNetwork,
                kind = HomeCardKind.MonitorNetwork,
                size = HomeCardSize.Small,
                visible = true,
            ),
        ),
    )

/** Stable IDs for the singleton cards. Multi-instance cards get UUIDs. */
object HomeCardInstanceId {
    const val Controller: String = "controller"
    const val NetworkActivity: String = "network_activity"
    const val MonitorResource: String = "monitor_resource"
    const val MonitorConnections: String = "monitor_connections"
    const val MonitorTraffic: String = "monitor_traffic"
    const val MonitorNetwork: String = "monitor_network"
}

internal fun newHomeCardInstanceId(): String = "card_" + UUID.randomUUID().toString().take(8)

internal fun HomeDashboardLayout.visibleCards(): List<HomeCardInstance> =
    cards.filter { it.visible }

internal fun HomeDashboardLayout.hiddenCards(): List<HomeCardInstance> =
    cards.filterNot { it.visible }

internal fun HomeDashboardLayout.findById(id: String): HomeCardInstance? =
    cards.firstOrNull { it.id == id }

/** Returns the cards in render order (visible first, then hidden). */
internal fun HomeDashboardLayout.orderedForRender(): List<HomeCardInstance> =
    visibleCards() + hiddenCards()

/**
 * Returns the IDs of visible cards in the current order, used for the
 * drag-reorder session.
 */
internal fun HomeDashboardLayout.visibleIds(): List<String> =
    visibleCards().map { it.id }

internal fun List<HomeCardInstance>.reorderVisible(
    newOrder: List<String>,
): List<HomeCardInstance> {
    if (newOrder.isEmpty()) return this
    val newOrderSet = newOrder.toSet()
    if (newOrderSet.size != newOrder.size) return this
    val hidden = filterNot { it.visible }
    val byId = filter { it.visible }.associateBy { it.id }
    if (!byId.keys.containsAll(newOrderSet)) return this
    val reorderedVisible = newOrder.map { id -> byId.getValue(id) }
    return reorderedVisible + hidden
}

private val homeDashboardLayoutJson = Json {
    encodeDefaults = true
    ignoreUnknownKeys = true
}

internal object HomeDashboardLayoutJson {
    fun encode(layout: HomeDashboardLayout): String =
        homeDashboardLayoutJson.encodeToString(HomeDashboardLayout.serializer(), layout)

    fun decode(payload: String?): HomeDashboardLayout? {
        if (payload.isNullOrBlank()) return null
        return runCatching {
            homeDashboardLayoutJson.decodeFromString(HomeDashboardLayout.serializer(), payload)
        }.getOrNull()
    }
}

/**
 * Sanitizes a freshly loaded [HomeDashboardLayout]:
 *  - drops instances whose kind is no longer supported;
 *  - ensures the singleton kinds have exactly one visible instance;
 *  - assigns new IDs to duplicate instances.
 *
 * Returns a new layout when changes are required, or the original instance
 * when the loaded state already matches the rules.
 */
internal fun sanitizeHomeDashboardLayout(
    loaded: HomeDashboardLayout,
    default: HomeDashboardLayout = DefaultHomeDashboardLayout,
): HomeDashboardLayout {
    val supportedKinds = HomeCardKind.entries.toSet()
    val sanitizedCards = mutableListOf<HomeCardInstance>()
    val seenIds = mutableSetOf<String>()
    loaded.cards.forEach { card ->
        if (card.kind !in supportedKinds) return@forEach
        val uniqueId = if (card.id in seenIds) newHomeCardInstanceId() else card.id
        seenIds += uniqueId
        sanitizedCards += card.copy(id = uniqueId)
    }
    // Backfill: every required singleton that was lost or never persisted
    // gets re-inserted at the end of the visible list in its default order.
    val presentSingletonIds = sanitizedCards
        .filter { it.kind.isSingleton }
        .map { it.id }
        .toSet()
    val missingSingletons = default.cards
        .filter { it.kind.isSingleton && it.id !in presentSingletonIds }
    sanitizedCards += missingSingletons
    val first = sanitizedCards.firstOrNull()
    if (first == null) return default
    return loaded.copy(cards = sanitizedCards).takeIf { it != loaded } ?: loaded
}

private val HomeCardKind.isSingleton: Boolean
    get() = when (this) {
        HomeCardKind.Usage, HomeCardKind.Stacked, HomeCardKind.NetworkQualityTest -> false
        HomeCardKind.Controller,
        HomeCardKind.NetworkActivity,
        HomeCardKind.MonitorResource,
        HomeCardKind.MonitorConnections,
        HomeCardKind.MonitorTraffic,
        HomeCardKind.MonitorNetwork,
        -> true
    }
