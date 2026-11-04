// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.plugin

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.LocalIsWideScreen
import app.LocalNavigator
import app.R
import engine.plugin.InstalledPlugin
import engine.plugin.PluginOutbounds
import engine.plugin.PluginProviderPreferences
import engine.plugin.PluginProviderProtocols
import engine.plugin.PluginRegistry
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import engine.plugin.ProtocolProvider
import ui.components.AsteriskScaffold
import ui.components.AsteriskTopAppBar
import ui.layout.pageContentPaddingWithCutout
import ui.layout.pageListPadding
import ui.icons.AsteriskIcons as Icons

/**
 * Plugins are separate APKs (Mieru, ShadowQUIC): the app ships no binary for
 * them, so this page only reports what is installed and links to the providers
 * whose signatures are trusted.
 */
@Composable
fun PluginPage(
    padding: PaddingValues,
) {
    val isWideScreen = LocalIsWideScreen.current
    val navigator = LocalNavigator.current
    val context = LocalContext.current.applicationContext
    val uriHandler = LocalUriHandler.current
    val lifecycleOwner = LocalLifecycleOwner.current
    // Package scans touch the PackageManager (tens of ms per package), so they
    // run on IO and re-run whenever the page is resumed: the user installs a
    // plugin in another app and comes back expecting to see it.
    var installed by remember(context) { mutableStateOf(emptyList<InstalledPlugin>()) }
    LaunchedEffect(context, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            installed = withContext(Dispatchers.IO) {
                runCatching { PluginRegistry.installedPlugins(context) }.getOrDefault(emptyList())
            }
        }
    }

    AsteriskScaffold(
        topBar = {
            AsteriskTopAppBar(
                title = { Text(stringResource(R.string.settings_plugins)) },
                navigationIcon = {
                    IconButton(onClick = { navigator.pop() }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.common_back),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        val contentPadding = pageListPadding(
            pageContentPaddingWithCutout(
                innerPadding = innerPadding,
                outerPadding = padding,
                isWideScreen = isWideScreen,
            ),
        )
        LazyColumn(
            contentPadding = contentPadding,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "plugins_installed_title") {
                Text(
                    text = stringResource(R.string.plugin_installed_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
            }
            if (installed.isEmpty()) {
                item(key = "plugins_installed_empty") {
                    Text(
                        text = stringResource(R.string.plugin_none_installed),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 8.dp),
                    )
                }
            }
            items(installed.size, key = { index -> "plugin_${installed[index].packageName}" }) { index ->
                val plugin = installed[index]
                PluginRow(
                    title = plugin.pluginId,
                    summary = stringResource(
                        R.string.plugin_row_summary,
                        plugin.versionName,
                        plugin.publisher,
                    ),
                )
            }
            item(key = "plugins_available_title") {
                Text(
                    text = stringResource(R.string.plugin_available_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
            }
            val missing = PluginOutbounds.all.filter { requirement ->
                installed.none { plugin -> plugin.pluginId == requirement.pluginId }
            }
            if (missing.isEmpty()) {
                item(key = "plugins_available_empty") {
                    Text(
                        text = stringResource(R.string.plugin_all_installed),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 8.dp),
                    )
                }
            }
            items(missing.size, key = { index -> "missing_${missing[index].pluginId}" }) { index ->
                val requirement = missing[index]
                PluginRow(
                    title = requirement.displayName,
                    summary = stringResource(R.string.plugin_download_hint),
                    onClick = {
                        runCatching { uriHandler.openUri(requirement.downloadUrl) }
                            .onFailure {
                                Toast.makeText(
                                    context,
                                    R.string.plugin_open_failed,
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                    },
                )
            }
            item(key = "plugins_provider_title") {
                Text(
                    text = stringResource(R.string.plugin_provider_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
            }
            item(key = "plugins_provider_list") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    PluginProviderProtocols.all.forEach { protocol ->
                        key(protocol) {
                            val provider = PluginProviderPreferences.flow(context, protocol)
                                .collectAsStateWithLifecycle()
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    text = when (protocol) {
                                        PluginProviderProtocols.Hysteria2 ->
                                            stringResource(R.string.plugin_provider_hysteria2)
                                        PluginProviderProtocols.Naive ->
                                            stringResource(R.string.plugin_provider_naive)
                                        else -> protocol
                                    },
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                                    ProtocolProvider.entries.forEachIndexed { index, entry ->
                                        SegmentedButton(
                                            selected = provider.value == entry,
                                            onClick = {
                                                PluginProviderPreferences.set(context, protocol, entry)
                                            },
                                            shape = SegmentedButtonDefaults.itemShape(
                                                index = index,
                                                count = ProtocolProvider.entries.size,
                                            ),
                                        ) {
                                            Text(
                                                text = when (entry) {
                                                    ProtocolProvider.Core ->
                                                        stringResource(R.string.plugin_provider_core)
                                                    ProtocolProvider.Plugin ->
                                                        stringResource(R.string.plugin_provider_plugin)
                                                },
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            item(key = "plugins_provider_note") {
                Text(
                    text = stringResource(R.string.plugin_provider_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
            }
            item(key = "plugins_note") {
                Text(
                    text = stringResource(R.string.plugin_trust_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun PluginRow(
    title: String,
    summary: String,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
