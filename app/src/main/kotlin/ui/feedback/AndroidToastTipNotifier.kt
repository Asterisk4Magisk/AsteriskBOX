// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package ui.feedback

import android.content.Context
import android.widget.Toast
import features.logs.FailureLogContext
import features.logs.GenericUserActionFailureContext
import features.logs.reportFailure
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.asterisk.zcc.abox.R

internal class AndroidToastTipNotifier(context: Context) {
    private val appContext = context.applicationContext

    suspend fun show(message: String) {
        withContext(Dispatchers.Main.immediate) {
            Toast.makeText(appContext, message, Toast.LENGTH_SHORT).show()
        }
    }

    suspend fun showError(
        error: Throwable,
        fallbackMessage: String? = null,
        failureContext: FailureLogContext = GenericUserActionFailureContext,
    ) {
        val rootMessage = error.rootOperationTipMessageOrNull { owner ->
            appContext.getString(R.string.root_foreign_owner_conflict, owner)
        }
        deliverErrorFeedback(
            error = error,
            fallbackMessage = rootMessage ?: fallbackMessage,
            failureContext = failureContext,
            reportFailure = { context, cause -> reportFailure(context, cause) },
            showMessage = ::show,
        )
    }
}

internal suspend fun deliverErrorFeedback(
    error: Throwable,
    fallbackMessage: String?,
    failureContext: FailureLogContext,
    reportFailure: (FailureLogContext, Throwable) -> Unit,
    showMessage: suspend (String) -> Unit,
) {
    reportFailure(failureContext, error)
    showMessage(error.tipMessage(fallbackMessage))
}

private fun Throwable.tipMessage(fallbackMessage: String? = null): String {
    return fallbackMessage.orEmpty().ifBlank {
        message.orEmpty().ifBlank { this::class.simpleName.orEmpty() }
    }
}
