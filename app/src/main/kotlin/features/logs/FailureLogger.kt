// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.logs

internal data class FailureLogContext(
    val operation: String,
    val source: String? = null,
    val stage: String? = null,
    val logTag: String = DefaultFailureLogTag,
) {
    init {
        require(operation.isLogToken()) { "Failure operation must be a log token" }
        require(source == null || source.isLogToken()) { "Failure source must be a log token" }
        require(stage == null || stage.isLogToken()) { "Failure stage must be a log token" }
        require(logTag.isNotBlank() && logTag.all(Char::isLetterOrDigit)) {
            "Failure log tag must be alphanumeric"
        }
    }

    internal fun message(): String = buildString {
        append("operation=")
        append(operation)
        source?.let { value ->
            append(" source=")
            append(value)
        }
        stage?.let { value ->
            append(" stage=")
            append(value)
        }
    }
}

internal fun reportFailure(
    context: FailureLogContext,
    error: Throwable? = null,
    logger: (tag: String, message: String, error: Throwable?) -> Unit =
        { tag, message, cause -> AndroidAppLogger.error(tag, message, cause) },
) {
    logger(
        context.logTag,
        context.message(),
        error?.toSafeDiagnosticException(),
    )
}

private fun Throwable.toSafeDiagnosticException(): Throwable =
    IllegalStateException(
        buildString {
            append(this@toSafeDiagnosticException::class.qualifiedName.orEmpty())
            this@toSafeDiagnosticException.message
                ?.sanitizeDiagnostic()
                ?.takeIf(String::isNotBlank)
                ?.let { safeMessage ->
                    append(": ")
                    append(safeMessage)
                }
        },
    ).also { diagnostic ->
        diagnostic.stackTrace = stackTrace
    }

private fun String.sanitizeDiagnostic(): String =
    replace(UriPattern, "<redacted-uri>")
        .replace(UuidPattern, "<redacted-uuid>")
        .replace(LongOpaqueTokenPattern, "<redacted-token>")
        .take(MaxDiagnosticMessageLength)

private fun String.isLogToken(): Boolean =
    isNotBlank() && all { character ->
        character in 'a'..'z' || character in '0'..'9' || character == '_'
    }

internal val GenericUserActionFailureContext = FailureLogContext(
    operation = "user_action",
)

private const val DefaultFailureLogTag = "Failure"
private const val MaxDiagnosticMessageLength = 512

private val UriPattern = Regex(
    """(?i)\b[a-z][a-z0-9+.-]*://[^\s<>"']+""",
)
private val UuidPattern = Regex(
    """(?i)\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\b""",
)
private val LongOpaqueTokenPattern = Regex("""\b[A-Za-z0-9_-]{32,}\b""")
