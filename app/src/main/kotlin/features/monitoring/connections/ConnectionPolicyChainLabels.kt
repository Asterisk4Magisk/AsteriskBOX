// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.monitoring.connections

import engine.singbox.config.APP_DIRECT_OUTBOUND
import engine.singbox.config.APP_GLOBAL_SELECTOR

internal fun connectionPolicyChainReferenceLabels(
    managedLabels: Map<String, String>,
    globalSelectorLabel: String,
    directLabel: String,
): Map<String, String> = buildMap {
    putAll(managedLabels)
    put(APP_GLOBAL_SELECTOR, globalSelectorLabel)
    put(APP_DIRECT_OUTBOUND, directLabel)
}
