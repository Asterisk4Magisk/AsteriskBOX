// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.outbound

import app.OutboundGroupState

internal fun List<OutboundGroupState>.moveOutboundGroup(
    fromIndex: Int,
    toIndex: Int,
): List<OutboundGroupState> {
    if (fromIndex !in indices || toIndex !in indices || fromIndex == toIndex) return this
    return toMutableList().apply {
        add(toIndex, removeAt(fromIndex))
    }
}
