// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package app

import androidx.compose.runtime.staticCompositionLocalOf
import android.net.Uri
import data.backup.AppBackupUseCase
import engine.proxy.AndroidProxyEngine
import engine.proxy.ProxyServiceUseCase
import engine.singbox.runtime.SingBoxRuntimeRepository
import features.logs.CoreLogRepository
import features.monitoring.MonitoringRepository
import features.resources.ResourceFileUpdateCoordinator
import features.resources.ResourceFileUseCase
import features.settings.usecase.SwitchRunModeUseCase
import features.settings.usecase.ApplyServiceControlUseCase
import features.settings.usecase.RootBootScriptUseCase
import features.settings.usecase.RootEbpfProbeUseCase
import features.subscription.usecase.OutboundSubscriptionUpdater
import kotlinx.coroutines.CoroutineScope
import system.AndroidNetworkInterfaceProvider
import system.AndroidPackageProvider
import system.AndroidRootShellGateway
import system.AndroidUserSpaceProvider
import ui.feedback.AndroidToastTipNotifier

internal data class AppServices(
    val appScope: CoroutineScope,
    val proxyEngine: AndroidProxyEngine,
    val rootAccess: AndroidRootShellGateway,
    val userSpaces: AndroidUserSpaceProvider,
    val packageCatalog: AndroidPackageProvider,
    val networkInterfaces: AndroidNetworkInterfaceProvider,
    val resourceFileUseCase: ResourceFileUseCase,
    val resourceFileUpdateCoordinator: ResourceFileUpdateCoordinator,
    val appBackupUseCase: AppBackupUseCase,
    val outboundSubscriptionUpdater: OutboundSubscriptionUpdater,
    val qrCodeScanner: suspend () -> String?,
    val importFilePicker: suspend () -> Uri?,
    val singBoxRuntime: SingBoxRuntimeRepository,
    val monitoring: MonitoringRepository,
    val proxyServiceUseCase: ProxyServiceUseCase,
    val switchRunModeUseCase: SwitchRunModeUseCase,
    val applyServiceControlUseCase: ApplyServiceControlUseCase,
    val rootBootScriptUseCase: RootBootScriptUseCase,
    val rootEbpfProbeUseCase: RootEbpfProbeUseCase,
    val tipNotifier: AndroidToastTipNotifier,
    val logFileCreator: suspend (String) -> Uri?,
    val coreLogRepository: CoreLogRepository,
    val rootLogRepository: CoreLogRepository,
    val logcatRepository: CoreLogRepository,
)

internal val LocalAppServices = staticCompositionLocalOf<AppServices> {
    error("LocalAppServices is not provided")
}
