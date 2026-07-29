// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.importing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.asterisk.zcc.abox.R

internal enum class ImportResultStatus {
    CLEAN_SUCCESS,
    PARTIAL_SUCCESS,
    SUCCESS_WITH_DETAILS,
    DUPLICATES_ONLY,
    FAILURE,
}

internal data class ImportResultDetail(
    val sourceIndex: Int?,
    val message: String,
    val isError: Boolean,
)

internal data class ImportResultPresentation(
    val status: ImportResultStatus,
    val formatId: String,
    val detectedCount: Int,
    val acceptedCount: Int,
    val skippedCount: Int,
    val duplicateCount: Int,
    val details: List<ImportResultDetail>,
    val omittedDetailCount: Int,
) {
    val showDialog: Boolean
        get() = status != ImportResultStatus.CLEAN_SUCCESS
}

internal fun <T> ImportOutcome<T>.toImportResultPresentation(
    committed: Boolean,
): ImportResultPresentation {
    val status = when {
        committed && isCleanSuccess -> ImportResultStatus.CLEAN_SUCCESS
        committed && skippedCount > 0 -> ImportResultStatus.PARTIAL_SUCCESS
        committed -> ImportResultStatus.SUCCESS_WITH_DETAILS
        accepted.isEmpty() && skippedCount == 0 && duplicateCount > 0 ->
            ImportResultStatus.DUPLICATES_ONLY
        else -> ImportResultStatus.FAILURE
    }
    val issueDetails = issues.map { issue ->
        ImportResultDetail(
            sourceIndex = issue.sourceIndex,
            message = sanitizeImportMessage(issue.message),
            isError = issue.severity == ImportIssueSeverity.ERROR,
        )
    }
    val mutationDetails = mutations.map { mutation ->
        ImportResultDetail(
            sourceIndex = mutation.sourceIndex,
            message = sanitizeImportMessage(mutation.message),
            isError = false,
        )
    }
    return ImportResultPresentation(
        status = status,
        formatId = format.id,
        detectedCount = detectedCount,
        acceptedCount = if (committed) accepted.size else 0,
        skippedCount = skippedCount,
        duplicateCount = duplicateCount,
        details = issueDetails + mutationDetails,
        omittedDetailCount = omittedDetailCount,
    )
}

internal fun importFailureResultPresentation(
    message: String,
): ImportResultPresentation = ImportResultPresentation(
    status = ImportResultStatus.FAILURE,
    formatId = "unknown",
    detectedCount = 0,
    acceptedCount = 0,
    skippedCount = 0,
    duplicateCount = 0,
    details = listOf(
        ImportResultDetail(
            sourceIndex = null,
            message = sanitizeImportMessage(message),
            isError = true,
        ),
    ),
    omittedDetailCount = 0,
)

@Composable
internal fun ImportResultDialog(
    presentation: ImportResultPresentation,
    onDismissRequest: () -> Unit,
) {
    val title = stringResource(
        when (presentation.status) {
            ImportResultStatus.CLEAN_SUCCESS -> R.string.import_result_success_title
            ImportResultStatus.PARTIAL_SUCCESS -> R.string.import_result_partial_title
            ImportResultStatus.SUCCESS_WITH_DETAILS -> R.string.import_result_details_title
            ImportResultStatus.DUPLICATES_ONLY -> R.string.import_result_duplicates_title
            ImportResultStatus.FAILURE -> R.string.import_result_failure_title
        },
    )
    val summary = stringResource(
        R.string.import_result_summary,
        presentation.acceptedCount,
        presentation.skippedCount,
        presentation.duplicateCount,
    )
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(title) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = androidx.compose.ui.Modifier.verticalScroll(rememberScrollState()),
            ) {
                Text(summary)
                presentation.details.forEach { detail ->
                    val prefix = detail.sourceIndex?.let { index ->
                        stringResource(R.string.import_result_entry_prefix, index + 1)
                    }.orEmpty()
                    Text("$prefix${detail.message}")
                }
                if (presentation.omittedDetailCount > 0) {
                    Text(
                        pluralStringResource(
                            R.plurals.import_result_omitted_details,
                            presentation.omittedDetailCount,
                            presentation.omittedDetailCount,
                        ),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismissRequest) {
                Text(stringResource(R.string.common_close))
            }
        },
    )
}
