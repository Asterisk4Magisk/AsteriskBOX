// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.outbound

import io.nekohasekai.libbox.Libbox

internal fun interface SingBoxOutboundConfigFormatter {
    fun format(content: String): String
}

internal object LibboxSingBoxOutboundConfigFormatter : SingBoxOutboundConfigFormatter {
    override fun format(content: String): String = Libbox.formatConfig(content).value
}
