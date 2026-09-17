// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.settings.sheets

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.LocalAppServices
import app.LocalAppStateStore
import app.R
import app.selectableManagedOutbounds
import app.visibleManagedReference
import ui.components.AsteriskDropdownAnchor
import ui.components.AsteriskDropdownMenuItem
import ui.components.localizedLabel
import java.util.Locale
import ui.icons.AsteriskIcons as Icons

/**
 * Primary MD3 popup for the network quality test.
 *
 *  - Header has no Cancel button (rely on back-gesture / drag-down) but
 *    exposes an Info icon (opens the parameter explanation dialog) and a
 *    Settings icon (opens the parameter sheet) at the top right.
 *  - Outbound picker is an outlined Card.
 *  - Bottom-right Extended FAB carries Speed when idle and an embedded
 *    CircularProgressIndicator when running.
 *  - Report is a 2-column responsive grid; each card shows a label, a big
 *    numeric value, a unit, and a Good / Fair / Poor badge that uses the
 *    husi status palette (`commit 68251ffd`).
 */
@Composable
internal fun NetworkQualityTestSheet(
    controller: NetworkQualityTestController,
    show: Boolean,
    onDismiss: () -> Unit,
) {
    val services = LocalAppServices.current
    val scope = rememberCoroutineScope()
    val stateStore = LocalAppStateStore.current
    val appState by stateStore.state.collectAsState()
    val runtimeState by services.singBoxRuntime.state.collectAsState()
    val outboundLabels = selectableManagedOutbounds(appState).associate { choice ->
        choice.tag to choice.localizedLabel()
    }

    var showExplanation by remember { mutableStateOf(false) }
    val snackbarDoneMessage = stringResource(
        R.string.monitor_network_quality_snackbar_done,
    )

    SettingsModalBottomSheet(
        show = show,
        onDismissRequest = onDismiss,
        title = stringResource(R.string.monitor_network_quality_title),
        startAction = { /* no Cancel button — sheet closes via gesture */ },
        endAction = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = { showExplanation = true },
                    enabled = true,
                    colors = IconButtonDefaults.iconButtonColors(
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Info,
                        contentDescription = stringResource(
                            R.string.monitor_network_quality_info_action,
                        ),
                    )
                }
                IconButton(
                    onClick = { if (!controller.running) controller.showSettings = true },
                    enabled = !controller.running,
                    colors = IconButtonDefaults.iconButtonColors(
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Settings,
                        contentDescription = stringResource(
                            R.string.monitor_network_quality_settings_action,
                        ),
                    )
                }
            }
        },
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            SettingsSheetContent {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    OutboundCard(
                        proxyRunning = appState.proxyRunning,
                        selectedTag = controller.outboundTag,
                        outboundTags = runtimeState.proxies.nodeByName.keys.toList(),
                        outboundLabels = outboundLabels,
                        enabled = !controller.running,
                        onSelect = { controller.outboundTag = it },
                    )

                    ReportGrid(report = controller.report.value)
                }

                Spacer(modifier = Modifier.size(96.dp))
            }

            TestFloatingActionButton(
                running = controller.running,
                onStart = {
                    controller.start(
                        proxyRunning = appState.proxyRunning,
                        appState = appState,
                        runtimeRepository = services.singBoxRuntime,
                        snackbarMessage = snackbarDoneMessage,
                        tipNotifier = services.tipNotifier,
                        scope = scope,
                    )
                },
                onCancel = { controller.cancel() },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp),
            )
        }
    }

    if (controller.errorDialog != null) {
        val message = controller.errorDialog.orEmpty()
        AlertDialog(
            onDismissRequest = { controller.errorDialog = null },
            confirmButton = {
                TextButton(onClick = { controller.errorDialog = null }) {
                    Text(stringResource(R.string.common_ok))
                }
            },
            icon = { Icon(Icons.Rounded.Warning, contentDescription = null) },
            title = { Text(stringResource(R.string.monitor_network_quality_error_title)) },
            text = { Text(message) },
        )
    }

    if (showExplanation) {
        MetricExplanationDialog(onDismissRequest = { showExplanation = false })
    }
}

@Composable
private fun OutboundCard(
    proxyRunning: Boolean,
    selectedTag: String,
    outboundTags: List<String>,
    outboundLabels: Map<String, String>,
    enabled: Boolean,
    onSelect: (String) -> Unit,
) {
    val defaultLabel = stringResource(R.string.monitor_network_quality_outbound_default)
    val label = stringResource(R.string.monitor_network_quality_outbound_label)
    val unavailableLabel = stringResource(R.string.common_unavailable)
    val tags = remember(outboundTags) { listOf("") + outboundTags }
    var expanded by remember { mutableStateOf(false) }
    fun outboundLabel(tag: String): String =
        visibleManagedReference(tag, outboundLabels, unavailableLabel).ifEmpty { defaultLabel }
    val selectedDisplay = outboundLabel(selectedTag)
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.outlinedCardColors(),
        shape = MaterialTheme.shapes.large,
        border = BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TextButton(
                onClick = { if (enabled) expanded = true },
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "$label：$selectedDisplay",
                        modifier = Modifier.weight(1f),
                        textAlign = TextAlign.Start,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    AsteriskDropdownAnchor(
                        expanded = expanded,
                        onDismissRequest = { expanded = false },
                    ) {
                        tags.forEach { tag ->
                            AsteriskDropdownMenuItem(
                                text = outboundLabel(tag),
                                selected = tag == selectedTag,
                                onClick = {
                                    expanded = false
                                    onSelect(tag)
                                },
                                enabled = enabled,
                            )
                        }
                    }
                }
            }
            if (!proxyRunning) {
                Text(
                    text = stringResource(R.string.monitor_network_quality_standalone_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * 2-column responsive grid of metric cards. Each card stacks label /
 * big numeric value (headlineMedium + tabular nums) / unit (smaller, muted)
 * / rating chip (husi status palette).
 */
@Composable
private fun ReportGrid(report: NetworkQualityReport) {
    val items = buildList {
        add(
            ReportItem(
                labelRes = R.string.monitor_network_quality_idle_latency,
                number = if (report.idleLatencyMs > 0) report.idleLatencyMs.toString() else "—",
                unit = if (report.idleLatencyMs > 0) "ms" else "",
                rating = rateIdleLatency(report.idleLatencyMs),
            ),
        )
        add(
            ReportItem(
                labelRes = R.string.monitor_network_quality_download_capacity,
                number = if (report.downloadCapacityBitsPerSecond > 0L) {
                    formatSpeedNumber(report.downloadCapacityBitsPerSecond)
                } else "—",
                unit = if (report.downloadCapacityBitsPerSecond > 0L) {
                    formatSpeedUnit(report.downloadCapacityBitsPerSecond)
                } else "",
                rating = rateDownloadCapacity(report.downloadCapacityBitsPerSecond),
            ),
        )
        add(
            ReportItem(
                labelRes = R.string.monitor_network_quality_download_responsiveness,
                number = if (report.downloadCapacityBitsPerSecond > 0L) {
                    report.downloadRpm.toString()
                } else "—",
                unit = if (report.downloadCapacityBitsPerSecond > 0L) "RPM" else "",
                rating = rateRpm(
                    rpm = report.downloadRpm,
                    hasMeasurement = report.downloadCapacityBitsPerSecond > 0L,
                ),
            ),
        )
        add(
            ReportItem(
                labelRes = R.string.monitor_network_quality_upload_capacity,
                number = if (report.uploadCapacityBitsPerSecond > 0L) {
                    formatSpeedNumber(report.uploadCapacityBitsPerSecond)
                } else "—",
                unit = if (report.uploadCapacityBitsPerSecond > 0L) {
                    formatSpeedUnit(report.uploadCapacityBitsPerSecond)
                } else "",
                rating = rateUploadCapacity(report.uploadCapacityBitsPerSecond),
            ),
        )
        add(
            ReportItem(
                labelRes = R.string.monitor_network_quality_upload_responsiveness,
                number = if (report.uploadCapacityBitsPerSecond > 0L) {
                    report.uploadRpm.toString()
                } else "—",
                unit = if (report.uploadCapacityBitsPerSecond > 0L) "RPM" else "",
                rating = rateRpm(
                    rpm = report.uploadRpm,
                    hasMeasurement = report.uploadCapacityBitsPerSecond > 0L,
                ),
            ),
        )
        add(
            ReportItem(
                labelRes = R.string.monitor_network_quality_elapsed_time,
                number = if (report.elapsedMs > 0L) {
                    (report.elapsedMs / 1000L).toString()
                } else "—",
                unit = if (report.elapsedMs > 0L) "s" else "",
                rating = rateElapsedTime(report.elapsedMs),
            ),
        )
    }

    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val columns = if (maxWidth >= 480.dp) 2 else 2
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items.chunked(columns).forEach { rowItems ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    rowItems.forEach { item ->
                        Box(modifier = Modifier.weight(1f)) {
                            ReportMetricCard(item = item)
                        }
                    }
                    if (rowItems.size < columns) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

private data class ReportItem(
    val labelRes: Int,
    val number: String,
    val unit: String,
    val rating: MetricRating,
)

@Composable
private fun ReportMetricCard(item: ReportItem) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(),
        shape = MaterialTheme.shapes.large,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = stringResource(item.labelRes),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = item.number,
                    modifier = Modifier.weight(1f, fill = false),
                    style = MaterialTheme.typography.headlineMedium.copy(
                        fontWeight = FontWeight.SemiBold,
                        fontFeatureSettings = "tnum",
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (item.unit.isNotBlank()) {
                    Text(
                        text = item.unit,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                }
            }
            RatingBadge(rating = item.rating)
        }
    }
}

@Composable
private fun RatingBadge(rating: MetricRating) {
    if (rating == MetricRating.Unrated) return
    val colors = rating.colors() ?: return
    val label = stringResource(
        when (rating) {
            MetricRating.Good -> R.string.monitor_network_quality_rating_good
            MetricRating.Fair -> R.string.monitor_network_quality_rating_fair
            MetricRating.Poor -> R.string.monitor_network_quality_rating_poor
            MetricRating.Unrated -> return
        },
    )
    Surface(
        shape = RoundedCornerShape(50),
        color = colors.container,
        contentColor = colors.onContainer,
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TestFloatingActionButton(
    running: Boolean,
    onStart: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val container = if (running) {
        MaterialTheme.colorScheme.errorContainer
    } else {
        MaterialTheme.colorScheme.primaryContainer
    }
    val content = if (running) {
        MaterialTheme.colorScheme.onErrorContainer
    } else {
        MaterialTheme.colorScheme.onPrimaryContainer
    }
    ExtendedFloatingActionButton(
        onClick = { if (running) onCancel() else onStart() },
        expanded = true,
        modifier = modifier,
        containerColor = container,
        contentColor = content,
        icon = {
            if (running) {
                CircularProgressIndicator(
                    modifier = Modifier.size(ExtendedFabIconSize),
                    strokeWidth = 2.5.dp,
                    color = content,
                )
            } else {
                Icon(
                    imageVector = Icons.Rounded.Speed,
                    contentDescription = null,
                    modifier = Modifier.size(ExtendedFabIconSize),
                )
            }
        },
        text = {
            Text(
                text = stringResource(
                    if (running) R.string.monitor_network_quality_fab_stop
                    else R.string.monitor_network_quality_fab_start,
                ),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
        },
    )
}

private val ExtendedFabIconSize = 24.dp

private fun formatSpeedNumber(bitsPerSecond: Long): String {
    if (bitsPerSecond <= 0L) return "—"
    val mbps = bitsPerSecond / 1_000_000.0
    return when {
        mbps >= 1000.0 -> String.format(Locale.getDefault(), "%.2f", mbps / 1000.0)
        mbps >= 1.0 -> String.format(Locale.getDefault(), "%.2f", mbps)
        else -> String.format(Locale.getDefault(), "%.0f", bitsPerSecond / 1000.0)
    }
}

private fun formatSpeedUnit(bitsPerSecond: Long): String {
    if (bitsPerSecond <= 0L) return ""
    val mbps = bitsPerSecond / 1_000_000.0
    return when {
        mbps >= 1000.0 -> "Gbps"
        mbps >= 1.0 -> "Mbps"
        else -> "kbps"
    }
}

/**
 * Information dialog opened from the header's Info icon. Explains every
 * metric the report grid renders and what the Good / Fair / Poor bands
 * mean in plain language.
 */
@Composable
private fun MetricExplanationDialog(onDismissRequest: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = {
            TextButton(onClick = onDismissRequest) {
                Text(stringResource(R.string.common_ok))
            }
        },
        title = { Text(stringResource(R.string.monitor_network_quality_explanation_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ExplanationBlock(
                    titleRes = R.string.monitor_network_quality_idle_latency,
                    bodyRes = R.string.monitor_network_quality_idle_latency_explanation,
                )
                ExplanationBlock(
                    titleRes = R.string.monitor_network_quality_download_capacity,
                    bodyRes = R.string.monitor_network_quality_download_capacity_explanation,
                )
                ExplanationBlock(
                    titleRes = R.string.monitor_network_quality_download_responsiveness,
                    bodyRes = R.string.monitor_network_quality_download_responsiveness_explanation,
                )
                ExplanationBlock(
                    titleRes = R.string.monitor_network_quality_upload_capacity,
                    bodyRes = R.string.monitor_network_quality_upload_capacity_explanation,
                )
                ExplanationBlock(
                    titleRes = R.string.monitor_network_quality_upload_responsiveness,
                    bodyRes = R.string.monitor_network_quality_upload_responsiveness_explanation,
                )
                ExplanationBlock(
                    titleRes = R.string.monitor_network_quality_elapsed_time,
                    bodyRes = R.string.monitor_network_quality_elapsed_time_explanation,
                )
            }
        },
    )
}

@Composable
private fun ExplanationBlock(titleRes: Int, bodyRes: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = stringResource(titleRes),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = stringResource(bodyRes),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
