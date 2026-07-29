// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.importing

import features.logs.AndroidAppLogger
import features.logs.FailureLogContext
import features.logs.reportFailure

internal enum class ImportOperation(internal val logValue: String) {
    ENDPOINT("endpoint"),
    OUTBOUND("outbound"),
    OUTBOUND_SUBSCRIPTION("outbound_subscription"),
}

internal enum class ImportSource(internal val logValue: String) {
    QR_CODE("qr_code"),
    CLIPBOARD("clipboard"),
    FILE("file"),
    SUBSCRIPTION("subscription"),
}

internal enum class ImportStage(internal val logValue: String) {
    READ("read"),
    DOWNLOAD("download"),
    DECRYPT("decrypt"),
    VERIFY("verify"),
    PARSE("parse"),
    VALIDATE("validate"),
    COMMIT("commit"),
}

internal fun reportImportFailure(
    operation: ImportOperation,
    source: ImportSource,
    stage: ImportStage,
    error: Throwable? = null,
    logger: (tag: String, message: String, error: Throwable?) -> Unit =
        { tag, message, cause -> AndroidAppLogger.error(tag, message, cause) },
) {
    reportFailure(
        context = importFailureContext(operation, source, stage),
        error = error?.toSanitizedImportFailure(),
        logger = logger,
    )
}

private fun Throwable.toSanitizedImportFailure(): Throwable {
    val sanitized = IllegalStateException(
        sanitizeImportMessage(message ?: this::class.simpleName ?: "Import failed"),
    )
    sanitized.stackTrace = stackTrace
    return sanitized
}

internal fun importFailureContext(
    operation: ImportOperation,
    source: ImportSource,
    stage: ImportStage,
): FailureLogContext = FailureLogContext(
    operation = operation.logValue,
    source = source.logValue,
    stage = stage.logValue,
    logTag = "Import",
)
