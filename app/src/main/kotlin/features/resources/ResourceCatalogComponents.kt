// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.resources

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.asterisk.zcc.abox.R
import ui.components.AsteriskActionButton
import ui.components.AsteriskCheckbox
import ui.components.AsteriskChipTone
import ui.components.AsteriskInfoChip
import ui.components.AsteriskModalBottomSheet
import ui.components.AsteriskPageCard
import ui.components.AsteriskSearchField
import ui.components.AsteriskTonalButton
import ui.icons.AsteriskIcons as Icons
import ui.theme.AsteriskMotion
import ui.theme.AsteriskShapeTokens
import java.util.Locale

@Composable
internal fun ResourceAddSourceSheet(
    show: Boolean,
    onDismissRequest: () -> Unit,
    onHidden: () -> Unit,
    onCatalogSelected: (ResourceCatalogSource) -> Unit,
    onCustomSelected: () -> Unit,
) {
    AsteriskModalBottomSheet(
        show = show,
        onDismissRequest = onDismissRequest,
        onHidden = onHidden,
        title = stringResource(R.string.settings_resource_files_add),
        startAction = {
            AsteriskActionButton(
                text = stringResource(R.string.common_cancel),
                icon = Icons.Rounded.Close,
                onClick = onDismissRequest,
            )
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .padding(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = stringResource(R.string.settings_resource_files_add_source_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, end = 4.dp, bottom = 4.dp),
            )
            ResourceAddSourceCard(
                title = "sing-geosite",
                summary = stringResource(R.string.settings_resource_files_add_geosite_summary),
                icon = Icons.Rounded.Language,
                onClick = { onCatalogSelected(ResourceCatalogSource.SingGeosite) },
            )
            ResourceAddSourceCard(
                title = "sing-geoip",
                summary = stringResource(R.string.settings_resource_files_add_geoip_summary),
                icon = Icons.Rounded.Public,
                onClick = { onCatalogSelected(ResourceCatalogSource.SingGeoip) },
            )
            ResourceAddSourceCard(
                title = stringResource(R.string.settings_resource_files_source_custom),
                summary = stringResource(R.string.settings_resource_files_add_custom_summary),
                icon = Icons.Rounded.Edit,
                onClick = onCustomSelected,
            )
        }
    }
}

@Composable
private fun ResourceAddSourceCard(
    title: String,
    summary: String,
    icon: ImageVector,
    onClick: () -> Unit,
) {
    AsteriskPageCard(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(18.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                shape = AsteriskShapeTokens.Pill,
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ) {
                Box(modifier = Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                    Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp))
                }
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                imageVector = Icons.Rounded.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun ResourceCatalogSheet(
    show: Boolean,
    source: ResourceCatalogSource,
    loadState: ResourceCatalogLoadState,
    existingNames: Set<String>,
    onDismissRequest: () -> Unit,
    onHidden: () -> Unit,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onSave: (List<ResourceCatalogEntry>) -> Unit,
) {
    var query by remember(source) { mutableStateOf("") }
    var selectedNames by remember(source) { mutableStateOf<Set<String>>(emptySet()) }
    val normalizedExistingNames = remember(existingNames) {
        existingNames.mapTo(mutableSetOf()) { name -> name.lowercase(Locale.ROOT) }
    }
    val entries = (loadState as? ResourceCatalogLoadState.Loaded)?.entries.orEmpty()
    val selectedEntries = entries.filter { entry -> entry.name in selectedNames }

    LaunchedEffect(show, source) {
        if (show) {
            query = ""
            selectedNames = emptySet()
        }
    }
    LaunchedEffect(entries) {
        val availableNames = entries.mapTo(mutableSetOf()) { entry -> entry.name }
        selectedNames = selectedNames.intersect(availableNames)
    }
    LaunchedEffect(normalizedExistingNames) {
        selectedNames = selectedNames.filterNotTo(mutableSetOf()) { name ->
            name.lowercase(Locale.ROOT) in normalizedExistingNames
        }
    }

    AsteriskModalBottomSheet(
        show = show,
        onDismissRequest = onDismissRequest,
        onHidden = onHidden,
        title = stringResource(
            R.string.settings_resource_files_catalog_title,
            source.displayName(),
        ),
        startAction = {
            AsteriskActionButton(
                text = stringResource(R.string.common_back),
                icon = Icons.AutoMirrored.Rounded.ArrowBack,
                onClick = onBack,
            )
        },
        endAction = {
            AsteriskActionButton(
                text = stringResource(
                    R.string.settings_resource_files_catalog_add_selected,
                    selectedEntries.size,
                ),
                icon = Icons.Rounded.Download,
                onClick = { onSave(selectedEntries) },
                enabled = selectedEntries.isNotEmpty(),
            )
        },
    ) {
        val stateAnimationSpec = AsteriskMotion.effects<Float>()
        AnimatedContent(
            targetState = loadState,
            transitionSpec = AsteriskMotion.fadeThrough(stateAnimationSpec),
            label = "resource-catalog-load-state",
        ) { state ->
            when (state) {
                ResourceCatalogLoadState.Loading -> ResourceCatalogLoading()
                is ResourceCatalogLoadState.Failed -> ResourceCatalogFailure(
                    error = state.error,
                    onRetry = onRetry,
                )
                is ResourceCatalogLoadState.Loaded -> ResourceCatalogLoaded(
                    entries = state.entries,
                    query = query,
                    onQueryChange = { query = it },
                    selectedNames = selectedNames,
                    normalizedExistingNames = normalizedExistingNames,
                    source = source,
                    onToggle = { entry ->
                        selectedNames = if (entry.name in selectedNames) {
                            selectedNames - entry.name
                        } else {
                            selectedNames + entry.name
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun ResourceCatalogLoading() {
    ResourceCatalogCenteredState {
        CircularProgressIndicator()
        Text(
            text = stringResource(R.string.settings_resource_files_catalog_loading),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ResourceCatalogFailure(
    error: Throwable,
    onRetry: () -> Unit,
) {
    ResourceCatalogCenteredState {
        Icon(
            imageVector = Icons.Rounded.ErrorOutline,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(40.dp),
        )
        Text(
            text = stringResource(R.string.settings_resource_files_catalog_load_failed),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        error.message?.takeIf(String::isNotBlank)?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        AsteriskTonalButton(
            text = stringResource(R.string.common_retry),
            icon = Icons.Rounded.Refresh,
            onClick = onRetry,
        )
    }
}

@Composable
private fun ResourceCatalogCenteredState(
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 340.dp)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
        content = content,
    )
}

@Composable
private fun ResourceCatalogLoaded(
    entries: List<ResourceCatalogEntry>,
    query: String,
    onQueryChange: (String) -> Unit,
    selectedNames: Set<String>,
    normalizedExistingNames: Set<String>,
    source: ResourceCatalogSource,
    onToggle: (ResourceCatalogEntry) -> Unit,
) {
    val filteredEntries = remember(entries, query) {
        filterResourceCatalogEntries(entries, query)
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 340.dp, max = 680.dp)
            .padding(horizontal = 16.dp)
            .padding(top = 8.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        AsteriskSearchField(
            query = query,
            onQueryChange = onQueryChange,
            placeholder = stringResource(R.string.settings_resource_files_catalog_search),
        )
        Text(
            text = stringResource(
                R.string.settings_resource_files_catalog_status,
                entries.size,
            ),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        val resultsAnimationSpec = AsteriskMotion.effects<Float>()
        AnimatedContent(
            targetState = filteredEntries.isEmpty(),
            modifier = Modifier.fillMaxWidth().weight(1f),
            transitionSpec = AsteriskMotion.fadeThrough(resultsAnimationSpec),
            label = "resource-catalog-results",
        ) { empty ->
            if (empty) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = stringResource(
                            if (entries.isEmpty()) {
                                R.string.settings_resource_files_catalog_empty
                            } else {
                                R.string.settings_resource_files_catalog_no_results
                            },
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(filteredEntries, key = ResourceCatalogEntry::name) { entry ->
                        val existing = entry.name.lowercase(Locale.ROOT) in normalizedExistingNames
                        val selected = entry.name in selectedNames
                        ResourceCatalogEntryCard(
                            entry = entry,
                            selected = selected,
                            existing = existing,
                            source = source,
                            onClick = { onToggle(entry) },
                            modifier = Modifier.animateItem(),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ResourceCatalogEntryCard(
    entry: ResourceCatalogEntry,
    selected: Boolean,
    existing: Boolean,
    source: ResourceCatalogSource,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AsteriskPageCard(
        modifier = modifier.fillMaxWidth(),
        selected = selected,
        onClick = if (existing) null else onClick,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (source == ResourceCatalogSource.SingGeosite) {
                    Icons.Rounded.Language
                } else {
                    Icons.Rounded.Public
                },
                contentDescription = null,
                tint = if (existing) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.primary
                },
                modifier = Modifier.size(22.dp),
            )
            Text(
                text = entry.name,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                color = if (existing) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (existing) {
                Box(
                    modifier = Modifier.minimumInteractiveComponentSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    AsteriskInfoChip(
                        text = stringResource(R.string.settings_resource_files_catalog_existing),
                        tone = AsteriskChipTone.Neutral,
                    )
                }
            } else {
                AsteriskCheckbox(
                    checked = selected,
                    onCheckedChange = null,
                )
            }
        }
    }
}

@Composable
private fun ResourceCatalogSource.displayName(): String = when (this) {
    ResourceCatalogSource.SingGeosite -> "sing-geosite"
    ResourceCatalogSource.SingGeoip -> "sing-geoip"
}
