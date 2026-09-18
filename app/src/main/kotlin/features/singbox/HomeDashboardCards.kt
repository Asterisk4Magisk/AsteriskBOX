// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package features.singbox

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.AppState
import app.OutboundGroupState
import app.R
import app.SubscriptionInfo
import features.home.HomeCardInstance
import features.home.HomeCardKind
import features.home.HomeCardSize
import features.home.HomeDashboardController
import features.home.HomeDashboardLayout
import features.home.findById
import ui.components.AsteriskExpressiveCard
import ui.components.AsteriskPageCard
import ui.icons.AsteriskIcons as Icons
import ui.theme.AsteriskShapeTokens
import ui.theme.ExpressiveShapeRole
import utils.toReadableBytes
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/**
 * Card-level title used in the stacked-card manage sheet. Keeps the chrome
 * consistent with the rest of the dashboard by mirroring what the user
 * sees in the home flow.
 */
@Composable
internal fun cardTitle(card: HomeCardInstance): String = when (card.kind) {
    HomeCardKind.Controller -> stringResource(R.string.home_service_enabled)
    HomeCardKind.NetworkActivity -> stringResource(R.string.home_network_activity)
    HomeCardKind.NetworkQualityTest -> stringResource(R.string.home_dashboard_network_quality_title)
    HomeCardKind.Usage -> stringResource(R.string.home_dashboard_usage_title)
    HomeCardKind.Stacked -> stringResource(R.string.home_dashboard_stacked_title)
    HomeCardKind.MonitorResource -> stringResource(R.string.home_monitor_resource)
    HomeCardKind.MonitorConnections -> stringResource(R.string.home_monitor_connections)
    HomeCardKind.MonitorTraffic -> stringResource(R.string.home_monitor_traffic)
    HomeCardKind.MonitorNetwork -> stringResource(R.string.home_monitor_network)
}


// ===== Usage card =====

@Composable
internal fun UsageCard(
    card: HomeCardInstance,
    appState: AppState,
    size: HomeCardSize,
    isEditing: Boolean,
    onClick: () -> Unit,
    onPickGroup: (Int) -> Unit,
) {
    val group = appState.outboundGroups.firstOrNull { it.id == card.config.groupId }
        ?: appState.outboundGroups.firstOrNull()
    val isLarge = size == HomeCardSize.Large
    if (isLarge) {
        AsteriskPageCard(modifier = Modifier
            .fillMaxWidth()
            .height(168.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                UsageHeader(group, onClick)
                Spacer(Modifier.height(8.dp))
                UsageBar(group?.subscriptionInfo)
                Spacer(Modifier.height(8.dp))
                UsageFooterRow(group?.subscriptionInfo)
            }
        }
    } else {
        AsteriskExpressiveCard(
            onClick = onClick,
            modifier = Modifier
                .fillMaxWidth()
                .height(148.dp),
            role = ExpressiveShapeRole.ContentCard,
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                UsageRing(group?.subscriptionInfo)
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.home_dashboard_usage_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = group?.name ?: stringResource(R.string.home_dashboard_usage_no_data),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(4.dp))
                    UsageCompactLabel(group?.subscriptionInfo)
                }
            }
        }
    }
}

@Composable
private fun UsageHeader(group: OutboundGroupState?, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Rounded.PieChart,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.size(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.home_dashboard_usage_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = group?.name ?: stringResource(R.string.home_dashboard_usage_no_data),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        androidx.compose.material3.TextButton(onClick = onClick) {
            Text(stringResource(R.string.home_dashboard_card_open))
        }
    }
}

@Composable
private fun UsageBar(info: SubscriptionInfo?) {
    val progress = info?.usageProgress ?: 0f
    val animated by animateFloatAsState(
        targetValue = progress,
        animationSpec = tween(450),
        label = "usage-bar-progress",
    )
    val track = MaterialTheme.colorScheme.surfaceContainerHigh
    val fill = MaterialTheme.colorScheme.primary
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(10.dp),
    ) {
        val radius = size.height / 2f
        drawRoundRect(
            color = track,
            size = size,
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius, radius),
        )
        if (animated > 0f) {
            val width = size.width * animated.coerceIn(0f, 1f)
            drawRoundRect(
                color = fill,
                size = Size(width, size.height),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius, radius),
            )
        }
    }
}

@Composable
private fun UsageRing(info: SubscriptionInfo?) {
    val progress = info?.usageProgress ?: 0f
    val animated by animateFloatAsState(
        targetValue = progress,
        animationSpec = tween(450),
        label = "usage-ring-progress",
    )
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    val fill = MaterialTheme.colorScheme.primary
    Box(
        modifier = Modifier.size(72.dp),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val stroke = 8.dp.toPx()
            val pad = stroke / 2f
            val arcSize = Size(size.width - stroke, size.height - stroke)
            drawArc(
                color = track,
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = Offset(pad, pad),
                size = arcSize,
                style = Stroke(stroke, cap = StrokeCap.Round),
            )
            if (animated > 0f) {
                drawArc(
                    color = fill,
                    startAngle = -90f,
                    sweepAngle = 360f * animated.coerceIn(0f, 1f),
                    useCenter = false,
                    topLeft = Offset(pad, pad),
                    size = arcSize,
                    style = Stroke(stroke, cap = StrokeCap.Round),
                )
            }
        }
        val centerText = if (info == null || !info.hasMeteredQuota) {
            "—"
        } else {
            "${(animated * 100).toInt()}%"
        }
        Text(
            text = centerText,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun UsageFooterRow(info: SubscriptionInfo?) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        UsageStat(
            label = stringResource(R.string.home_dashboard_usage_used),
            value = info?.usedBytes?.toReadableBytes() ?: HomeDashUnavailable,
        )
        UsageStat(
            label = stringResource(R.string.home_dashboard_usage_remaining),
            value = info?.remainingBytes?.toReadableBytes() ?: HomeDashUnavailable,
        )
        UsageStat(
            label = stringResource(R.string.home_dashboard_usage_expires),
            value = formatExpiry(info?.expireAtSeconds ?: 0L),
            align = Alignment.End,
        )
    }
}

@Composable
private fun UsageStat(
    label: String,
    value: String,
    align: Alignment.Horizontal = Alignment.Start,
) {
    Column(horizontalAlignment = align) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun UsageCompactLabel(info: SubscriptionInfo?) {
    if (info == null) {
        Text(
            text = stringResource(R.string.home_dashboard_usage_no_data),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    if (!info.hasMeteredQuota) {
        Text(
            text = stringResource(R.string.home_dashboard_usage_unmetered),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    val remaining = info.remainingBytes.toReadableBytes()
    Text(
        text = stringResource(R.string.home_dashboard_usage_remaining) + " · " + remaining,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private fun formatExpiry(seconds: Long): String {
    if (seconds <= 0L) return HomeDashUnavailable
    val millis = seconds * 1000L
    val date = Date(millis)
    return DateFormat.getDateInstance(DateFormat.MEDIUM).format(date)
}

private const val HomeDashUnavailable = "—"

// ===== Network quality test card =====

@Composable
internal fun NetworkQualityTestCard(
    size: HomeCardSize,
    onOpen: () -> Unit,
) {
    val isLarge = size == HomeCardSize.Large
    val height = if (isLarge) 132.dp else 96.dp
    AsteriskExpressiveCard(
        onClick = onOpen,
        modifier = Modifier
            .fillMaxWidth()
            .height(height),
        role = if (isLarge) ExpressiveShapeRole.GroupLarge else ExpressiveShapeRole.ContentCard,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Surface(
                modifier = Modifier.size(42.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                shape = AsteriskShapeTokens.SmallContainer,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Rounded.Speed,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.home_dashboard_network_quality_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(R.string.home_dashboard_network_quality_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

// ===== Stacked card =====

@Composable
internal fun StackedCard(
    card: HomeCardInstance,
    layout: HomeDashboardLayout,
    appState: AppState,
    size: HomeCardSize,
    isEditing: Boolean,
    controller: HomeDashboardController,
    onClickChild: (HomeCardInstance) -> Unit,
    onToggleAutoRotate: (Boolean) -> Unit,
    onRemoveChild: (String) -> Unit,
    onAddChild: (String) -> Unit,
) {
    val isLarge = size == HomeCardSize.Large
    val height = if (isLarge) 200.dp else 148.dp
    val children = card.config.stackedCardIds.mapNotNull { id -> layout.findById(id) }
    val pageCount = children.size.coerceAtLeast(1)
    val pageState = rememberPagerState(
        initialPage = card.config.stackedPage.coerceIn(0, pageCount - 1),
        pageCount = { pageCount },
    )
    val scope = rememberCoroutineScope()
    val autoRotate = card.config.stackedAutoRotate
    LaunchedEffect(pageState.currentPage, pageCount) {
        if (pageState.currentPage != card.config.stackedPage) {
            controller.stackSetPage(card.id, pageState.currentPage)
        }
    }
    LaunchedEffect(autoRotate, children.size, card.id) {
        if (!autoRotate || children.size < 2) return@LaunchedEffect
        while (true) {
            delay(StackedAutoRotateIntervalMillis)
            val next = (pageState.currentPage + 1) % children.size
            scope.launch {
                pageState.animateScrollToPage(next)
            }
        }
    }
    AsteriskPageCard(
        modifier = Modifier
            .fillMaxWidth()
            .height(height),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = stringResource(R.string.home_dashboard_stacked_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                if (children.size > 1) {
                    Text(
                        text = stringResource(
                            R.string.home_dashboard_stacked_page_label,
                            pageState.currentPage + 1,
                            children.size,
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                if (children.isEmpty()) {
                    Text(
                        text = stringResource(R.string.home_dashboard_stacked_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    HorizontalPager(
                        state = pageState,
                        modifier = Modifier.fillMaxSize(),
                    ) { page ->
                        val child = children[page]
                        StackedChild(child, appState, onClickChild)
                    }
                }
            }
            if (children.size > 1) {
                Spacer(Modifier.height(6.dp))
                StackedIndicator(
                    current = pageState.currentPage,
                    total = children.size,
                )
            }
        }
    }
}

@Composable
private fun StackedIndicator(
    current: Int,
    total: Int,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(total) { index ->
            val active = index == current
            Box(
                modifier = Modifier
                    .padding(horizontal = 2.dp)
                    .width(if (active) 14.dp else 6.dp)
                    .height(3.dp)
                    .background(
                        color = if (active) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.outlineVariant,
                        shape = MaterialTheme.shapes.small,
                    ),
            )
        }
    }
}

@Composable
private fun StackedChild(
    card: HomeCardInstance,
    appState: AppState,
    onClick: (HomeCardInstance) -> Unit,
) {
    when (card.kind) {
        HomeCardKind.NetworkQualityTest -> NetworkQualityTestCard(size = HomeCardSize.Large, onOpen = { onClick(card) })
        HomeCardKind.Usage -> UsageCard(
            card = card,
            appState = appState,
            size = HomeCardSize.Large,
            isEditing = false,
            onClick = { onClick(card) },
            onPickGroup = {},
        )
        HomeCardKind.MonitorResource,
        HomeCardKind.MonitorConnections,
        HomeCardKind.MonitorTraffic,
        HomeCardKind.MonitorNetwork,
        -> {
            val title = cardTitle(card)
            val summary = ""
            AsteriskExpressiveCard(
                onClick = { onClick(card) },
                modifier = Modifier.fillMaxSize(),
                role = ExpressiveShapeRole.ContentCard,
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text(text = title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        HomeCardKind.NetworkActivity -> {
            AsteriskPageCard(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.home_network_activity),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
        else -> {
            // Singleton or unsupported inside a stack — surface a generic
            // placeholder so the pager still has something to render.
            AsteriskExpressiveCard(
                onClick = { onClick(card) },
                modifier = Modifier.fillMaxSize(),
                role = ExpressiveShapeRole.ContentCard,
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(text = cardTitle(card), style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}

private const val StackedAutoRotateIntervalMillis = 4_000L
