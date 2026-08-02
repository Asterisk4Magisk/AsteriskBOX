// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.resources.runtime

import kotlin.coroutines.cancellation.CancellationException

internal data class ResourceFileBatchFailure<T>(
    val target: T,
    val error: Throwable,
)

internal data class ResourceFileBatchRunResult<T>(
    val succeeded: List<T>,
    val failures: List<ResourceFileBatchFailure<T>>,
)

internal data class ResourceFileBatchDownloadFailure(
    val fileName: String,
    val error: Throwable,
)

internal class ResourceFileBatchDownloadFailedException(
    val succeededFileNames: List<String>,
    val failures: List<ResourceFileBatchDownloadFailure>,
) : RuntimeException(
    "${failures.size} resource file downloads failed",
    failures.firstOrNull()?.error,
)

internal fun <T> runResourceFileBatch(
    targets: List<T>,
    execute: (T) -> Unit,
): ResourceFileBatchRunResult<T> {
    val succeeded = mutableListOf<T>()
    val failures = mutableListOf<ResourceFileBatchFailure<T>>()
    targets.forEach { target ->
        try {
            execute(target)
            succeeded += target
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            if (error is AndroidResourceFileDownloadCancelledException) throw error
            failures += ResourceFileBatchFailure(target, error)
        }
    }
    return ResourceFileBatchRunResult(
        succeeded = succeeded,
        failures = failures,
    )
}
