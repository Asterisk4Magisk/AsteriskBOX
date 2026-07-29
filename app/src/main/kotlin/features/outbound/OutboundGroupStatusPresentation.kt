// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.outbound

import app.OutboundGroupState
import app.OutboundGroupUpdateStatus
import features.importing.sanitizePersistedImportSummary

internal data class OutboundGroupStatusPresentation(
    val status: OutboundGroupUpdateStatus,
    val attemptAtMillis: Long,
    val importedCount: Int,
    val skippedCount: Int,
    val duplicateCount: Int,
    val summary: String,
)

internal fun OutboundGroupState.subscriptionStatusPresentation() =
    OutboundGroupStatusPresentation(
        status = lastUpdateStatus,
        attemptAtMillis = lastUpdateAttemptAtMillis,
        importedCount = lastUpdateImportedCount,
        skippedCount = lastUpdateSkippedCount,
        duplicateCount = lastUpdateDuplicateCount,
        summary = sanitizePersistedImportSummary(lastUpdateErrorSummary),
    )
