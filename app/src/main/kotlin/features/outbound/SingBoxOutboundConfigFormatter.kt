// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.outbound

import android.content.Context
import features.resources.runtime.skipsBundledCoreConfigCheck
import io.nekohasekai.libbox.Libbox

internal fun interface SingBoxOutboundConfigFormatter {
    fun format(content: String): String
}

internal object LibboxSingBoxOutboundConfigFormatter : SingBoxOutboundConfigFormatter {
    override fun format(content: String): String = Libbox.formatConfig(content).value
}

/** Keeps outbounds as written for a custom ROOT core, which may accept fields libbox rejects. */
internal object PassthroughSingBoxOutboundConfigFormatter : SingBoxOutboundConfigFormatter {
    override fun format(content: String): String = content
}

internal fun Context.singBoxOutboundConfigFormatter(runMode: Int): SingBoxOutboundConfigFormatter =
    if (skipsBundledCoreConfigCheck(runMode)) PassthroughSingBoxOutboundConfigFormatter
    else LibboxSingBoxOutboundConfigFormatter
