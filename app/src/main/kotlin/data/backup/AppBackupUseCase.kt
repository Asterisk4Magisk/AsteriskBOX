// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package data.backup

import app.AppState
import app.ProjectInfo
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

internal interface AppBackupDocumentGateway {
    suspend fun readText(): String?

    suspend fun writeText(
        defaultFileName: String,
        content: String,
    ): Boolean
}

internal class AppBackupUseCase(
    private val gateway: AppBackupDocumentGateway,
    private val clock: () -> Long = System::currentTimeMillis,
    private val appVersionName: String = ProjectInfo.VERSION_NAME,
    private val appVersionCode: Int = ProjectInfo.VERSION_CODE,
    private val fileNameFactory: (Long) -> String = ::defaultBackupFileName,
    private val maxBackupFileBytes: Int = MaxAppBackupFileBytes,
) {
    suspend fun export(state: AppState): Boolean {
        val createdAtMillis = clock()
        val backup = state.toAppBackupFile(
            createdAtMillis = createdAtMillis,
            appVersionName = appVersionName,
            appVersionCode = appVersionCode,
        )
        val content = encodeAppBackup(backup)
        require(content.hasUtf8SizeAtMost(maxBackupFileBytes)) {
            "Backup file exceeds the $maxBackupFileBytes-byte limit"
        }
        return gateway.writeText(
            defaultFileName = fileNameFactory(createdAtMillis),
            content = content,
        )
    }

    suspend fun readRestorePreview(): AppBackupRestorePreview? =
        gateway.readText()?.let { content ->
            decodeAppBackup(content).toRestorePreview()
        }
}

internal fun String.hasUtf8SizeAtMost(maxBytes: Int): Boolean {
    require(maxBytes > 0)
    return length <= maxBytes && toByteArray(Charsets.UTF_8).size <= maxBytes
}

internal fun defaultBackupFileName(
    createdAtMillis: Long,
    timeZone: TimeZone = TimeZone.getDefault(),
): String {
    val formatter = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).apply {
        this.timeZone = timeZone
    }
    return "asteriskbox-backup-${formatter.format(Date(createdAtMillis))}.json"
}
