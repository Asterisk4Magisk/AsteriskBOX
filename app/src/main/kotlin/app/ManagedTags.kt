// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package app

internal const val ManagedSingBoxTagPrefix = "__asteriskbox_"

internal fun managedOutboundTag(id: Int): String =
    "${ManagedSingBoxTagPrefix}outbound_${id}__"

internal fun managedEndpointTag(id: Int): String =
    "${ManagedSingBoxTagPrefix}endpoint_${id}__"

internal fun managedSelectorTag(id: Int): String =
    "${ManagedSingBoxTagPrefix}selector_${id}__"

internal fun managedDnsServerTag(id: Int): String =
    "${ManagedSingBoxTagPrefix}dns_server_${id}__"

internal fun managedDnsEvaluationTag(id: Int): String =
    "${ManagedSingBoxTagPrefix}dns_evaluation_${id}__"

internal fun managedCustomRuleSetTag(id: Int): String =
    "${ManagedSingBoxTagPrefix}rule_set_${id}__"

internal fun managedBundledRuleSetTag(kind: ResourceFileKind): String =
    "${ManagedSingBoxTagPrefix}rule_set_${kind.name.lowercase()}__"
