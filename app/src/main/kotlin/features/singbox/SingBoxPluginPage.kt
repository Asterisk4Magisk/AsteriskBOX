// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.singbox

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import app.LocalAppStateStore
import app.LocalIsWideScreen
import app.LocalNavigator
import app.LocalUpdateAppState
import app.R
import app.collectAppState
import engine.singbox.plugin.InstalledPlugin
import engine.singbox.plugin.PluginOutbounds
import engine.singbox.plugin.PluginRegistry
import engine.singbox.plugin.PluginRequirement
import features.settings.SettingsActionRow
import features.settings.SettingsSectionCard
import features.settings.SmallTitle
import features.settings.SwitchPreference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ui.components.AsteriskScaffold
import ui.components.AsteriskTopAppBar
import ui.layout.pageContentPaddingWithCutout
import ui.layout.pageListPadding
import ui.icons.AsteriskIcons as Icons

/**
 * Plugins are separate APKs: the app ships no binary for them, so this page
 * reports which are installed, links to the missing ones, and lets the
 * protocols the core also implements switch to their plugin.
 */
@Composable
internal fun SingBoxPluginPage(padding: PaddingValues) {
    val appState by LocalAppStateStore.current.collectAppState()
    val updateAppState = LocalUpdateAppState.current
    val navigator = LocalNavigator.current
    val isWideScreen = LocalIsWideScreen.current
    val context = LocalContext.current.applicationContext
    val uriHandler = LocalUriHandler.current
    val lifecycleOwner = LocalLifecycleOwner.current
    // Null until the first scan. Rescanned on every resume: a plugin is
    // installed in another app and the user comes back expecting to see it.
    var installed by remember { mutableStateOf<Map<String, InstalledPlugin>?>(null) }
    LaunchedEffect(context, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            installed = withContext(Dispatchers.IO) { PluginRegistry.installedPlugins(context) }
        }
    }

    AsteriskScaffold(
        topBar = {
            AsteriskTopAppBar(
                title = { Text(stringResource(R.string.settings_plugins)) },
                navigationIcon = {
                    IconButton(onClick = navigator::pop) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.common_back))
                    }
                },
            )
        },
    ) { innerPadding ->
        LazyColumn(
            contentPadding = pageListPadding(pageContentPaddingWithCutout(innerPadding, padding, isWideScreen)),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "plugin_packages") {
                SmallTitle(text = stringResource(R.string.plugin_packages_title))
                SettingsSectionCard(bottomPadding = 0.dp) {
                    PluginOutbounds.all.forEach { requirement ->
                        PluginPackageRow(
                            requirement = requirement,
                            scanned = installed != null,
                            plugin = installed?.get(requirement.pluginId),
                            onOpenDownload = { uriHandler.openUri(requirement.downloadUrl) },
                            onOpenDetails = { plugin -> context.openApplicationDetails(plugin.packageName) },
                        )
                    }
                }
                Text(
                    text = stringResource(R.string.plugin_trust_note),
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item(key = "plugin_implementations") {
                SmallTitle(text = stringResource(R.string.plugin_implementations_title))
                SettingsSectionCard {
                    PluginOutbounds.switchable.forEach { requirement ->
                        val enabled = requirement.outboundType in appState.pluginProtocols
                        SwitchPreference(
                            title = stringResource(R.string.plugin_use_plugin, requirement.displayName),
                            summary = stringResource(requirement.implementationSummary()),
                            icon = Icons.Rounded.Tune,
                            checked = enabled,
                            onCheckedChange = { checked ->
                                updateAppState { state ->
                                    state.copy(
                                        pluginProtocols = if (checked) {
                                            (state.pluginProtocols + requirement.outboundType).distinct()
                                        } else {
                                            state.pluginProtocols - requirement.outboundType
                                        },
                                    )
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PluginPackageRow(
    requirement: PluginRequirement,
    scanned: Boolean,
    plugin: InstalledPlugin?,
    onOpenDownload: () -> Unit,
    onOpenDetails: (InstalledPlugin) -> Unit,
) {
    SettingsActionRow(
        title = requirement.displayName,
        icon = Icons.Rounded.Extension,
        summary = when {
            plugin != null -> stringResource(R.string.plugin_installed_summary, plugin.versionName, plugin.publisher)
            scanned -> stringResource(R.string.plugin_missing_summary)
            else -> ""
        },
        value = when {
            plugin != null -> stringResource(R.string.plugin_installed)
            scanned -> stringResource(R.string.plugin_not_installed)
            else -> ""
        },
        onClick = { if (plugin != null) onOpenDetails(plugin) else onOpenDownload() },
        enabled = scanned,
    )
}

private fun PluginRequirement.implementationSummary(): Int = when (this) {
    PluginOutbounds.naive -> R.string.plugin_use_naive_summary
    else -> R.string.plugin_use_hysteria2_summary
}

private fun android.content.Context.openApplicationDetails(packageName: String) {
    startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}
