// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.AppState
import app.LocalAppServices
import features.settings.sheets.ExternalInterfacesBottomSheet
import features.settings.sheets.IgnoredInterfacesBottomSheet
import features.settings.sheets.LocalProxySettingsBottomSheet
import features.settings.sheets.PrivateAddressBottomSheet
import features.settings.sheets.SnifferSettingsBottomSheet
import features.settings.sheets.TunSettingsBottomSheet
import features.settings.sheets.orderedBy
import features.settings.sheets.sanitizeExternalInterfaces
import features.settings.sheets.sanitizePrivateAddressCidrs
import app.modes.RunModeBpf2Socks
import app.modes.RunModeTun2Socks
import app.modes.RunModeTproxy
import app.modes.RunModeVpnService
import engine.singbox.config.validateSingBoxRuntimeConfiguration
import features.logs.FailureLogContext
import features.logs.reportFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.asterisk.zcc.abox.R

@Composable
internal fun SettingsBottomSheetsHost(
    appState: AppState,
    sheetState: SettingsSheetState,
    tunStackOptions: List<String>,
    updateAppState: ((AppState) -> AppState) -> Unit,
) {
    val context = LocalContext.current
    val tipNotifier = LocalAppServices.current.tipNotifier
    val scope = rememberCoroutineScope()
    val validationFailedMessage = stringResource(R.string.settings_sing_box_validation_failed)
    var validating by remember { mutableStateOf(false) }

    fun validateAndCommit(
        operation: String,
        transform: (AppState) -> AppState,
        close: () -> Unit,
    ) {
        if (validating) return
        val baseState = appState
        val candidateState = transform(baseState)
        validating = true
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    validateSingBoxRuntimeConfiguration(context, candidateState)
                }
                var committed = false
                updateAppState { current ->
                    if (current === baseState) {
                        committed = true
                        candidateState
                    } else {
                        current
                    }
                }
                if (committed) {
                    close()
                } else {
                    tipNotifier.show(validationFailedMessage)
                }
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                reportFailure(
                    context = FailureLogContext(
                        operation = operation,
                        stage = "validate",
                    ),
                    error = error,
                )
                tipNotifier.show(validationFailedMessage)
            } finally {
                validating = false
            }
        }
    }

    LocalProxySettingsBottomSheet(
        show = sheetState.showLocalProxySettings,
        saving = validating,
        showInboundProxyPort = appState.runMode == RunModeTproxy ||
            appState.runMode == RunModeTun2Socks ||
            appState.runMode == RunModeBpf2Socks,
        useTun2SocksProxyPort = appState.runMode == RunModeTun2Socks,
        useBpf2SocksProxyPort = appState.runMode == RunModeBpf2Socks,
        lockInboundProxyPort = (appState.runMode == RunModeTproxy ||
            appState.runMode == RunModeTun2Socks ||
            appState.runMode == RunModeBpf2Socks) &&
            appState.proxyRunning,
        inboundProxyPort = if (appState.runMode == RunModeTun2Socks) {
            sheetState.localProxySettingsDraft.socks5ProxyPort
        } else if (appState.runMode == RunModeBpf2Socks) {
            sheetState.localProxySettingsDraft.socks5ProxyPort
        } else {
            sheetState.localProxySettingsDraft.transparentProxyPort
        },
        bpf2SocksBridgePort = sheetState.localProxySettingsDraft.bpf2SocksBridgePort,
        port = sheetState.localProxySettingsDraft.port,
        enableDynamicPort = sheetState.localProxySettingsDraft.enableDynamicPort,
        listenAllInterfaces = sheetState.localProxySettingsDraft.listenAllInterfaces,
        username = sheetState.localProxySettingsDraft.username,
        password = sheetState.localProxySettingsDraft.password,
        onInboundProxyPortChange = {
            sheetState.localProxySettingsDraft = if (appState.runMode == RunModeTun2Socks) {
                sheetState.localProxySettingsDraft.copy(socks5ProxyPort = it)
            } else if (appState.runMode == RunModeBpf2Socks) {
                sheetState.localProxySettingsDraft.copy(socks5ProxyPort = it)
            } else {
                sheetState.localProxySettingsDraft.copy(transparentProxyPort = it)
            }
        },
        onBpf2SocksBridgePortChange = {
            sheetState.localProxySettingsDraft = sheetState.localProxySettingsDraft.copy(bpf2SocksBridgePort = it)
        },
        onPortChange = {
            sheetState.localProxySettingsDraft = sheetState.localProxySettingsDraft.copy(
                port = it,
            )
        },
        onEnableDynamicPortChange = {
            sheetState.localProxySettingsDraft = sheetState.localProxySettingsDraft.copy(enableDynamicPort = it)
        },
        onListenAllInterfacesChange = {
            sheetState.localProxySettingsDraft = sheetState.localProxySettingsDraft.copy(listenAllInterfaces = it)
        },
        onUsernameChange = {
            sheetState.localProxySettingsDraft = sheetState.localProxySettingsDraft.copy(username = it)
        },
        onPasswordChange = {
            sheetState.localProxySettingsDraft = sheetState.localProxySettingsDraft.copy(password = it)
        },
        onDismissRequest = { sheetState.showLocalProxySettings = false },
        onSave = { inboundProxyPort, bpf2SocksBridgePort, port, enableDynamicPort, listenAllInterfaces, username, password ->
            validateAndCommit(
                operation = "save_local_proxy_settings",
                transform = { state ->
                    val lockInboundProxyPort = (state.runMode == RunModeTproxy ||
                        state.runMode == RunModeTun2Socks ||
                        state.runMode == RunModeBpf2Socks) &&
                        state.proxyRunning
                    state.copy(
                        transparentProxyPort = when {
                            lockInboundProxyPort -> state.transparentProxyPort
                            state.runMode == RunModeTproxy -> inboundProxyPort
                            else -> state.transparentProxyPort
                        },
                        socks5ProxyPort = when {
                            lockInboundProxyPort -> state.socks5ProxyPort
                            state.runMode == RunModeTun2Socks ||
                                state.runMode == RunModeBpf2Socks -> inboundProxyPort
                            else -> state.socks5ProxyPort
                        },
                        bpf2SocksBridgePort = when {
                            lockInboundProxyPort -> state.bpf2SocksBridgePort
                            state.runMode == RunModeBpf2Socks -> bpf2SocksBridgePort
                            else -> state.bpf2SocksBridgePort
                        },
                        localProxyPort = port,
                        enableDynamicLocalProxyPort = enableDynamicPort,
                        localProxyListenAllInterfaces = listenAllInterfaces,
                        localProxyUsername = username,
                        localProxyPassword = password,
                    )
                },
                close = { sheetState.showLocalProxySettings = false },
            )
        },
    )
    TunSettingsBottomSheet(
        show = sheetState.showTunSettings,
        saving = validating,
        tunStackOptions = tunStackOptions,
        tunStack = sheetState.tunSettingsDraft.tunStack,
        mtu = sheetState.tunSettingsDraft.mtu,
        vpnDns = sheetState.tunSettingsDraft.vpnDns,
        ipv4Cidr = sheetState.tunSettingsDraft.ipv4Cidr,
        ipv6Cidr = sheetState.tunSettingsDraft.ipv6Cidr,
        showTunStack = appState.runMode != RunModeTun2Socks,
        showVpnDns = appState.runMode == RunModeVpnService,
        onTunStackChange = { sheetState.tunSettingsDraft = sheetState.tunSettingsDraft.copy(tunStack = it) },
        onMtuChange = {
            sheetState.tunSettingsDraft = sheetState.tunSettingsDraft.copy(mtu = it)
        },
        onVpnDnsChange = { sheetState.tunSettingsDraft = sheetState.tunSettingsDraft.copy(vpnDns = it) },
        onIpv4CidrChange = { sheetState.tunSettingsDraft = sheetState.tunSettingsDraft.copy(ipv4Cidr = it) },
        onIpv6CidrChange = { sheetState.tunSettingsDraft = sheetState.tunSettingsDraft.copy(ipv6Cidr = it) },
        onDismissRequest = { sheetState.showTunSettings = false },
        onSave = { tunStack, mtu, vpnDns, ipv4Cidr, ipv6Cidr ->
            validateAndCommit(
                operation = "save_tun_settings",
                transform = { state ->
                    state.copy(
                        singBoxTunStack = if (state.runMode == RunModeTun2Socks) {
                            state.singBoxTunStack
                        } else {
                            tunStack
                        },
                        tunMtu = mtu,
                        tunVpnDns = if (state.runMode == RunModeVpnService) vpnDns else state.tunVpnDns,
                        tunIpv4Cidr = ipv4Cidr,
                        tunIpv6Cidr = ipv6Cidr,
                    )
                },
                close = { sheetState.showTunSettings = false },
            )
        },
    )
    SnifferSettingsBottomSheet(
        show = sheetState.showSnifferSettings,
        saving = validating,
        draft = sheetState.snifferSettingsDraft,
        onDraftChange = { sheetState.snifferSettingsDraft = it },
        onDismissRequest = { sheetState.showSnifferSettings = false },
        onSave = { draft ->
            validateAndCommit(
                operation = "save_sniffer_settings",
                transform = { state ->
                    state.copy(
                        enableSniffer = draft.enableSniffer,
                        snifferProtocols = draft.snifferProtocols,
                        snifferTimeout = draft.snifferTimeout,
                    )
                },
                close = { sheetState.showSnifferSettings = false },
            )
        },
    )
    ExternalInterfacesBottomSheet(
        show = sheetState.showExternalInterfaces,
        selectedInterfaces = sheetState.externalInterfacesDraft,
        onSelectedInterfacesChange = { sheetState.externalInterfacesDraft = it.sanitizeExternalInterfaces() },
        onDismissRequest = { sheetState.showExternalInterfaces = false },
        onSave = { interfaces ->
            updateAppState { state -> state.copy(externalInterfaces = interfaces.sanitizeExternalInterfaces()) }
            sheetState.showExternalInterfaces = false
        },
    )
    IgnoredInterfacesBottomSheet(
        show = sheetState.showIgnoredInterfaces,
        interfaces = sheetState.ignoredInterfaceOptions,
        selectedInterfaces = sheetState.ignoredInterfacesDraft,
        loading = sheetState.ignoredInterfacesLoading,
        errorMessage = sheetState.ignoredInterfacesError,
        onSelectedInterfacesChange = {
            sheetState.ignoredInterfacesDraft = it.orderedBy(sheetState.ignoredInterfaceOptions)
        },
        onDismissRequest = { sheetState.closeIgnoredInterfaces() },
        onSave = { interfaces ->
            updateAppState { state ->
                state.copy(ignoredInterfaces = interfaces.orderedBy(sheetState.ignoredInterfaceOptions))
            }
            sheetState.closeIgnoredInterfaces()
        },
    )
    PrivateAddressBottomSheet(
        show = sheetState.showPrivateAddresses,
        selectedCidrs = sheetState.privateAddressCidrsDraft,
        onSelectedCidrsChange = { sheetState.privateAddressCidrsDraft = it.sanitizePrivateAddressCidrs() },
        onDismissRequest = { sheetState.showPrivateAddresses = false },
        onSave = { cidrs ->
            updateAppState { state -> state.copy(privateAddressCidrs = cidrs.sanitizePrivateAddressCidrs()) }
            sheetState.showPrivateAddresses = false
        },
    )
}
