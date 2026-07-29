// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.settings.sheets

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.ManagedOutboundChoice
import app.ManagedReferenceChoice
import app.SingBoxDnsRuleActions
import app.SingBoxDnsRuleState
import app.SingBoxDnsServerState
import app.SingBoxDnsServerTypes
import app.visibleManagedReference
import engine.network.isCidrAddress
import engine.network.isIpAddress
import engine.network.isIpv4CidrAddress
import engine.singbox.DefaultSingBoxDnsFakeIpRange
import engine.singbox.DefaultSingBoxDnsTimeout
import engine.singbox.config.sanitized
import engine.singbox.isNonNegativeSingBoxDuration
import engine.singbox.isSingBoxDnsQueryType
import engine.singbox.isSingBoxDnsRCode
import engine.singbox.isSingBoxPortRange
import engine.singbox.isSingBoxUnsigned16
import engine.singbox.isSingBoxUnsigned32
import features.dns.DnsMatchResponseChoice
import features.dns.DnsRuleMatcherGroups
import features.dns.withDnsRuleMatchValues
import features.settings.DnsSettingsDraft
import features.settings.withDnsServerTagReplacement
import org.asterisk.zcc.abox.R
import ui.components.AsteriskInfoChip
import ui.components.ReferenceSelectionCard
import ui.components.StringListEditor
import ui.components.localizedLabel
import ui.components.singBoxOptionLabel
import ui.components.singBoxProtocolChoices
import ui.theme.AsteriskMotion
import ui.theme.AsteriskShapeTokens
import ui.icons.AsteriskIcons as Icons

private val DnsIpVersions = listOf("", "4", "6")
private val DnsNetworks = listOf("", "tcp", "udp")
private val DnsNetworkTypes = listOf("wifi", "cellular", "ethernet", "other")
private val DnsRejectMethods = listOf("default", "drop")

private val DnsResponseCodes = listOf(
    "",
    "NOERROR",
    "FORMERR",
    "SERVFAIL",
    "NXDOMAIN",
    "NOTIMP",
    "NOTIMPL",
    "REFUSED",
    "YXDOMAIN",
    "YXRRSET",
    "NXRRSET",
    "NOTAUTH",
    "NOTZONE",
    "DSOTYPENI",
    "BADSIG",
    "BADKEY",
    "BADTIME",
    "BADMODE",
    "BADNAME",
    "BADALG",
    "BADTRUNC",
    "BADCOOKIE",
)
private val NetworkDnsServerTypes = setOf("udp", "tcp", "tls", "quic", "https", "h3")
private val EndpointDnsServerTypes = setOf("tailscale", "openconnect", "openvpn")

private data class DnsServerEditorState(
    val index: Int?,
    val server: SingBoxDnsServerState,
)

internal data class DnsRuleEditorState(
    val index: Int?,
    val rule: SingBoxDnsRuleState,
)

@Composable
internal fun DnsSettingsBottomSheet(
    show: Boolean,
    saving: Boolean,
    draft: DnsSettingsDraft,
    outboundProxyChoices: List<ManagedOutboundChoice>,
    endpointChoicesByServerType: Map<String, List<ManagedReferenceChoice>>,
    onDraftChange: (DnsSettingsDraft) -> Unit,
    onDismissRequest: () -> Unit,
    onSave: (DnsSettingsDraft) -> Unit,
) {
    var serverEditor by remember {
        mutableStateOf(
            DnsServerEditorState(
                index = null,
                server = SingBoxDnsServerState(type = "local"),
            ),
        )
    }
    var showServerEditor by remember(show) { mutableStateOf(false) }
    val serverTags = draft.dnsServers
        .map { server -> server.tag.trim() }
        .filter(String::isNotEmpty)
    val serverLabels = draft.dnsServers.map { server ->
        server.remarks.ifBlank { dnsServerTypeLabel(server.type) }
    }
    val defaultDomainResolverTags = listOf("") + serverTags
    val defaultDomainResolverLabels =
        listOf(stringResource(R.string.common_not_specified)) + serverLabels
    val endpointLabels = endpointChoicesByServerType.values
        .flatten()
        .associate { choice -> choice.tag to choice.remarks }
    val unavailableLabel = stringResource(R.string.common_unavailable)
    val timeoutError = dnsDurationError(draft.dnsTimeout, stringResource(R.string.settings_dns_duration_invalid))
    val cacheCapacityError = draft.dnsCacheCapacity.trim()
        .takeIf(String::isNotEmpty)
        ?.let { value ->
            if (isSingBoxUnsigned32(value)) {
                null
            } else {
                stringResource(R.string.settings_dns_cache_capacity_invalid)
            }
        }
    val defaultServerError = stringResource(R.string.settings_dns_final_required)
        .takeIf { serverTags.isNotEmpty() && draft.dnsFinal !in serverTags }
    val defaultDomainResolverError =
        stringResource(R.string.settings_dns_default_domain_resolver_unavailable)
            .takeIf {
                draft.routeDefaultDomainResolver.isNotBlank() &&
                    draft.routeDefaultDomainResolver !in serverTags
            }
    val serverListError = when {
        draft.enableLocalDns && draft.dnsServers.isEmpty() ->
            stringResource(R.string.settings_dns_server_required)
        draft.dnsServers.map { server -> server.id }.distinct().size != draft.dnsServers.size ->
            stringResource(R.string.settings_dns_server_identity_invalid)
        else -> null
    }
    val canSave = timeoutError == null &&
        cacheCapacityError == null &&
        defaultServerError == null &&
        defaultDomainResolverError == null &&
        serverListError == null

    SettingsModalBottomSheet(
        show = show,
        title = stringResource(R.string.settings_dns),
        startAction = {
            TextButton(
                text = stringResource(R.string.common_cancel),
                icon = Icons.Rounded.Close,
                enabled = !saving,
                onClick = onDismissRequest,
            )
        },
        endAction = {
            TextButton(
                text = stringResource(R.string.common_save),
                icon = Icons.Rounded.Save,
                enabled = canSave && !saving,
                onClick = { onSave(draft.sanitized()) },
            )
        },
        onDismissRequest = onDismissRequest,
    ) {
        key(show) {
            SettingsSheetContent {
                DnsSheetSection(title = stringResource(R.string.settings_dns_section_basic)) {
                    SwitchPreference(
                        title = stringResource(R.string.settings_vpn_local_dns),
                        icon = Icons.Rounded.Dns,
                        summary = stringResource(R.string.settings_vpn_local_dns_summary),
                        checked = draft.enableLocalDns,
                        onCheckedChange = { onDraftChange(draft.copy(enableLocalDns = it)) },
                    )
                    WindowDropdownPreference(
                        title = stringResource(R.string.settings_dns_final),
                        icon = Icons.AutoMirrored.Rounded.AltRoute,
                        items = serverLabels,
                        selectedIndex = serverTags.indexOf(draft.dnsFinal).coerceAtLeast(0),
                        onSelectedIndexChange = { index ->
                            onDraftChange(draft.copy(dnsFinal = serverTags[index]))
                        },
                        summary = defaultServerError
                            ?: stringResource(R.string.settings_dns_final_summary),
                    )
                    WindowDropdownPreference(
                        title = stringResource(R.string.settings_dns_default_domain_resolver),
                        icon = Icons.Rounded.Public,
                        items = defaultDomainResolverLabels,
                        selectedIndex = defaultDomainResolverTags
                            .indexOf(draft.routeDefaultDomainResolver)
                            .coerceAtLeast(0),
                        onSelectedIndexChange = { index ->
                            onDraftChange(
                                draft.copy(
                                    routeDefaultDomainResolver =
                                        defaultDomainResolverTags[index],
                                ),
                            )
                        },
                        summary = defaultDomainResolverError
                            ?: stringResource(
                                R.string.settings_dns_default_domain_resolver_summary,
                            ),
                    )
                    SwitchPreference(
                        title = stringResource(R.string.settings_dns_optimistic_cache),
                        icon = Icons.Rounded.Speed,
                        summary = stringResource(R.string.settings_dns_optimistic_cache_summary),
                        checked = draft.dnsOptimisticCache,
                        onCheckedChange = { enabled ->
                            onDraftChange(
                                draft.copy(
                                    dnsOptimisticCache = enabled,
                                    dnsDisableCache = if (enabled) false else draft.dnsDisableCache,
                                    dnsDisableExpire = if (enabled) false else draft.dnsDisableExpire,
                                ),
                            )
                        },
                    )
                    AnimatedVisibility(
                        visible = !draft.dnsOptimisticCache,
                        enter = AsteriskMotion.contentEnter(),
                        exit = AsteriskMotion.contentExit(),
                    ) {
                        Column {
                            SwitchPreference(
                                title = stringResource(R.string.settings_dns_disable_cache),
                                icon = Icons.Rounded.Storage,
                                checked = draft.dnsDisableCache,
                                onCheckedChange = {
                                    onDraftChange(
                                        draft.copy(
                                            dnsDisableCache = it,
                                            dnsOptimisticCache = false,
                                        ),
                                    )
                                },
                            )
                            SwitchPreference(
                                title = stringResource(R.string.settings_dns_disable_expire),
                                icon = Icons.Rounded.History,
                                checked = draft.dnsDisableExpire,
                                onCheckedChange = {
                                    onDraftChange(
                                        draft.copy(
                                            dnsDisableExpire = it,
                                            dnsOptimisticCache = false,
                                        ),
                                    )
                                },
                            )
                        }
                    }
                    SettingsTextField(
                        value = draft.dnsCacheCapacity,
                        onValueChange = {
                            onDraftChange(
                                draft.copy(
                                    dnsCacheCapacity = it.filter(Char::isDigit).take(10),
                                ),
                            )
                        },
                        label = stringResource(R.string.settings_dns_cache_capacity),
                        errorText = cacheCapacityError,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Number,
                            imeAction = ImeAction.Next,
                        ),
                    )
                    SettingsTextField(
                        value = draft.dnsTimeout,
                        onValueChange = { onDraftChange(draft.copy(dnsTimeout = it)) },
                        label = stringResource(R.string.settings_dns_timeout),
                        errorText = timeoutError,
                    )
                }

                DnsSheetSection(title = stringResource(R.string.settings_dns_section_servers)) {
                    DnsObjectList(
                        count = draft.dnsServers.size,
                        emptyText = stringResource(R.string.settings_dns_servers_empty),
                        errorText = serverListError,
                        onAdd = {
                            serverEditor = DnsServerEditorState(
                                index = null,
                                server = SingBoxDnsServerState(
                                    id = draft.nextDnsServerId,
                                    type = "local",
                                ),
                            )
                            showServerEditor = true
                        },
                    ) {
                        draft.dnsServers.forEachIndexed { index, server ->
                            DnsObjectRow(
                                title = server.remarks.ifBlank { dnsServerTypeLabel(server.type) },
                                summary = dnsServerSummary(server, endpointLabels, unavailableLabel),
                                onEdit = {
                                    serverEditor = DnsServerEditorState(index, server)
                                    showServerEditor = true
                                },
                                onDelete = {
                                    val deletedTag = server.tag.trim()
                                    val servers = removeDnsServerAndReferences(draft.dnsServers, index)
                                    val remainingTags = servers
                                        .map { item -> item.tag.trim() }
                                        .filter(String::isNotEmpty)
                                    val dnsFinal = draft.dnsFinal
                                        .takeIf { tag -> tag != deletedTag && tag in remainingTags }
                                        ?: remainingTags.firstOrNull().orEmpty()
                                    val routeDefaultDomainResolver =
                                        draft.routeDefaultDomainResolver
                                            .takeIf { tag ->
                                                tag != deletedTag && tag in remainingTags
                                            }
                                            .orEmpty()
                                    onDraftChange(
                                        draft.copy(
                                            dnsFinal = dnsFinal,
                                            routeDefaultDomainResolver =
                                                routeDefaultDomainResolver,
                                            dnsServers = servers,
                                            dnsServerTagReplacements =
                                                draft.dnsServerTagReplacements
                                                    .withDnsServerTagReplacement(
                                                        deletedTag,
                                                        dnsFinal,
                                                    ),
                                            dnsPreferredByTagReplacements =
                                                draft.dnsPreferredByTagReplacements
                                                    .withDnsServerTagReplacement(
                                                        deletedTag,
                                                        "",
                                                    ),
                                        ),
                                    )
                                },
                            )
                        }
                    }
                }

            }
        }
    }

    DnsServerEditorSheet(
        show = show && showServerEditor,
        editor = serverEditor,
        existingServers = draft.dnsServers,
        outboundProxyChoices = outboundProxyChoices,
        endpointChoicesByServerType = endpointChoicesByServerType,
        onEditorChange = { server -> serverEditor = serverEditor.copy(server = server) },
        onDismissRequest = { showServerEditor = false },
        onSave = { server ->
            val oldTag = serverEditor.index
                ?.let { index -> draft.dnsServers[index].tag.trim() }
            val servers = serverEditor.index?.let { index ->
                draft.dnsServers.mapIndexed { itemIndex, current ->
                    if (itemIndex == index) server else current
                }
            } ?: (draft.dnsServers + server)
            val dnsFinal = when {
                draft.dnsFinal.isBlank() -> server.tag
                oldTag != null && draft.dnsFinal == oldTag -> server.tag
                else -> draft.dnsFinal
            }
            val routeDefaultDomainResolver =
                if (oldTag != null && draft.routeDefaultDomainResolver == oldTag) {
                    server.tag
                } else {
                    draft.routeDefaultDomainResolver
                }
            onDraftChange(
                draft.copy(
                    dnsFinal = dnsFinal,
                    routeDefaultDomainResolver = routeDefaultDomainResolver,
                    dnsServers = servers.map { item ->
                        if (oldTag != null && item.domainResolver == oldTag) {
                            item.copy(domainResolver = server.tag)
                        } else {
                            item
                        }
                    },
                    nextDnsServerId = if (serverEditor.index == null) {
                        draft.nextDnsServerId + 1
                    } else {
                        draft.nextDnsServerId
                    },
                ),
            )
            showServerEditor = false
        },
    )

}

@Composable
private fun DnsServerEditorSheet(
    show: Boolean,
    editor: DnsServerEditorState,
    existingServers: List<SingBoxDnsServerState>,
    outboundProxyChoices: List<ManagedOutboundChoice>,
    endpointChoicesByServerType: Map<String, List<ManagedReferenceChoice>>,
    onEditorChange: (SingBoxDnsServerState) -> Unit,
    onDismissRequest: () -> Unit,
    onSave: (SingBoxDnsServerState) -> Unit,
) {
    val server = editor.server
    val outboundProxyTags = outboundProxyChoices.map(ManagedOutboundChoice::tag)
    val serverTypeLabels = SingBoxDnsServerTypes.map { type -> dnsServerTypeLabel(type) }
    val fieldLayout = dnsServerFieldLayout(server.type)
    val endpointChoices = endpointChoicesByServerType[server.type].orEmpty()
    val endpointTags = endpointChoices.map(ManagedReferenceChoice::tag)
    val supportsDomainResolver = fieldLayout.kind == DnsServerFieldKind.Network ||
        fieldLayout.kind == DnsServerFieldKind.Dhcp ||
        fieldLayout.kind == DnsServerFieldKind.Mdns
    val domainResolverChoices = dnsDomainResolverChoices(existingServers, editor.index)
    val domainResolverTags = domainResolverChoices.map(ManagedReferenceChoice::tag)
    val requiredError = stringResource(R.string.settings_dns_field_required)
    val invalidAddressMessage = stringResource(R.string.settings_dns_server_address_invalid)
    val addressError = when {
        server.type !in NetworkDnsServerTypes -> null
        server.server.isBlank() -> requiredError
        !isDnsNetworkServerAddress(server.server) -> invalidAddressMessage
        else -> null
    }
    val domainResolverError = when {
        supportsDomainResolver &&
            server.type in NetworkDnsServerTypes &&
            server.server.isNotBlank() &&
            dnsServerDomainResolverRequired(
                address = server.server,
            ) &&
            server.domainResolver.isBlank() ->
            stringResource(R.string.settings_dns_domain_resolver_required)
        supportsDomainResolver &&
            server.domainResolver.isNotBlank() &&
            server.domainResolver !in domainResolverTags ->
            stringResource(R.string.settings_dns_domain_resolver_required)
        else -> null
    }
    val detourError = stringResource(R.string.settings_dns_outbound_proxy_unavailable)
        .takeIf { server.detour.isNotBlank() && server.detour !in outboundProxyTags }
    val endpointError = when {
        server.type !in EndpointDnsServerTypes -> null
        server.endpoint.isBlank() -> requiredError
        server.endpoint !in endpointTags -> stringResource(R.string.settings_dns_endpoint_unavailable)
        else -> null
    }
    val serviceError = requiredError.takeIf { server.type == "resolved" && server.service.isBlank() }
    val hostsInvalidMessage = stringResource(R.string.settings_dns_hosts_invalid)
    val fakeIpError = if (
        server.type == "fakeip" &&
        !isIpv4CidrAddress(server.inet4Range.ifBlank { DefaultSingBoxDnsFakeIpRange })
    ) {
        stringResource(R.string.settings_dns_cidr_invalid)
    } else {
        null
    }
    val portError = server.serverPort.trim()
        .takeIf(String::isNotEmpty)
        ?.let { value ->
            if (isDnsServerPort(value)) null else stringResource(R.string.settings_dns_port_invalid)
        }
    val canSave = listOf(
        addressError,
        domainResolverError,
        detourError,
        endpointError,
        serviceError,
        fakeIpError,
        portError,
    ).all { it == null }
    val fieldSizeMotion = AsteriskMotion.contentSpatial<IntSize>()
    val fieldEffectsMotion = AsteriskMotion.effects<Float>()

    SettingsModalBottomSheet(
        show = show,
        title = stringResource(
            if (editor.index == null) R.string.settings_dns_add_server else R.string.settings_dns_edit_server,
        ),
        startAction = {
            TextButton(
                text = stringResource(R.string.common_cancel),
                icon = Icons.Rounded.Close,
                onClick = onDismissRequest,
            )
        },
        endAction = {
            TextButton(
                text = stringResource(R.string.common_save),
                icon = Icons.Rounded.Save,
                enabled = canSave,
                onClick = { onSave(server.sanitized()) },
            )
        },
        onDismissRequest = onDismissRequest,
    ) {
        SettingsSheetContent {
            WindowDropdownPreference(
                title = stringResource(R.string.settings_dns_server_type),
                icon = Icons.Rounded.Dns,
                items = serverTypeLabels,
                selectedIndex = SingBoxDnsServerTypes.indexOf(server.type).coerceAtLeast(0),
                onSelectedIndexChange = { index ->
                    onEditorChange(server.copy(type = SingBoxDnsServerTypes[index]))
                },
            )
            SettingsTextField(
                value = server.remarks,
                onValueChange = { onEditorChange(server.copy(remarks = it)) },
                label = stringResource(R.string.settings_dns_server_remarks),
                errorText = null,
            )

            AnimatedContent(
                targetState = fieldLayout,
                modifier = Modifier.fillMaxWidth(),
                transitionSpec = {
                    fadeIn(animationSpec = fieldEffectsMotion)
                        .togetherWith(fadeOut(animationSpec = fieldEffectsMotion))
                        .using(
                            SizeTransform(
                                sizeAnimationSpec = { _, _ -> fieldSizeMotion },
                            ),
                        )
                },
                contentAlignment = Alignment.TopStart,
                label = "dns-server-type-fields",
            ) { currentLayout ->
                Column(modifier = Modifier.fillMaxWidth()) {
                    when (currentLayout.kind) {
                    DnsServerFieldKind.Local -> {
                    SwitchPreference(
                        title = stringResource(R.string.settings_dns_prefer_go),
                        icon = Icons.Rounded.Tune,
                        checked = server.preferGo,
                        onCheckedChange = { onEditorChange(server.copy(preferGo = it)) },
                    )
                    StringListEditor(
                        editorKey = "dns-server-neighbor:${editor.index}",
                        title = stringResource(R.string.settings_dns_neighbor_domain),
                        values = server.neighborDomain,
                        onValuesChange = { onEditorChange(server.copy(neighborDomain = it)) },
                        emptyText = stringResource(R.string.settings_dns_list_empty),
                    )
                    }
                    DnsServerFieldKind.Hosts -> {
                    StringListEditor(
                        editorKey = "dns-server-host-path:${editor.index}",
                        title = stringResource(R.string.settings_dns_hosts_path),
                        values = server.hostsPaths,
                        onValuesChange = { onEditorChange(server.copy(hostsPaths = it)) },
                        emptyText = stringResource(R.string.settings_dns_list_empty),
                    )
                    Spacer(Modifier.height(8.dp))
                    StringListEditor(
                        editorKey = "dns-server-hosts:${editor.index}",
                        title = stringResource(R.string.settings_dns_predefined_hosts),
                        description = stringResource(R.string.settings_dns_predefined_hosts_format),
                        values = server.predefinedHosts,
                        onValuesChange = { onEditorChange(server.copy(predefinedHosts = it)) },
                        emptyText = stringResource(R.string.settings_dns_list_empty),
                        validateInput = { value ->
                            dnsPredefinedHostError(value, hostsInvalidMessage)
                        },
                    )
                    }
                    DnsServerFieldKind.Network -> {
                    SettingsTextField(
                        value = server.server,
                        onValueChange = { onEditorChange(server.copy(server = it)) },
                        label = stringResource(R.string.settings_dns_server_address),
                        errorText = addressError,
                    )
                    SettingsTextField(
                        value = server.serverPort,
                        onValueChange = { onEditorChange(server.copy(serverPort = it.filter(Char::isDigit).take(5))) },
                        label = stringResource(R.string.settings_dns_server_port),
                        errorText = portError,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Number,
                            imeAction = ImeAction.Next,
                        ),
                    )
                    if (currentLayout.showPath) {
                        SettingsTextField(
                            value = server.path,
                            onValueChange = { onEditorChange(server.copy(path = it)) },
                            label = stringResource(R.string.settings_dns_server_path),
                            errorText = null,
                        )
                    }
                    if (currentLayout.showTls) {
                        SettingsTextField(
                            value = server.tlsServerName,
                            onValueChange = { onEditorChange(server.copy(tlsServerName = it)) },
                            label = stringResource(R.string.settings_dns_tls_server_name),
                            errorText = null,
                        )
                        SwitchPreference(
                            title = stringResource(R.string.settings_dns_tls_insecure),
                            icon = Icons.Rounded.Security,
                            checked = server.tlsInsecure,
                            onCheckedChange = { onEditorChange(server.copy(tlsInsecure = it)) },
                        )
                    }
                    DnsDialFields(
                        server = server,
                        domainResolverChoices = domainResolverChoices,
                        outboundProxyChoices = outboundProxyChoices,
                        onServerChange = onEditorChange,
                        domainResolverError = domainResolverError,
                        detourError = detourError,
                    )
                    }
                    DnsServerFieldKind.Dhcp -> {
                    SettingsTextField(
                        value = server.interfaceName,
                        onValueChange = { onEditorChange(server.copy(interfaceName = it)) },
                        label = stringResource(R.string.settings_dns_interface),
                        errorText = null,
                    )
                    DnsDialFields(
                        server = server,
                        domainResolverChoices = domainResolverChoices,
                        outboundProxyChoices = outboundProxyChoices,
                        onServerChange = onEditorChange,
                        domainResolverError = domainResolverError,
                        detourError = detourError,
                    )
                    }
                    DnsServerFieldKind.Mdns -> {
                    StringListEditor(
                        editorKey = "dns-server-interface:${editor.index}",
                        title = stringResource(R.string.settings_dns_interfaces),
                        values = server.interfaceNames,
                        onValuesChange = { onEditorChange(server.copy(interfaceNames = it)) },
                        emptyText = stringResource(R.string.settings_dns_list_empty),
                    )
                    Spacer(Modifier.height(8.dp))
                    DnsDialFields(
                        server = server,
                        domainResolverChoices = domainResolverChoices,
                        outboundProxyChoices = outboundProxyChoices,
                        onServerChange = onEditorChange,
                        domainResolverError = domainResolverError,
                        detourError = detourError,
                    )
                    }
                    DnsServerFieldKind.FakeIp -> {
                    SettingsTextField(
                        value = server.inet4Range,
                        onValueChange = { onEditorChange(server.copy(inet4Range = it)) },
                        label = stringResource(R.string.settings_dns_fake_ip_v4_range),
                        errorText = fakeIpError,
                    )
                    SettingsTextField(
                        value = server.inet6Range,
                        onValueChange = { onEditorChange(server.copy(inet6Range = it)) },
                        label = stringResource(R.string.settings_dns_fake_ip_v6_range),
                        errorText = server.inet6Range.takeIf(String::isNotBlank)?.let { range ->
                            if (isCidrAddress(range) && ":" in range) null
                            else stringResource(R.string.settings_dns_cidr_invalid)
                        },
                    )
                    }
                    DnsServerFieldKind.Endpoint -> {
                    val endpointValues = listOf("") + endpointTags
                    WindowDropdownPreference(
                        title = stringResource(R.string.settings_dns_endpoint),
                        icon = Icons.Rounded.Hub,
                        items = listOf(stringResource(R.string.common_not_specified)) +
                            endpointChoices.map { choice ->
                                choice.remarks.ifBlank {
                                    stringResource(R.string.settings_dns_endpoint_fallback)
                                }
                            },
                        selectedIndex = endpointValues.indexOf(server.endpoint).coerceAtLeast(0),
                        onSelectedIndexChange = { index ->
                            onEditorChange(server.copy(endpoint = endpointValues[index]))
                        },
                        summary = endpointError.orEmpty(),
                    )
                    SwitchPreference(
                        title = stringResource(R.string.settings_dns_accept_default_resolvers),
                        icon = Icons.Rounded.Dns,
                        checked = server.acceptDefaultResolvers,
                        onCheckedChange = { onEditorChange(server.copy(acceptDefaultResolvers = it)) },
                    )
                    SwitchPreference(
                        title = stringResource(R.string.settings_dns_accept_search_domain),
                        icon = Icons.Rounded.Search,
                        checked = server.acceptSearchDomain,
                        onCheckedChange = { onEditorChange(server.copy(acceptSearchDomain = it)) },
                    )
                    }
                    DnsServerFieldKind.Resolved -> {
                    SettingsTextField(
                        value = server.service,
                        onValueChange = { onEditorChange(server.copy(service = it)) },
                        label = stringResource(R.string.settings_dns_resolved_service),
                        errorText = serviceError,
                    )
                    SwitchPreference(
                        title = stringResource(R.string.settings_dns_accept_default_resolvers),
                        icon = Icons.Rounded.Dns,
                        checked = server.acceptDefaultResolvers,
                        onCheckedChange = { onEditorChange(server.copy(acceptDefaultResolvers = it)) },
                    )
                    }
                        DnsServerFieldKind.None -> Unit
                    }
                }
            }
        }
    }
}

@Composable
private fun DnsDialFields(
    server: SingBoxDnsServerState,
    domainResolverChoices: List<ManagedReferenceChoice>,
    outboundProxyChoices: List<ManagedOutboundChoice>,
    onServerChange: (SingBoxDnsServerState) -> Unit,
    domainResolverError: String? = null,
    detourError: String? = null,
) {
    val domainResolverTags = domainResolverChoices.map(ManagedReferenceChoice::tag)
    val domainResolverValues = listOf("") + domainResolverTags
    WindowDropdownPreference(
        title = stringResource(R.string.settings_dns_domain_resolver),
        icon = Icons.Rounded.Dns,
        items = listOf(stringResource(R.string.common_not_specified)) +
            domainResolverChoices.map { choice ->
                choice.remarks.ifBlank {
                    stringResource(R.string.settings_dns_server_fallback)
                }
            },
        selectedIndex = domainResolverValues.indexOf(server.domainResolver).coerceAtLeast(0),
        onSelectedIndexChange = { index ->
            onServerChange(server.copy(domainResolver = domainResolverValues[index]))
        },
        summary = domainResolverError.orEmpty(),
    )
    val detourValues = listOf("") + outboundProxyChoices.map(ManagedOutboundChoice::tag)
    WindowDropdownPreference(
        title = stringResource(R.string.settings_dns_outbound_proxy),
        icon = Icons.AutoMirrored.Rounded.AltRoute,
        items = listOf(stringResource(R.string.common_not_specified)) +
            outboundProxyChoices.map { choice -> choice.localizedLabel() },
        selectedIndex = detourValues.indexOf(server.detour).coerceAtLeast(0),
        onSelectedIndexChange = { index ->
            onServerChange(server.copy(detour = detourValues[index]))
        },
        summary = detourError.orEmpty(),
    )
}

@Composable
internal fun DnsRuleEditorSheet(
    show: Boolean,
    editor: DnsRuleEditorState,
    serverChoices: List<Pair<String, String>>,
    inboundChoices: List<Pair<String, String>>,
    preferredByChoices: List<Pair<String, String>>,
    matchResponseChoices: List<Pair<DnsMatchResponseChoice, String>>,
    ruleSetChoices: List<Pair<String, String>>,
    saving: Boolean = false,
    onEditorChange: (SingBoxDnsRuleState) -> Unit,
    onDismissRequest: () -> Unit,
    onSave: (SingBoxDnsRuleState) -> Unit,
) {
    val rule = editor.rule
    val pendingMatchers = remember(editor.index, rule.id) {
        mutableStateMapOf<String, Boolean>()
    }
    val actionLabels = SingBoxDnsRuleActions.map { action -> dnsRuleActionLabel(action) }
    val rejectMethodLabels = DnsRejectMethods.map { method -> dnsRejectMethodLabel(method) }
    val protocolChoices = singBoxProtocolChoices()
    val serverTags = serverChoices.map { choice -> choice.first }
    val durationMessage = stringResource(R.string.settings_dns_duration_invalid)
    val invalidMatchMessage = stringResource(R.string.settings_dns_rule_value_invalid)
    val routeAction = rule.action == "route" || rule.action == "evaluate"
    val serverError = if (routeAction && rule.server !in serverTags) {
        stringResource(R.string.settings_dns_server_required)
    } else {
        null
    }
    val timeoutError = rule.timeout.takeIf(String::isNotBlank)?.let {
        dnsDurationError(it, durationMessage)
    }
    val ttlError = rule.rewriteTtl.takeIf(String::isNotBlank)?.let { value ->
        if (isSingBoxUnsigned32(value)) null
        else stringResource(R.string.settings_dns_ttl_invalid)
    }
    val subnetError = rule.clientSubnet.takeIf(String::isNotBlank)?.let { value ->
        if (isIpAddress(value) || isCidrAddress(value)) null
        else stringResource(R.string.settings_dns_cidr_invalid)
    }
    val rcodeError = rule.rcode
        .takeIf { value -> rule.action == "predefined" && value.isNotBlank() }
        ?.let { value ->
            if (isSingBoxDnsRCode(value)) null else invalidMatchMessage
        }
    val matchesValid = rule.matches.all { match ->
        val managedChoices = when (match.field) {
            "inbound" -> inboundChoices.map { choice -> choice.first }
            "preferred_by" -> preferredByChoices.map { choice -> choice.first }
            "match_response" -> null
            "protocol" -> protocolChoices.map { choice -> choice.first }
            "network_type" -> DnsNetworkTypes
            "rule_set" -> ruleSetChoices.map { choice -> choice.first }
            else -> null
        }
        match.values.isNotEmpty() &&
            (managedChoices == null || match.values.all(managedChoices::contains)) &&
            (
                match.field != "match_response" ||
                    match.values.singleOrNull()?.let { value ->
                        matchResponseChoices.any { choice ->
                            choice.first.value == value && match.encodeAsString
                        }
                    } == true
                ) &&
            match.values.all { value ->
                dnsRuleValueError(match.field, value, invalidMatchMessage) == null
            }
    }
    val canSave = pendingMatchers.isEmpty() &&
        matchesValid &&
        listOf(serverError, timeoutError, ttlError, subnetError, rcodeError).all { it == null }
    val actionSizeMotion = AsteriskMotion.contentSpatial<IntSize>()
    val actionEffectsMotion = AsteriskMotion.effects<Float>()

    SettingsModalBottomSheet(
        show = show,
        title = stringResource(
            if (editor.index == null) R.string.settings_dns_add_rule else R.string.settings_dns_edit_rule,
        ),
        startAction = {
            TextButton(
                text = stringResource(R.string.common_cancel),
                icon = Icons.Rounded.Close,
                enabled = !saving,
                onClick = onDismissRequest,
            )
        },
        endAction = {
            TextButton(
                text = stringResource(R.string.common_save),
                icon = Icons.Rounded.Save,
                enabled = canSave && !saving,
                onClick = { onSave(rule.sanitized()) },
            )
        },
        onDismissRequest = {
            if (!saving) onDismissRequest()
        },
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "basic-title") {
                DnsRuleEditorSectionTitle(
                    stringResource(R.string.routing_section_basic),
                )
            }
            item(key = "remarks") {
                SettingsTextField(
                    value = rule.remarks,
                    onValueChange = { onEditorChange(rule.copy(remarks = it)) },
                    label = stringResource(R.string.dns_rule_remarks),
                    errorText = null,
                )
            }
            item(key = "action") {
                WindowDropdownPreference(
                    title = stringResource(R.string.settings_dns_rule_action),
                    icon = Icons.AutoMirrored.Rounded.AltRoute,
                    items = actionLabels,
                    selectedIndex = SingBoxDnsRuleActions.indexOf(rule.action).coerceAtLeast(0),
                    onSelectedIndexChange = { index ->
                        onEditorChange(rule.copy(action = SingBoxDnsRuleActions[index]))
                    },
                )
            }
            item(key = "action-fields") {
                AnimatedContent(
                    targetState = rule.action,
                    modifier = Modifier.fillMaxWidth(),
                    transitionSpec = {
                        fadeIn(animationSpec = actionEffectsMotion)
                            .togetherWith(fadeOut(animationSpec = actionEffectsMotion))
                            .using(
                                SizeTransform(
                                    sizeAnimationSpec = { _, _ -> actionSizeMotion },
                                ),
                            )
                    },
                    contentAlignment = Alignment.TopStart,
                    label = "dns-rule-action-fields",
                ) { currentAction ->
                    DnsRuleActionFields(
                        action = currentAction,
                        rule = rule,
                        editorKey = editor.rule.id,
                        serverChoices = serverChoices,
                        rejectMethodLabels = rejectMethodLabels,
                        serverError = serverError,
                        timeoutError = timeoutError,
                        ttlError = ttlError,
                        subnetError = subnetError,
                        rcodeError = rcodeError,
                        onRuleChange = onEditorChange,
                    )
                }
            }
            item(key = "invert") {
                SwitchPreference(
                    title = stringResource(R.string.settings_dns_invert),
                    icon = Icons.Rounded.Sync,
                    checked = rule.invert,
                    onCheckedChange = { onEditorChange(rule.copy(invert = it)) },
                )
            }

            DnsRuleMatcherGroups.forEachIndexed { sectionIndex, matchers ->
                item(key = "matcher-section-$sectionIndex") {
                    DnsRuleEditorSectionTitle(
                        stringResource(dnsRuleMatcherSectionTitleResource(sectionIndex)),
                    )
                }
                if (sectionIndex == 0) {
                    item(key = "ip-version") {
                        WindowDropdownPreference(
                            title = dnsRuleMatcherLabel("ip_version"),
                            icon = Icons.Rounded.Language,
                            items = listOf(
                                stringResource(R.string.settings_dns_any),
                                singBoxOptionLabel(
                                    stringResource(R.string.common_ipv4),
                                    "4",
                                ),
                                singBoxOptionLabel(
                                    stringResource(R.string.common_ipv6),
                                    "6",
                                ),
                            ),
                            selectedIndex = DnsIpVersions.indexOf(rule.ipVersion).coerceAtLeast(0),
                            onSelectedIndexChange = { index ->
                                onEditorChange(rule.copy(ipVersion = DnsIpVersions[index]))
                            },
                        )
                    }
                    item(key = "network") {
                        WindowDropdownPreference(
                            title = dnsRuleMatcherLabel("network"),
                            icon = Icons.Rounded.Lan,
                            items = listOf(
                                stringResource(R.string.settings_dns_any),
                                singBoxOptionLabel(
                                    stringResource(R.string.common_tcp),
                                    "tcp",
                                ),
                                singBoxOptionLabel(
                                    stringResource(R.string.common_udp),
                                    "udp",
                                ),
                            ),
                            selectedIndex = DnsNetworks.indexOf(rule.network).coerceAtLeast(0),
                            onSelectedIndexChange = { index ->
                                onEditorChange(rule.copy(network = DnsNetworks[index]))
                            },
                        )
                    }
                }
                items(
                    items = matchers,
                    key = { matcher -> "matcher-$matcher" },
                ) { matcher ->
                    DnsRuleMatchFieldEditor(
                        rule = rule,
                        matcher = matcher,
                        inboundChoices = inboundChoices,
                        preferredByChoices = preferredByChoices,
                        matchResponseChoices = matchResponseChoices,
                        protocolChoices = protocolChoices,
                        ruleSetChoices = ruleSetChoices,
                        invalidMessage = invalidMatchMessage,
                        onRuleChange = onEditorChange,
                        onPendingChange = { pending ->
                            if (pending) {
                                pendingMatchers[matcher] = true
                            } else {
                                pendingMatchers.remove(matcher)
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun DnsRuleActionFields(
    action: String,
    rule: SingBoxDnsRuleState,
    editorKey: Any,
    serverChoices: List<Pair<String, String>>,
    rejectMethodLabels: List<String>,
    serverError: String?,
    timeoutError: String?,
    ttlError: String?,
    subnetError: String?,
    rcodeError: String?,
    onRuleChange: (SingBoxDnsRuleState) -> Unit,
) {
    val serverTags = serverChoices.map { choice -> choice.first }
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        when (action) {
            "route", "evaluate" -> {
                WindowDropdownPreference(
                    title = stringResource(R.string.settings_dns_target_server),
                    icon = Icons.Rounded.Dns,
                    items = serverChoices.map { choice -> choice.second }.ifEmpty {
                        listOf(stringResource(R.string.settings_dns_no_server))
                    },
                    selectedIndex = serverTags.indexOf(rule.server).coerceAtLeast(0),
                    onSelectedIndexChange = { index ->
                        serverTags.getOrNull(index)?.let { tag ->
                            onRuleChange(rule.copy(server = tag))
                        }
                    },
                    summary = serverError.orEmpty(),
                )
                DnsRuleRouteOptions(
                    rule,
                    onRuleChange,
                    timeoutError,
                    ttlError,
                    subnetError,
                )
            }
            "route-options" -> DnsRuleRouteOptions(
                rule,
                onRuleChange,
                timeoutError,
                ttlError,
                subnetError,
            )
            "reject" -> {
                WindowDropdownPreference(
                    title = stringResource(R.string.settings_dns_reject_method),
                    icon = Icons.Rounded.Block,
                    items = rejectMethodLabels,
                    selectedIndex = DnsRejectMethods.indexOf(rule.rejectMethod).coerceAtLeast(0),
                    onSelectedIndexChange = { index ->
                        onRuleChange(rule.copy(rejectMethod = DnsRejectMethods[index]))
                    },
                )
                AnimatedVisibility(
                    visible = rule.rejectMethod != "drop",
                    enter = AsteriskMotion.contentEnter(),
                    exit = AsteriskMotion.contentExit(),
                ) {
                    SwitchPreference(
                        title = stringResource(R.string.settings_dns_no_drop),
                        icon = Icons.Rounded.Security,
                        checked = rule.noDrop,
                        onCheckedChange = {
                            onRuleChange(rule.copy(noDrop = it))
                        },
                    )
                }
            }
            "predefined" -> {
                var customResponseCode by rememberSaveable(editorKey) {
                    mutableStateOf(rule.rcode !in DnsResponseCodes)
                }
                val responseCodeLabels = DnsResponseCodes.map { code ->
                    code.ifBlank {
                        stringResource(R.string.settings_dns_response_code_default)
                    }
                }
                WindowDropdownPreference(
                    title = stringResource(R.string.settings_dns_response_code),
                    icon = Icons.Rounded.Policy,
                    items = responseCodeLabels +
                        stringResource(R.string.settings_dns_custom_response_code),
                    selectedIndex = if (customResponseCode) {
                        DnsResponseCodes.size
                    } else {
                        DnsResponseCodes.indexOf(rule.rcode).coerceAtLeast(0)
                    },
                    onSelectedIndexChange = { index ->
                        customResponseCode = index == DnsResponseCodes.size
                        if (!customResponseCode) {
                            onRuleChange(rule.copy(rcode = DnsResponseCodes[index]))
                        } else if (rule.rcode in DnsResponseCodes) {
                            onRuleChange(rule.copy(rcode = ""))
                        }
                    },
                )
                AnimatedVisibility(
                    visible = customResponseCode,
                    enter = AsteriskMotion.contentEnter(),
                    exit = AsteriskMotion.contentExit(),
                ) {
                    SettingsTextField(
                        value = rule.rcode.takeUnless(DnsResponseCodes::contains).orEmpty(),
                        onValueChange = { value ->
                            onRuleChange(rule.copy(rcode = value))
                        },
                        label = stringResource(R.string.settings_dns_custom_response_code),
                        errorText = rcodeError,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    )
                }
                DnsRecordListEditor(
                    editorKey = "dns-rule-answer:$editorKey",
                    title = stringResource(R.string.settings_dns_answer_records),
                    values = rule.answer,
                    onValuesChange = { onRuleChange(rule.copy(answer = it)) },
                )
                DnsRecordListEditor(
                    editorKey = "dns-rule-ns:$editorKey",
                    title = stringResource(R.string.settings_dns_ns_records),
                    values = rule.ns,
                    onValuesChange = { onRuleChange(rule.copy(ns = it)) },
                )
                DnsRecordListEditor(
                    editorKey = "dns-rule-extra:$editorKey",
                    title = stringResource(R.string.settings_dns_extra_records),
                    values = rule.extra,
                    onValuesChange = { onRuleChange(rule.copy(extra = it)) },
                )
            }
        }
    }
}

@Composable
private fun DnsRuleMatchFieldEditor(
    rule: SingBoxDnsRuleState,
    matcher: String,
    inboundChoices: List<Pair<String, String>>,
    preferredByChoices: List<Pair<String, String>>,
    matchResponseChoices: List<Pair<DnsMatchResponseChoice, String>>,
    protocolChoices: List<Pair<String, String>>,
    ruleSetChoices: List<Pair<String, String>>,
    invalidMessage: String,
    onRuleChange: (SingBoxDnsRuleState) -> Unit,
    onPendingChange: (Boolean) -> Unit,
) {
    val matchState = rule.matches.firstOrNull { match -> match.field == matcher }
    val values = matchState?.values.orEmpty()
    when (matcher) {
        "protocol" -> {
            ReferenceSelectionCard(
                title = dnsRuleMatcherLabel(matcher),
                emptyText = stringResource(R.string.common_not_specified),
                choices = protocolChoices,
                selected = values.toSet(),
                onToggle = { protocol ->
                    val nextValues = if (protocol in values) {
                        values - protocol
                    } else {
                        values + protocol
                    }
                    onRuleChange(rule.withDnsRuleMatchValues(matcher, nextValues))
                },
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            onPendingChange(false)
        }
        "clash_mode" -> {
            val modes = listOf("Rule", "Global", "Direct")
            val labels = listOf(
                singBoxOptionLabel(stringResource(R.string.sing_box_mode_rule), modes[0]),
                singBoxOptionLabel(stringResource(R.string.sing_box_mode_global), modes[1]),
                singBoxOptionLabel(stringResource(R.string.sing_box_mode_direct), modes[2]),
            )
            val selectedIndex = modes
                .indexOfFirst { mode -> mode.equals(values.firstOrNull(), ignoreCase = true) }
                .takeIf { index -> index >= 0 }
                ?.plus(1)
                ?: 0
            WindowDropdownPreference(
                title = dnsRuleMatcherLabel(matcher),
                icon = Icons.Rounded.Policy,
                items = listOf(stringResource(R.string.common_not_specified)) + labels,
                selectedIndex = selectedIndex,
                onSelectedIndexChange = { index ->
                    onRuleChange(
                        rule.withDnsRuleMatchValues(
                            matcher,
                            modes.getOrNull(index - 1)?.let(::listOf).orEmpty(),
                        ),
                    )
                },
            )
            onPendingChange(false)
        }
        "network_type" -> {
            val choices = DnsNetworkTypes.map { networkType ->
                val label = stringResource(
                    when (networkType) {
                        "wifi" -> R.string.routing_network_type_wifi
                        "cellular" -> R.string.routing_network_type_cellular
                        "ethernet" -> R.string.routing_network_type_ethernet
                        else -> R.string.routing_network_type_other
                    },
                )
                networkType to singBoxOptionLabel(label, networkType)
            }
            ReferenceSelectionCard(
                title = dnsRuleMatcherLabel(matcher),
                emptyText = stringResource(R.string.common_not_specified),
                choices = choices,
                selected = values.toSet(),
                onToggle = { networkType ->
                    val nextValues = if (networkType in values) {
                        values - networkType
                    } else {
                        values + networkType
                    }
                    onRuleChange(rule.withDnsRuleMatchValues(matcher, nextValues))
                },
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            onPendingChange(false)
        }
        "response_rcode" -> {
            val namedCodes = DnsResponseCodes.filter(String::isNotEmpty)
            val currentValue = values.firstOrNull().orEmpty()
            var customMode by rememberSaveable(rule.id, matcher) {
                mutableStateOf(currentValue.isNotEmpty() && currentValue !in namedCodes)
            }
            var customValue by rememberSaveable(rule.id, matcher) {
                mutableStateOf(currentValue.takeIf { value -> value !in namedCodes }.orEmpty())
            }
            val selectedIndex = when {
                customMode -> namedCodes.size + 1
                currentValue in namedCodes -> namedCodes.indexOf(currentValue) + 1
                else -> 0
            }
            val customInvalid = customMode && !isSingBoxDnsRCode(customValue)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                WindowDropdownPreference(
                    title = dnsRuleMatcherLabel(matcher),
                    icon = Icons.Rounded.Policy,
                    items = listOf(stringResource(R.string.common_not_specified)) +
                        namedCodes +
                        stringResource(R.string.settings_dns_custom_response_code),
                    selectedIndex = selectedIndex,
                    onSelectedIndexChange = { index ->
                        customMode = index == namedCodes.size + 1
                        when {
                            index == 0 -> onRuleChange(
                                rule.withDnsRuleMatchValues(matcher, emptyList()),
                            )
                            index <= namedCodes.size -> onRuleChange(
                                rule.withDnsRuleMatchValues(
                                    matcher,
                                    listOf(namedCodes[index - 1]),
                                ),
                            )
                            customValue.isNotBlank() -> onRuleChange(
                                rule.withDnsRuleMatchValues(
                                    matcher,
                                    listOf(customValue),
                                ),
                            )
                        }
                    },
                )
                AnimatedVisibility(
                    visible = customMode,
                    enter = AsteriskMotion.contentEnter(),
                    exit = AsteriskMotion.contentExit(),
                ) {
                    SettingsTextField(
                        value = customValue,
                        onValueChange = { value ->
                            customValue = value
                            onRuleChange(
                                rule.withDnsRuleMatchValues(
                                    matcher,
                                    value.takeIf(String::isNotBlank)?.let(::listOf).orEmpty(),
                                ),
                            )
                        },
                        label = stringResource(R.string.settings_dns_custom_response_code),
                        errorText = if (customInvalid) invalidMessage else null,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Number,
                            imeAction = ImeAction.Next,
                        ),
                    )
                }
            }
            onPendingChange(customInvalid)
        }
        "inbound" -> {
            ReferenceSelectionCard(
                title = dnsRuleMatcherLabel(matcher),
                emptyText = stringResource(R.string.managed_inbound_empty),
                choices = inboundChoices,
                selected = values.toSet(),
                onToggle = { tag ->
                    val nextValues = if (tag in values) values - tag else values + tag
                    onRuleChange(rule.withDnsRuleMatchValues(matcher, nextValues))
                },
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            onPendingChange(false)
        }
        "preferred_by" -> {
            ReferenceSelectionCard(
                title = dnsRuleMatcherLabel(matcher),
                emptyText = stringResource(R.string.managed_preferred_by_empty),
                choices = preferredByChoices,
                selected = values.toSet(),
                onToggle = { tag ->
                    val nextValues = if (tag in values) values - tag else values + tag
                    onRuleChange(rule.withDnsRuleMatchValues(matcher, nextValues))
                },
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            onPendingChange(false)
        }
        "rule_set" -> {
            ReferenceSelectionCard(
                title = dnsRuleMatcherLabel(matcher),
                emptyText = stringResource(R.string.routing_rule_sets_empty),
                choices = ruleSetChoices,
                selected = values.toSet(),
                onToggle = { tag ->
                    val nextValues = if (tag in values) values - tag else values + tag
                    onRuleChange(rule.withDnsRuleMatchValues(matcher, nextValues))
                },
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            onPendingChange(false)
        }
        "match_response" -> {
            val selectedIndex = matchState
                ?.values
                ?.singleOrNull()
                ?.let { value ->
                    matchResponseChoices.indexOfFirst { choice ->
                        choice.first.value == value
                    }
                }
                ?.takeIf { index -> index >= 0 }
                ?.plus(1)
                ?: 0
            WindowDropdownPreference(
                title = dnsRuleMatcherLabel(matcher),
                icon = Icons.Rounded.Policy,
                items = listOf(
                    stringResource(R.string.settings_dns_match_response_unavailable),
                ) + matchResponseChoices.map { choice -> choice.second },
                selectedIndex = selectedIndex,
                onSelectedIndexChange = { index ->
                    val nextValues = matchResponseChoices
                        .getOrNull(index - 1)
                        ?.first
                        ?.value
                        ?.let(::listOf)
                        .orEmpty()
                    onRuleChange(
                        rule.withDnsRuleMatchValues(
                            matcher,
                            nextValues,
                            encodeAsString = true,
                        ),
                    )
                },
            )
            onPendingChange(false)
        }
        else -> {
            StringListEditor(
                editorKey = "dns-rule-match:${rule.id}:$matcher",
                title = dnsRuleMatcherLabel(matcher),
                description = if (matcher in DnsAddressMapMatchers) {
                    stringResource(R.string.settings_dns_rule_map_values_summary)
                } else {
                    null
                },
                values = values,
                onValuesChange = { nextValues ->
                    onRuleChange(rule.withDnsRuleMatchValues(matcher, nextValues))
                },
                emptyText = stringResource(R.string.settings_dns_rule_values_empty),
                validateInput = { value ->
                    dnsRuleValueError(matcher, value, invalidMessage)
                },
                onPendingChange = onPendingChange,
            )
        }
    }
}

@Composable
private fun DnsRuleEditorSectionTitle(title: String) {
    Text(
        text = title,
        color = MaterialTheme.colorScheme.primary,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 10.dp),
    )
}

@StringRes
private fun dnsRuleMatcherSectionTitleResource(index: Int): Int = when (index) {
    0 -> R.string.routing_section_network
    1 -> R.string.routing_section_destination
    2 -> R.string.routing_section_source
    3 -> R.string.settings_dns_rule_section_process
    4 -> R.string.settings_dns_rule_section_interface
    5 -> R.string.settings_dns_rule_section_response
    else -> R.string.settings_dns_rule_match
}

@Composable
private fun DnsRuleRouteOptions(
    rule: SingBoxDnsRuleState,
    onRuleChange: (SingBoxDnsRuleState) -> Unit,
    timeoutError: String?,
    ttlError: String?,
    subnetError: String?,
) {
    SwitchPreference(
        title = stringResource(R.string.settings_dns_disable_cache),
        icon = Icons.Rounded.Storage,
        checked = rule.disableCache,
        onCheckedChange = { onRuleChange(rule.copy(disableCache = it)) },
    )
    SettingsTextField(
        value = rule.rewriteTtl,
        onValueChange = { onRuleChange(rule.copy(rewriteTtl = it.filter(Char::isDigit))) },
        label = stringResource(R.string.settings_dns_rewrite_ttl),
        errorText = ttlError,
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Number,
            imeAction = ImeAction.Next,
        ),
    )
    SettingsTextField(
        value = rule.timeout,
        onValueChange = { onRuleChange(rule.copy(timeout = it)) },
        label = stringResource(R.string.settings_dns_rule_timeout),
        errorText = timeoutError,
    )
    SettingsTextField(
        value = rule.clientSubnet,
        onValueChange = { onRuleChange(rule.copy(clientSubnet = it)) },
        label = stringResource(R.string.settings_dns_client_subnet),
        errorText = subnetError,
    )
}

@Composable
private fun DnsRecordListEditor(
    editorKey: Any,
    title: String,
    values: List<String>,
    onValuesChange: (List<String>) -> Unit,
) {
    StringListEditor(
        editorKey = editorKey,
        title = title,
        values = values,
        onValuesChange = onValuesChange,
        emptyText = stringResource(R.string.settings_dns_list_empty),
    )
}

@Composable
private fun DnsSheetSection(
    title: String,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp),
    ) {
        Text(
            text = title,
            color = MaterialTheme.colorScheme.primary,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        content()
    }
}

@Composable
private fun DnsObjectList(
    count: Int,
    emptyText: String,
    onAdd: () -> Unit,
    errorText: String? = null,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        shape = MaterialTheme.shapes.large,
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AsteriskInfoChip(text = count.toString())
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onAdd) {
                    Icon(Icons.Rounded.Add, stringResource(R.string.common_add))
                }
            }
            if (count == 0) {
                Text(
                    text = emptyText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 12.dp),
                )
            }
            errorText?.let { error ->
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                )
            }
            content()
        }
    }
}

@Composable
private fun DnsObjectRow(
    title: String,
    summary: String,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        shape = AsteriskShapeTokens.InnerContainer,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .padding(start = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = summary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.75f),
                )
            }
            Spacer(Modifier.width(4.dp))
            IconButton(onClick = onEdit, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Rounded.Edit, stringResource(R.string.common_edit))
            }
            IconButton(onClick = onDelete, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Rounded.Delete, stringResource(R.string.common_delete))
            }
        }
    }
}

internal fun DnsSettingsDraft.sanitized(): DnsSettingsDraft =
    copy(
        dnsFinal = dnsFinal.trim(),
        routeDefaultDomainResolver = routeDefaultDomainResolver.trim(),
        dnsCacheCapacity = dnsCacheCapacity.trim(),
        dnsDisableCache = dnsDisableCache && !dnsOptimisticCache,
        dnsDisableExpire = dnsDisableExpire && !dnsOptimisticCache,
        dnsTimeout = dnsTimeout.trim().ifBlank { DefaultSingBoxDnsTimeout },
        dnsServers = dnsServers.map(SingBoxDnsServerState::sanitized),
    )

@Composable
private fun dnsServerSummary(
    server: SingBoxDnsServerState,
    endpointLabels: Map<String, String>,
    unavailableLabel: String,
): String {
    val type = dnsServerTypeLabel(server.type)
    val detail = when (server.type) {
        in NetworkDnsServerTypes -> server.server.takeIf(String::isNotBlank)?.let { address ->
            server.serverPort.takeIf(String::isNotBlank)?.let { port ->
                stringResource(R.string.settings_dns_host_port, address, port)
            } ?: address
        }
        "fakeip" -> server.inet4Range.ifBlank { DefaultSingBoxDnsFakeIpRange }
        in EndpointDnsServerTypes ->
            visibleManagedReference(server.endpoint, endpointLabels, unavailableLabel)
        "resolved" -> server.service
        else -> null
    }
    return detail?.takeIf(String::isNotBlank)?.let {
        stringResource(R.string.settings_dns_summary_pair, type, it)
    } ?: type
}

@Composable
internal fun dnsRuleSummary(rule: SingBoxDnsRuleState): String {
    val matches = rule.matches
        .take(2)
        .map { condition ->
            stringResource(
                R.string.settings_dns_rule_match_summary,
                dnsRuleMatcherLabel(condition.field),
                condition.values.take(2).joinToString(", "),
            )
        }
    val match = when (matches.size) {
        0 -> stringResource(R.string.settings_dns_rule_match_all)
        1 -> matches.single()
        else -> stringResource(R.string.settings_dns_rule_match_pair, matches[0], matches[1])
    }
    val target = rule.server.takeIf {
        it.isNotBlank() && (rule.action == "route" || rule.action == "evaluate")
    }
    return target?.let {
        stringResource(R.string.settings_dns_summary_pair, match, it)
    } ?: match
}

@Composable
internal fun dnsServerTypeLabel(type: String): String =
    singBoxOptionLabel(stringResource(dnsServerTypeLabelResource(type)), type)

@StringRes
private fun dnsServerTypeLabelResource(type: String): Int = when (type) {
    "local" -> R.string.settings_dns_server_type_local
    "hosts" -> R.string.settings_dns_server_type_hosts
    "udp" -> R.string.settings_dns_server_type_udp
    "tcp" -> R.string.settings_dns_server_type_tcp
    "tls" -> R.string.settings_dns_server_type_tls
    "quic" -> R.string.settings_dns_server_type_quic
    "https" -> R.string.settings_dns_server_type_https
    "h3" -> R.string.settings_dns_server_type_h3
    "dhcp" -> R.string.settings_dns_server_type_dhcp
    "mdns" -> R.string.settings_dns_server_type_mdns
    "fakeip" -> R.string.settings_dns_server_type_fakeip
    "tailscale" -> R.string.settings_dns_server_type_tailscale
    "openconnect" -> R.string.settings_dns_server_type_openconnect
    "openvpn" -> R.string.settings_dns_server_type_openvpn
    "resolved" -> R.string.settings_dns_server_type_resolved
    else -> R.string.common_unknown
}

@Composable
internal fun dnsRuleActionLabel(action: String): String =
    singBoxOptionLabel(stringResource(dnsRuleActionLabelResource(action)), action)

@StringRes
private fun dnsRuleActionLabelResource(action: String): Int = when (action) {
    "route" -> R.string.settings_dns_action_route
    "evaluate" -> R.string.settings_dns_action_evaluate
    "respond" -> R.string.settings_dns_action_respond
    "route-options" -> R.string.settings_dns_action_route_options
    "reject" -> R.string.settings_dns_action_reject
    "predefined" -> R.string.settings_dns_action_predefined
    else -> R.string.common_unknown
}

@Composable
private fun dnsRejectMethodLabel(method: String): String =
    singBoxOptionLabel(
        stringResource(
            when (method) {
                "default" -> R.string.settings_dns_reject_method_default
                "drop" -> R.string.settings_dns_reject_method_drop
                else -> R.string.common_unknown
            },
        ),
        method,
    )

@Composable
private fun dnsRuleMatcherLabel(field: String): String =
    singBoxOptionLabel(stringResource(dnsRuleMatcherLabelResource(field)), field)

@StringRes
private fun dnsRuleMatcherLabelResource(field: String): Int = when (field) {
    "ip_version" -> R.string.settings_dns_ip_version
    "network" -> R.string.settings_dns_network
    "domain" -> R.string.settings_dns_matcher_domain
    "domain_suffix" -> R.string.settings_dns_matcher_domain_suffix
    "domain_keyword" -> R.string.settings_dns_matcher_domain_keyword
    "domain_regex" -> R.string.settings_dns_matcher_domain_regex
    "rule_set" -> R.string.settings_dns_matcher_rule_set
    "query_type" -> R.string.settings_dns_matcher_query_type
    "inbound" -> R.string.settings_dns_matcher_inbound
    "auth_user" -> R.string.settings_dns_matcher_auth_user
    "protocol" -> R.string.settings_dns_matcher_protocol
    "source_ip_cidr" -> R.string.settings_dns_matcher_source_ip_cidr
    "source_port" -> R.string.settings_dns_matcher_source_port
    "source_port_range" -> R.string.settings_dns_matcher_source_port_range
    "port" -> R.string.settings_dns_matcher_port
    "port_range" -> R.string.settings_dns_matcher_port_range
    "process_name" -> R.string.settings_dns_matcher_process_name
    "process_path" -> R.string.settings_dns_matcher_process_path
    "process_path_regex" -> R.string.settings_dns_matcher_process_path_regex
    "package_name" -> R.string.settings_dns_matcher_package_name
    "package_name_regex" -> R.string.settings_dns_matcher_package_name_regex
    "clash_mode" -> R.string.settings_dns_matcher_clash_mode
    "network_type" -> R.string.settings_dns_matcher_network_type
    "interface_address" -> R.string.settings_dns_matcher_interface_address
    "network_interface_address" -> R.string.settings_dns_matcher_network_interface_address
    "default_interface_address" -> R.string.settings_dns_matcher_default_interface_address
    "source_mac_address" -> R.string.settings_dns_matcher_source_mac_address
    "source_hostname" -> R.string.settings_dns_matcher_source_hostname
    "preferred_by" -> R.string.settings_dns_matcher_preferred_by
    "wifi_ssid" -> R.string.settings_dns_matcher_wifi_ssid
    "wifi_bssid" -> R.string.settings_dns_matcher_wifi_bssid
    "match_response" -> R.string.settings_dns_matcher_match_response
    "response_rcode" -> R.string.settings_dns_matcher_response_rcode
    "response_answer" -> R.string.settings_dns_matcher_response_answer
    "response_ns" -> R.string.settings_dns_matcher_response_ns
    "response_extra" -> R.string.settings_dns_matcher_response_extra
    else -> R.string.common_unknown
}

internal fun dnsDurationError(value: String, invalidMessage: String): String? {
    val normalized = value.trim()
    if (normalized.isEmpty()) return invalidMessage
    return if (isNonNegativeSingBoxDuration(normalized)) null else invalidMessage
}

private fun dnsPredefinedHostError(input: String, invalidMessage: String): String? {
    val separator = input.indexOf('=')
    if (separator <= 0 || separator >= input.lastIndex) return invalidMessage
    val domain = input.substring(0, separator).trim()
    val addresses = input.substring(separator + 1).split(',').map(String::trim)
    return if (domain.isBlank() || addresses.any { address -> !isIpAddress(address) }) invalidMessage else null
}

internal fun dnsRuleValueError(
    matcher: String,
    input: String,
    invalidMessage: String,
): String? {
    val value = input.trim()
    if (value.isEmpty()) return invalidMessage
    return when (matcher) {
        "source_ip_cidr" ->
            if (isCidrAddress(value)) null else invalidMessage
        "default_interface_address" ->
            if (isIpAddress(value) || isCidrAddress(value)) null else invalidMessage
        in DnsAddressMapMatchers -> {
            val separator = value.indexOf('=')
            val name = value.substring(0, separator.coerceAtLeast(0)).trim()
            val addresses = if (separator in 1..<value.lastIndex) {
                value.substring(separator + 1).split(',').map(String::trim)
            } else {
                emptyList()
            }
            if (
                name.isNotEmpty() &&
                addresses.isNotEmpty() &&
                addresses.all { address ->
                    isIpAddress(address) || isCidrAddress(address)
                }
            ) {
                null
            } else {
                invalidMessage
            }
        }
        "source_port", "port" ->
            if (isSingBoxUnsigned16(value)) null else invalidMessage
        "query_type" ->
            if (isSingBoxDnsQueryType(value)) null else invalidMessage
        "response_rcode" ->
            if (isSingBoxDnsRCode(value)) null else invalidMessage
        "match_response" -> null
        "source_port_range", "port_range" ->
            if (isSingBoxPortRange(value)) null else invalidMessage
        "domain_regex", "process_path_regex", "package_name_regex" ->
            if (runCatching { Regex(value) }.isSuccess) null else invalidMessage
        else -> null
    }
}

private val DnsAddressMapMatchers = setOf("interface_address", "network_interface_address")
