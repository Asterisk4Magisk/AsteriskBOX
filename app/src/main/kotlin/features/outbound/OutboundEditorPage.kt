// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.outbound

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.LocalAppServices
import app.LocalAppStateStore
import app.LocalIsWideScreen
import app.LocalNavigator
import app.LocalUpdateAppState
import app.OutboundState
import app.collectAppState
import app.managedOutboundTag
import app.selectableDetourOutbounds
import engine.singbox.config.SingBoxJson
import engine.singbox.config.validateSingBoxRuntimeConfiguration
import features.logs.FailureLogContext
import features.logs.reportFailure
import features.settings.SettingsDropdownRow
import features.settings.SettingsSectionCard
import features.settings.SettingsSectionTitle
import features.settings.SettingsSwitchRow
import features.settings.sheets.dnsServerTypeLabel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import org.asterisk.zcc.abox.R
import ui.clipboard.setPlainText
import ui.components.AsteriskFilterChip
import ui.components.localizedLabel
import ui.components.singBoxOptionLabel
import ui.icons.AsteriskIcons as Icons
import ui.layout.pageContentPaddingWithCutout
import ui.layout.pageListPadding
import ui.theme.AsteriskMotion
import ui.theme.AsteriskShapeTokens

@Composable
internal fun OutboundEditorPage(
    padding: PaddingValues,
    outboundId: Int,
    initialGroupId: Int,
    initialType: String,
) {
    val appState by LocalAppStateStore.current.collectAppState()
    val updateAppState = LocalUpdateAppState.current
    val navigator = LocalNavigator.current
    val services = LocalAppServices.current
    val resources = LocalResources.current
    val clipboard = LocalClipboard.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val isWideScreen = LocalIsWideScreen.current
    val editing = appState.outbounds.firstOrNull { it.id == outboundId }
    val type = editing?.type ?: initialType
    val schema = remember(type) { OutboundEditorRegistry.schema(type) }
    var document by remember(outboundId, editing?.json, type) {
        mutableStateOf(
            editing?.json
                ?.let { json -> runCatching { SingBoxJson.parseToJsonElement(json) as JsonObject }.getOrNull() }
                ?.let(::OutboundEditorDocument)
                ?: OutboundEditorDocument.create(
                    type,
                    managedOutboundTag(appState.nextOutboundId),
                ),
        )
    }
    var remarks by remember(outboundId, editing?.remarks) {
        mutableStateOf(editing?.remarks.orEmpty())
    }
    var selectedGroupId by remember(outboundId) {
        mutableIntStateOf(editing?.groupId ?: initialGroupId)
    }
    var attemptedSave by remember { mutableStateOf(false) }
    var saving by remember(outboundId) { mutableStateOf(false) }
    val visibleGroups = appState.outboundGroups.filter { it.enabled || it.id == selectedGroupId }
    if (visibleGroups.none { it.id == selectedGroupId }) {
        selectedGroupId = visibleGroups.firstOrNull()?.id ?: appState.outboundGroups.firstOrNull()?.id ?: 0
    }
    val referenceOptions = mapOf(
        "detour" to selectableDetourOutbounds(
            state = appState,
            excludedTag = editing?.tag.orEmpty(),
            excludedManagedGroupId = selectedGroupId,
            includeGlobalSelector = false,
        ).map { choice ->
            OutboundReferenceOption(choice.tag, choice.localizedLabel())
        },
        "domain_resolver" to appState.dnsServers
            .distinctBy { server -> server.tag }
            .map { server ->
                OutboundReferenceOption(
                    value = server.tag,
                    label = server.remarks.ifBlank { dnsServerTypeLabel(server.type) },
                )
            },
    )
    val referenceValues = referenceOptions.mapValues { (_, options) ->
        options.map(OutboundReferenceOption::value)
    }
    val errors = if (attemptedSave) {
        (document.validate() + document.validateReferences(referenceValues))
            .associateBy { it.path }
    } else {
        emptyMap()
    }
    val invalidMessage = stringResource(R.string.outbound_editor_invalid)
    val copiedMessage = stringResource(R.string.outbound_editor_copied)
    val saveEffectsMotion = AsteriskMotion.fastEffects<Float>()

    fun save() {
        if (saving) return
        attemptedSave = true
        val currentErrors = document.validate() + document.validateReferences(referenceValues)
        when {
            selectedGroupId == 0 -> scope.launch {
                services.tipNotifier.show(resources.getString(R.string.outbound_group_required))
            }
            currentErrors.isNotEmpty() -> scope.launch {
                services.tipNotifier.show(invalidMessage)
            }
            else -> {
                val imported = document.toImported(remarks)
                saving = true
                scope.launch {
                    try {
                        val candidateState = if (editing == null) {
                            appState.withImportedOutbounds(
                            groupId = selectedGroupId,
                            imported = listOf(imported),
                            replaceGroup = false,
                        )
                        } else {
                            appState.copy(
                            outbounds = appState.outbounds.map { outbound ->
                                if (outbound.id == editing.id) {
                                    OutboundState(
                                        id = editing.id,
                                        groupId = selectedGroupId,
                                        remarks = imported.remarks,
                                        type = imported.type,
                                        json = SingBoxJson.encodeToString(
                                            kotlinx.serialization.json.JsonElement.serializer(),
                                            JsonObject(
                                                (SingBoxJson.parseToJsonElement(imported.json) as JsonObject) +
                                                    ("tag" to kotlinx.serialization.json.JsonPrimitive(editing.tag)),
                                            ),
                                        ),
                                    )
                                } else {
                                    outbound
                                }
                            },
                        )
                        }
                        withContext(Dispatchers.IO) {
                            validateSingBoxRuntimeConfiguration(context, candidateState)
                        }
                        var committed = false
                        updateAppState { state ->
                            if (state !== appState) {
                                state
                            } else {
                                committed = true
                                candidateState
                            }
                        }
                        if (committed) {
                            navigator.pop()
                        } else {
                            reportFailure(
                                FailureLogContext(
                                    operation = "outbound_save",
                                    stage = "commit",
                                ),
                            )
                            services.tipNotifier.show(invalidMessage)
                        }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Throwable) {
                        services.tipNotifier.showError(
                            error,
                            invalidMessage,
                            FailureLogContext(operation = "outbound_save"),
                        )
                    } finally {
                        saving = false
                    }
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            stringResource(
                                if (editing == null) R.string.outbound_manual_add
                                else R.string.outbound_manual_edit,
                            ),
                        )
                        Text(
                            schema.title,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = navigator::pop, enabled = !saving) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.common_back))
                    }
                },
                actions = {
                    if (editing != null) {
                        IconButton(
                            onClick = {
                                scope.launch {
                                    clipboard.setPlainText(
                                        JsonObject(document.value - "tag").toString(),
                                    )
                                    services.tipNotifier.show(copiedMessage)
                                }
                            },
                            enabled = !saving,
                        ) {
                            Icon(Icons.Rounded.ContentCopy, stringResource(R.string.common_copy))
                        }
                    }
                    IconButton(onClick = ::save, enabled = !saving) {
                        AnimatedContent(
                            targetState = saving,
                            transitionSpec = {
                                fadeIn(saveEffectsMotion).togetherWith(fadeOut(saveEffectsMotion))
                            },
                            label = "outbound-save-progress",
                        ) { inProgress ->
                            if (inProgress) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp,
                                )
                            } else {
                                Icon(Icons.Rounded.Save, stringResource(R.string.common_save))
                            }
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        val contentPadding = pageContentPaddingWithCutout(
            innerPadding = innerPadding,
            outerPadding = padding,
            isWideScreen = isWideScreen,
        )
        LazyColumn(
            contentPadding = pageListPadding(contentPadding),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item(key = "identity") {
                EditorSectionCard(
                    title = stringResource(R.string.outbound_editor_section_identity),
                    description = stringResource(R.string.outbound_editor_section_identity_summary),
                ) {
                    OutlinedTextField(
                        value = remarks,
                        onValueChange = { remarks = it },
                        label = { Text(stringResource(R.string.outbound_remarks)) },
                        singleLine = true,
                        shape = AsteriskShapeTokens.InnerContainer,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                    )
                    SettingsDropdownRow(
                        title = stringResource(R.string.outbound_group),
                        icon = Icons.Rounded.AccountTree,
                        items = visibleGroups.map { it.displayName() },
                        selectedIndex = visibleGroups
                            .indexOfFirst { it.id == selectedGroupId }
                            .coerceAtLeast(0),
                        onSelectedIndexChange = { index ->
                            selectedGroupId = visibleGroups[index].id
                        },
                    )
                }
            }

            outboundEditorContent(
                state = OutboundEditorContentState(
                    schema = schema,
                    document = document,
                    errors = errors,
                    referenceOptions = referenceOptions,
                    onDocumentChange = { document = it },
                ),
            )

            item(key = "bottom_space") { Spacer(Modifier.width(1.dp)) }
        }
    }
}

@Composable
internal fun EditorSectionCard(
    title: String,
    description: String,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(animationSpec = AsteriskMotion.contentSpatial()),
    ) {
        SettingsSectionTitle(title)
        SettingsSectionCard(bottomPadding = 0.dp) {
            if (description.isNotBlank()) {
                Text(
                    text = description,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
            }
            content()
        }
    }
}

@Composable
internal fun OutboundEditorField(
    field: OutboundFieldSpec,
    document: OutboundEditorDocument,
    error: OutboundEditorValidationError?,
    referenceOptions: List<OutboundReferenceOption>,
    onDocumentChange: (OutboundEditorDocument) -> Unit,
) {
    val errorText = error?.localizedMessage(field)
    when (field.kind) {
        OutboundFieldKind.BOOLEAN -> {
            val lockedEnabled = field.path == "tls.enabled" &&
                document.type in MandatoryTlsOutboundTypes
            SettingsSwitchRow(
                title = field.localizedLabel(),
                icon = field.editorIcon(),
                checked = document.boolean(field.path),
                enabled = !lockedEnabled,
                summary = if (lockedEnabled) {
                    stringResource(R.string.outbound_editor_tls_required)
                } else {
                    errorText.orEmpty()
                },
                onCheckedChange = { enabled ->
                    onDocumentChange(document.setBoolean(field.path, enabled))
                },
            )
        }
        OutboundFieldKind.SELECT -> OutboundSelectField(field, document, errorText, onDocumentChange)
        OutboundFieldKind.MULTI_SELECT ->
            OutboundMultiSelectField(field, document, errorText, onDocumentChange)
        OutboundFieldKind.REFERENCE -> OutboundReferenceField(
            field = field,
            document = document,
            options = referenceOptions,
            error = errorText,
            onDocumentChange = onDocumentChange,
        )
        OutboundFieldKind.KEY_VALUE -> OutboundKeyValueField(field, document, onDocumentChange)
        else -> OutlinedTextField(
            value = document.text(field.path),
            onValueChange = { value -> onDocumentChange(document.setText(field.path, value)) },
            label = { Text(field.localizedLabel()) },
            supportingText = when {
                errorText != null -> ({ Text(errorText) })
                field.kind == OutboundFieldKind.TEXT_LIST ->
                    ({ Text(stringResource(R.string.outbound_editor_list_hint)) })
                else -> null
            },
            isError = error != null,
            singleLine = field.kind !in setOf(OutboundFieldKind.MULTILINE, OutboundFieldKind.TEXT_LIST),
            minLines = if (field.kind == OutboundFieldKind.MULTILINE) 3 else 1,
            visualTransformation = if (field.kind == OutboundFieldKind.SECRET) {
                PasswordVisualTransformation()
            } else {
                androidx.compose.ui.text.input.VisualTransformation.None
            },
            keyboardOptions = KeyboardOptions(
                keyboardType = if (field.kind == OutboundFieldKind.INTEGER) {
                    KeyboardType.Number
                } else {
                    KeyboardType.Text
                },
            ),
            shape = AsteriskShapeTokens.InnerContainer,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun OutboundKeyValueField(
    field: OutboundFieldSpec,
    document: OutboundEditorDocument,
    onDocumentChange: (OutboundEditorDocument) -> Unit,
) {
    var entries by remember(field.path) { mutableStateOf(document.entries(field.path)) }

    fun update(updated: List<Pair<String, String>>) {
        entries = updated
        onDocumentChange(document.setEntries(field.path, updated))
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .animateContentSize(animationSpec = AsteriskMotion.contentSpatial()),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        shape = AsteriskShapeTokens.InnerContainer,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(field.localizedLabel(), style = MaterialTheme.typography.labelLarge)
            if (entries.isEmpty()) {
                Text(
                    stringResource(R.string.outbound_editor_key_value_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            entries.forEachIndexed { index, (key, value) ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = key,
                        onValueChange = { newKey ->
                            update(entries.toMutableList().also { it[index] = newKey to value })
                        },
                        label = { Text(stringResource(R.string.outbound_editor_key)) },
                        singleLine = true,
                        shape = AsteriskShapeTokens.InnerContainer,
                        modifier = Modifier.weight(0.42f),
                    )
                    OutlinedTextField(
                        value = value,
                        onValueChange = { newValue ->
                            update(entries.toMutableList().also { it[index] = key to newValue })
                        },
                        label = { Text(stringResource(R.string.outbound_editor_value)) },
                        singleLine = true,
                        shape = AsteriskShapeTokens.InnerContainer,
                        modifier = Modifier.weight(0.58f),
                    )
                    IconButton(
                        onClick = {
                            update(entries.toMutableList().also { it.removeAt(index) })
                        },
                    ) {
                        Icon(Icons.Rounded.Delete, stringResource(R.string.common_delete))
                    }
                }
            }
            TextButton(onClick = { entries = entries + ("" to "") }) {
                Icon(Icons.Rounded.Add, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.common_add))
            }
        }
    }
}

@Composable
private fun OutboundSelectField(
    field: OutboundFieldSpec,
    document: OutboundEditorDocument,
    error: String?,
    onDocumentChange: (OutboundEditorDocument) -> Unit,
) {
    val current = document.text(field.path)
    val values = if (current in field.options) field.options else listOf(current) + field.options
    val defaultLabel = stringResource(R.string.common_default)
    SettingsDropdownRow(
        title = field.localizedLabel(),
        icon = field.editorIcon(),
        items = values.map { option ->
            option.takeIf(String::isNotBlank)?.let { field.localizedOptionLabel(it) } ?: defaultLabel
        },
        selectedIndex = values.indexOf(current).coerceAtLeast(0),
        summary = error.orEmpty(),
        onSelectedIndexChange = { index ->
            onDocumentChange(document.setText(field.path, values[index]))
        },
    )
}

@Composable
private fun OutboundMultiSelectField(
    field: OutboundFieldSpec,
    document: OutboundEditorDocument,
    error: String?,
    onDocumentChange: (OutboundEditorDocument) -> Unit,
) {
    val selected = document.text(field.path)
        .lineSequence()
        .map(String::trim)
        .filter(String::isNotBlank)
        .toCollection(linkedSetOf())
    val options = field.options + selected.filterNot(field.options::contains)
    val unavailable = stringResource(R.string.common_unavailable)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .animateContentSize(animationSpec = AsteriskMotion.contentSpatial()),
    ) {
        Text(
            text = field.localizedLabel(),
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            options.forEach { option ->
                val isSelected = option in selected
                AsteriskFilterChip(
                    selected = isSelected,
                    onClick = {
                        val updated = if (isSelected) selected - option else selected + option
                        val ordered = field.options.filter(updated::contains) +
                            updated.filterNot(field.options::contains)
                        onDocumentChange(document.setText(field.path, ordered.joinToString("\n")))
                    },
                    label = if (option in field.options) {
                        field.localizedOptionLabel(option)
                    } else {
                        singBoxOptionLabel(unavailable, option)
                    },
                )
            }
        }
        error?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
            )
        }
    }
}

@Composable
private fun OutboundReferenceField(
    field: OutboundFieldSpec,
    document: OutboundEditorDocument,
    options: List<OutboundReferenceOption>,
    error: String?,
    onDocumentChange: (OutboundEditorDocument) -> Unit,
) {
    val current = document.text(field.path).trim()
    val available = options
        .filter { option -> option.value.isNotBlank() }
        .distinctBy(OutboundReferenceOption::value)
    val values = listOf("") + available.map(OutboundReferenceOption::value) +
        listOfNotNull(current.takeIf { value ->
            value.isNotBlank() && available.none { option -> option.value == value }
        })
    val notSpecified = stringResource(R.string.common_not_specified)
    val unavailable = stringResource(R.string.common_unavailable)
    val labels = available.associate { option -> option.value to option.label }
    SettingsDropdownRow(
        title = field.localizedLabel(),
        icon = field.editorIcon(),
        items = values.map { option ->
            if (option.isBlank()) notSpecified else labels[option] ?: unavailable
        },
        selectedIndex = values.indexOf(current).coerceAtLeast(0),
        summary = error.orEmpty(),
        onSelectedIndexChange = { index ->
            onDocumentChange(document.setText(field.path, values[index]))
        },
    )
}

private fun OutboundFieldSpec.editorIcon(): ImageVector = when {
    path == "server" -> Icons.Rounded.Dns
    path == "server_port" || path.endsWith("_port") -> Icons.Rounded.SettingsEthernet
    path.contains("password") ||
        path.endsWith("psk") ||
        path.contains("private_key") ||
        path in setOf("auth", "auth_str") -> Icons.Rounded.Lock
    path.contains("certificate") -> Icons.Rounded.VpnLock
    path.endsWith("server_name") -> Icons.Rounded.Language
    path.contains("insecure") -> Icons.Rounded.Warning
    path.contains("upload") || path.startsWith("up_") -> Icons.Rounded.Upload
    path.contains("download") || path.startsWith("down_") -> Icons.Rounded.Download
    path.contains("bandwidth") || path.contains("congestion") -> Icons.Rounded.Speed
    path.contains("timeout") ||
        path.contains("interval") ||
        path.contains("delay") ||
        path.contains("keep_alive") ||
        path.contains("heartbeat") -> Icons.Rounded.Timer
    path == "detour" -> Icons.AutoMirrored.Rounded.AltRoute
    path.startsWith("transport.") ||
        path.contains("headers") ||
        path.endsWith("path") ||
        path.endsWith("host") -> Icons.Rounded.Http
    path.startsWith("multiplex.") -> Icons.Rounded.Hub
    path.contains("network") ||
        path.contains("interface") ||
        path.contains("address") -> Icons.Rounded.Public
    path.contains("fingerprint") ||
        path.contains("public_key") ||
        path.contains("short_id") ||
        path.contains("uuid") -> Icons.Rounded.Policy
    path.startsWith("tls.") -> Icons.Rounded.Security
    path.contains("version") ||
        path.contains("method") ||
        path.contains("mode") ||
        path.contains("flow") -> Icons.Rounded.DataObject
    kind in setOf(OutboundFieldKind.TEXT_LIST, OutboundFieldKind.MULTI_SELECT) ->
        Icons.Rounded.ViewAgenda
    kind == OutboundFieldKind.INTEGER -> Icons.Rounded.DataUsage
    else -> Icons.Rounded.Tune
}

@Composable
private fun OutboundFieldSpec.localizedOptionLabel(option: String): String {
    val labelRes = if (path in setOf("network_type", "fallback_network_type")) {
        when (option) {
            "wifi" -> R.string.routing_network_type_wifi
            "cellular" -> R.string.routing_network_type_cellular
            "ethernet" -> R.string.routing_network_type_ethernet
            else -> R.string.routing_network_type_other
        }
    } else {
        null
    }
    return labelRes?.let { resource ->
        singBoxOptionLabel(stringResource(resource), option)
    } ?: option
}

@Composable
internal fun OutboundEditorSection.localizedTitle(): String = when (this) {
    OutboundEditorSection.SERVER -> stringResource(R.string.outbound_editor_section_server)
    OutboundEditorSection.PROTOCOL -> stringResource(R.string.outbound_editor_section_protocol)
    OutboundEditorSection.TLS -> stringResource(R.string.outbound_editor_section_tls)
    OutboundEditorSection.TRANSPORT -> stringResource(R.string.outbound_editor_section_transport)
    OutboundEditorSection.MULTIPLEX -> stringResource(R.string.outbound_editor_section_multiplex)
    OutboundEditorSection.QUIC -> stringResource(R.string.outbound_editor_section_quic)
    OutboundEditorSection.DIAL -> stringResource(R.string.outbound_editor_section_dial)
}

@Composable
internal fun OutboundEditorSection.localizedSummary(): String = when (this) {
    OutboundEditorSection.SERVER -> stringResource(R.string.outbound_editor_section_server_summary)
    OutboundEditorSection.PROTOCOL -> stringResource(R.string.outbound_editor_section_protocol_summary)
    OutboundEditorSection.TLS -> stringResource(R.string.outbound_editor_section_tls_summary)
    OutboundEditorSection.TRANSPORT -> stringResource(R.string.outbound_editor_section_transport_summary)
    OutboundEditorSection.MULTIPLEX -> stringResource(R.string.outbound_editor_section_multiplex_summary)
    OutboundEditorSection.QUIC -> stringResource(R.string.outbound_editor_section_quic_summary)
    OutboundEditorSection.DIAL -> stringResource(R.string.outbound_editor_section_dial_summary)
}

@Composable
private fun OutboundFieldSpec.localizedLabel(): String {
    return stringResource(labelRes)
}

@Composable
private fun OutboundEditorValidationError.localizedMessage(field: OutboundFieldSpec): String =
    when (reason) {
        OutboundEditorValidationReason.REQUIRED -> stringResource(
            R.string.outbound_editor_field_required,
            field.localizedLabel(),
        )
        OutboundEditorValidationReason.INVALID_PORT ->
            stringResource(R.string.outbound_editor_port_invalid)
        OutboundEditorValidationReason.INVALID_INTEGER -> stringResource(
            R.string.outbound_editor_integer_invalid,
            field.localizedLabel(),
        )
        OutboundEditorValidationReason.INVALID_REFERENCE -> stringResource(
            R.string.outbound_editor_reference_unavailable,
            field.localizedLabel(),
        )
    }

@Composable
private fun app.OutboundGroupState.displayName(): String = name
