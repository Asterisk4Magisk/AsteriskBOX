// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.importing

internal interface ImportFormat {
    val id: String
}

internal enum class ImportIssueReason {
    EMPTY_INPUT,
    INPUT_TOO_LARGE,
    TOO_MANY_CANDIDATES,
    INVALID_DOCUMENT,
    INVALID_ENTRY,
    INVALID_FIELD,
    UNSUPPORTED_TYPE,
    UNSUPPORTED_OPTION,
    UNSAFE_EXTERNAL_REFERENCE,
    NO_SUPPORTED_ITEMS,
    VALIDATION_FAILED,
    STATE_CHANGED,
    NETWORK_ERROR,
    PROXY_UNAVAILABLE,
    STRICT_MODE_REJECTED,
}

internal enum class ImportIssueSeverity {
    WARNING,
    ERROR,
}

internal data class ImportIssue(
    val reason: ImportIssueReason,
    val severity: ImportIssueSeverity,
    val stage: ImportStage,
    val sourceIndex: Int? = null,
    val detectedType: String? = null,
    val message: String,
)

internal enum class ImportMutationCode {
    DUPLICATE_SKIPPED,
    IGNORED_FIELD,
    IGNORED_SECTION,
    REMOVED_DETOUR,
    REMOVED_DOMAIN_RESOLVER,
    DEFAULT_APPLIED,
}

internal data class ImportMutation(
    val code: ImportMutationCode,
    val sourceIndex: Int? = null,
    val message: String,
)

internal class ImportOutcome<T>(
    val format: ImportFormat,
    val detectedCount: Int,
    accepted: List<T>,
    val duplicateCount: Int = 0,
    issues: List<ImportIssue> = emptyList(),
    mutations: List<ImportMutation> = emptyList(),
    priorOmittedDetailCount: Int = 0,
) {
    val accepted: List<T> = accepted.toList()
    val issues: List<ImportIssue> = issues.take(MaxTransientImportDetails)
    val mutations: List<ImportMutation> = mutations.take(
        (MaxTransientImportDetails - this.issues.size).coerceAtLeast(0),
    )
    val omittedDetailCount: Int =
        priorOmittedDetailCount +
            issues.size + mutations.size - this.issues.size - this.mutations.size

    val skippedCount: Int = detectedCount - this.accepted.size - duplicateCount
    val hasEntryErrors: Boolean = skippedCount > 0
    val isCleanSuccess: Boolean =
        this.accepted.isNotEmpty() &&
            skippedCount == 0 &&
            duplicateCount == 0 &&
            this.issues.isEmpty() &&
            this.mutations.isEmpty()

    init {
        require(detectedCount >= 0) { "Detected candidate count must not be negative" }
        require(duplicateCount >= 0) { "Duplicate candidate count must not be negative" }
        require(priorOmittedDetailCount >= 0) {
            "Prior omitted detail count must not be negative"
        }
        require(skippedCount >= 0) {
            "Detected candidate count must include accepted and duplicate candidates"
        }
    }
}

internal const val MaxTransientImportDetails = 100
