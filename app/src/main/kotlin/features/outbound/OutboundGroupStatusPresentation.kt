// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.outbound

import app.OutboundGroupState
import app.OutboundGroupUpdateStatus
import features.importing.sanitizePersistedImportSummary

internal data class OutboundGroupStatusPresentation(
    val status: OutboundGroupUpdateStatus,
    val line: OutboundGroupStatusLinePresentation?,
    val summary: String,
)

internal enum class OutboundGroupStatusLineKind {
    UPDATED,
    PARTIALLY_UPDATED,
    NOT_MODIFIED,
    FAILED,
}

internal data class OutboundGroupStatusLinePresentation(
    val kind: OutboundGroupStatusLineKind,
    val timestampMillis: Long,
    val importedCount: Int,
    val skippedCount: Int,
    val duplicateCount: Int,
)

internal fun OutboundGroupState.subscriptionStatusPresentation() =
    OutboundGroupStatusPresentation(
        status = lastUpdateStatus,
        line = subscriptionStatusLinePresentation(),
        summary = sanitizePersistedImportSummary(lastUpdateErrorSummary),
    )

private fun OutboundGroupState.subscriptionStatusLinePresentation():
    OutboundGroupStatusLinePresentation? {
    val (kind, timestampMillis) = when (lastUpdateStatus) {
        OutboundGroupUpdateStatus.NEVER -> return null
        OutboundGroupUpdateStatus.SUCCESS ->
            OutboundGroupStatusLineKind.UPDATED to lastUpdatedAtMillis
        OutboundGroupUpdateStatus.PARTIAL ->
            OutboundGroupStatusLineKind.PARTIALLY_UPDATED to lastUpdatedAtMillis
        OutboundGroupUpdateStatus.NOT_MODIFIED ->
            OutboundGroupStatusLineKind.NOT_MODIFIED to lastUpdateAttemptAtMillis
        OutboundGroupUpdateStatus.FAILED ->
            OutboundGroupStatusLineKind.FAILED to lastUpdateAttemptAtMillis
    }
    if (timestampMillis <= 0L) return null
    val includesImportCounts = kind == OutboundGroupStatusLineKind.UPDATED ||
        kind == OutboundGroupStatusLineKind.PARTIALLY_UPDATED
    return OutboundGroupStatusLinePresentation(
        kind = kind,
        timestampMillis = timestampMillis,
        importedCount = if (includesImportCounts) lastUpdateImportedCount else 0,
        skippedCount = if (includesImportCounts) lastUpdateSkippedCount else 0,
        duplicateCount = if (includesImportCounts) lastUpdateDuplicateCount else 0,
    )
}
