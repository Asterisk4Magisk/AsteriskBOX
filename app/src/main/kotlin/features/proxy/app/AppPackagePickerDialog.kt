// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.proxy.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.LocalAppServices
import app.LocalIsWideScreen
import app.R
import features.proxy.app.model.AppPackageEntry
import features.proxy.app.model.loadProxyAppListPackages
import features.proxy.app.model.name
import kotlinx.coroutines.CancellationException
import system.ANDROID_APP_ICON_SIZE_DP
import ui.components.AsteriskCheckbox
import ui.components.EditorPageScaffold
import ui.components.StringListHeaderActionSize
import ui.icons.AsteriskIcons as Icons

/**
 * Header action that lets a [ui.components.StringListEditor] holding Android package names be
 * maintained by checking installed apps. Values that are not installed packages stay untouched.
 */
@Composable
internal fun AppPackagePickerAction(
    values: List<String>,
    onValuesChange: (List<String>) -> Unit,
) {
    var showPicker by rememberSaveable { mutableStateOf(false) }
    IconButton(
        onClick = { showPicker = true },
        modifier = Modifier.size(StringListHeaderActionSize),
    ) {
        Icon(Icons.Rounded.Apps, stringResource(R.string.proxy_app_list_pick_installed_apps))
    }
    if (showPicker) {
        AppPackagePickerDialog(
            initialValues = values,
            onDismissRequest = { showPicker = false },
            onConfirm = { nextValues ->
                showPicker = false
                onValuesChange(nextValues)
            },
        )
    }
}

@Composable
internal fun AppPackagePickerDialog(
    initialValues: List<String>,
    onDismissRequest: () -> Unit,
    onConfirm: (List<String>) -> Unit,
) {
    val services = LocalAppServices.current
    val packageCatalog = services.packageCatalog
    val tipNotifier = services.tipNotifier
    val isWideScreen = LocalIsWideScreen.current
    val title = stringResource(R.string.proxy_app_list_picker_title)
    val loadFailedMessage = stringResource(R.string.proxy_app_list_load_failed)
    var selectedPackages by rememberSaveable { mutableStateOf(initialValues.distinct()) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var showSystemApps by rememberSaveable { mutableStateOf(false) }
    var apps by remember { mutableStateOf<List<AppPackageEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    val iconSizePx = with(LocalDensity.current) { ANDROID_APP_ICON_SIZE_DP.dp.roundToPx() }

    LaunchedEffect(showSystemApps) {
        loading = true
        val selectedOnLoad = selectedPackages.toSet()
        try {
            apps = packageCatalog.loadProxyAppListPackages(
                showSystemApps = showSystemApps,
                currentUserOnly = true,
                excludedPackageName = "",
            )
                .distinctBy(AppPackageEntry::packageName)
                .sortedBy { app -> app.packageName !in selectedOnLoad }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            tipNotifier.showError(error, loadFailedMessage)
            apps = emptyList()
        } finally {
            loading = false
        }
    }

    val visibleApps = remember(apps, searchQuery) {
        val query = searchQuery.trim()
        if (query.isEmpty()) {
            apps
        } else {
            apps.filter { app ->
                app.name.contains(query, ignoreCase = true) ||
                    app.packageName.contains(query, ignoreCase = true)
            }
        }
    }

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        EditorPageScaffold(
            outerPadding = PaddingValues(),
            isWideScreen = isWideScreen,
            title = { Text(title) },
            saving = false,
            saveEnabled = true,
            onBack = onDismissRequest,
            onSave = { onConfirm(selectedPackages) },
            searchQuery = searchQuery,
            onSearchQueryChange = { searchQuery = it },
            searchPlaceholder = stringResource(R.string.proxy_app_list_search_label),
            actions = {
                AppPackagePickerMoreMenu(
                    showSystemApps = showSystemApps,
                    onToggleSystemApps = { showSystemApps = !showSystemApps },
                )
            },
        ) { listPadding ->
            Box(Modifier.fillMaxSize()) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = listPadding,
                ) {
                    if (visibleApps.isEmpty() && !loading) {
                        item(key = "app_empty", contentType = "empty") {
                            ProxyAppListEmptyState()
                        }
                    } else {
                        items(
                            items = visibleApps,
                            key = { app -> app.packageName },
                            contentType = { "app" },
                        ) { app ->
                            ProxyAppListItemCard(
                                app = app,
                                checked = app.packageName in selectedPackages,
                                enabled = true,
                                sharedUid = false,
                                iconSizePx = iconSizePx,
                                onCheckedChange = { checked ->
                                    selectedPackages = togglePickedPackage(
                                        selectedPackages,
                                        app.packageName,
                                        checked,
                                    )
                                },
                            )
                        }
                    }
                }
                if (loading && apps.isEmpty()) {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(listPadding),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(28.dp),
                            strokeWidth = 2.5.dp,
                        )
                        Spacer(Modifier.height(14.dp))
                        Text(
                            text = stringResource(R.string.proxy_app_list_loading),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AppPackagePickerMoreMenu(
    showSystemApps: Boolean,
    onToggleSystemApps: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.Rounded.MoreVert, stringResource(R.string.proxy_app_list_more_actions))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.proxy_app_list_show_system_apps)) },
                onClick = onToggleSystemApps,
                leadingIcon = { Icon(Icons.Rounded.Apps, contentDescription = null) },
                trailingIcon = { AsteriskCheckbox(checked = showSystemApps, onCheckedChange = null) },
            )
        }
    }
}

/**
 * Checking appends the package and unchecking removes it; every other value keeps its position,
 * so manually entered values (such as uninstalled packages) survive a picker round trip.
 */
internal fun togglePickedPackage(
    selected: List<String>,
    packageName: String,
    checked: Boolean,
): List<String> = when {
    checked && packageName !in selected -> selected + packageName
    !checked -> selected - packageName
    else -> selected
}
