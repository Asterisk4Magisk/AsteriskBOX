// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import com.journeyapps.barcodescanner.ScanContract
import data.AppSettingsPreferences
import engine.vpn.AndroidVpnPermissionRequester
import features.logs.AndroidLogFileCreator
import features.resources.runtime.AndroidResourceFilePicker
import features.settings.locale.localizedAppContext
import features.singbox.qr.AndroidQrCodeScanRequester
import features.importing.ImportOperation
import features.importing.ImportSource
import features.importing.ImportStage
import features.importing.reportImportFailure
import features.subscription.parseSubscriptionDeepLink
import features.subscription.usecase.OutboundSubscriptionUpdateResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import ui.feedback.AndroidToastTipNotifier

class MainActivity : ComponentActivity() {
    private val vpnPermissionRequester = AndroidVpnPermissionRequester {
        getString(R.string.error_vpn_permission_launcher_missing)
    }

    private val qrCodeScanRequester = AndroidQrCodeScanRequester(
        hasCameraPermission = {
            checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        },
        permissionDeniedMessage = {
            getString(R.string.error_qr_camera_permission_denied)
        },
        missingLauncherMessage = {
            getString(R.string.error_qr_scan_launcher_missing)
        },
    )

    private val resourceFilePicker = AndroidResourceFilePicker(
        missingLauncherMessage = {
            getString(R.string.error_resource_file_picker_missing)
        },
    )

    private val logFileCreator = AndroidLogFileCreator(
        missingLauncherMessage = {
            getString(R.string.error_log_export_launcher_missing)
        },
    )
    private val backupFileCreator = AndroidLogFileCreator(
        missingLauncherMessage = {
            getString(R.string.error_backup_file_creator_missing)
        },
    )
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {}

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        vpnPermissionRequester.complete(result.resultCode == RESULT_OK)
    }

    private val qrCodePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        qrCodeScanRequester.completeCameraPermission(granted)
    }

    private val qrCodeScanLauncher = registerForActivityResult(ScanContract()) { result ->
        qrCodeScanRequester.completeScan(result.contents)
    }

    private val resourceFilePickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        resourceFilePicker.complete(uri)
    }

    private val logFileCreatorLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("*/*"),
    ) { uri ->
        logFileCreator.complete(uri)
    }

    private val backupFileCreatorLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        backupFileCreator.complete(uri)
    }

    override fun attachBaseContext(newBase: Context) {
        val settings = AppSettingsPreferences(newBase).load()
        super.attachBaseContext(
            newBase.localizedAppContext(settings.languageMode, settings.colorMode),
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        vpnPermissionRequester.registerLauncher { intent ->
            vpnPermissionLauncher.launch(intent)
        }
        qrCodeScanRequester.registerPermissionLauncher { permission ->
            qrCodePermissionLauncher.launch(permission)
        }
        qrCodeScanRequester.registerScanLauncher { options ->
            qrCodeScanLauncher.launch(options)
        }
        resourceFilePicker.registerLauncher { mimeTypes ->
            resourceFilePickerLauncher.launch(mimeTypes)
        }
        logFileCreator.registerLauncher { fileName ->
            logFileCreatorLauncher.launch(fileName)
        }
        backupFileCreator.registerLauncher { fileName ->
            backupFileCreatorLauncher.launch(fileName)
        }
        showAppContent()
        requestStartupPermissions()
        if (savedInstanceState == null) handleExternalIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleExternalIntent(intent)
    }

    private fun handleExternalIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        val raw = intent.dataString ?: return
        val application = application as AsteriskApplication
        val notifier = AndroidToastTipNotifier(application)
        val config = parseSubscriptionDeepLink(raw)
        application.appScope.launch {
            if (config == null) {
                notifier.show(getString(R.string.subscription_install_link_invalid))
                return@launch
            }
            try {
                when (val result = application.subscriptionInstallConfigUseCase.install(config)) {
                    is OutboundSubscriptionUpdateResult.Success -> notifier.show(
                        getString(
                            R.string.import_result_summary,
                            result.outcome.accepted.size,
                            result.outcome.skippedCount,
                            result.outcome.duplicateCount,
                        ),
                    )
                    is OutboundSubscriptionUpdateResult.Partial -> notifier.show(
                        getString(R.string.import_result_partial_title) + "\n" +
                            getString(
                                R.string.import_result_summary,
                                result.outcome.accepted.size,
                                result.outcome.skippedCount,
                                result.outcome.duplicateCount,
                            ),
                    )
                    OutboundSubscriptionUpdateResult.NotModified -> notifier.show(
                        getString(R.string.outbound_group_sync_not_modified),
                    )
                    is OutboundSubscriptionUpdateResult.Failed -> {
                        reportImportFailure(
                            ImportOperation.OUTBOUND_SUBSCRIPTION,
                            ImportSource.SUBSCRIPTION,
                            result.stage,
                            result.error,
                        )
                        notifier.show(getString(R.string.subscription_install_failed))
                    }
                    is OutboundSubscriptionUpdateResult.Cancelled -> notifier.show(
                        getString(R.string.subscription_install_failed),
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                reportImportFailure(
                    ImportOperation.OUTBOUND_SUBSCRIPTION,
                    ImportSource.SUBSCRIPTION,
                    ImportStage.COMMIT,
                    error,
                )
                notifier.show(getString(R.string.subscription_install_failed))
            }
        }
    }

    override fun onDestroy() {
        vpnPermissionRequester.complete(false)
        vpnPermissionRequester.registerLauncher(null)
        qrCodeScanRequester.completeCameraPermission(false)
        qrCodeScanRequester.completeScan(null)
        qrCodeScanRequester.registerPermissionLauncher(null)
        qrCodeScanRequester.registerScanLauncher(null)
        resourceFilePicker.complete(null)
        resourceFilePicker.registerLauncher(null)
        logFileCreator.complete(null)
        logFileCreator.registerLauncher(null)
        backupFileCreator.complete(null)
        backupFileCreator.registerLauncher(null)
        super.onDestroy()
    }

    private fun requestStartupPermissions() {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun showAppContent() {
        enableEdgeToEdge()
        setContent {
            App(
                padding = WindowInsets.systemBars.union(WindowInsets.displayCutout).asPaddingValues(),
                qrCodeScanner = qrCodeScanRequester::scan,
                resourceFilePicker = { resourceFilePicker.pick() },
                backupFilePicker = { resourceFilePicker.pick(BackupFileMimeTypes) },
                backupFileCreator = backupFileCreator::create,
                logFileCreator = logFileCreator::create,
                requestVpnPermission = vpnPermissionRequester::request,
            )
        }
    }

}

private val BackupFileMimeTypes = arrayOf(
    "application/json",
    "text/json",
    "text/plain",
)
