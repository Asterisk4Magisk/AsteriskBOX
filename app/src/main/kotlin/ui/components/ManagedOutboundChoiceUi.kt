// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.ManagedOutboundChoice
import app.ManagedOutboundChoiceKind
import org.asterisk.zcc.abox.R

@Composable
internal fun ManagedOutboundChoice.localizedLabel(includeGroupName: Boolean = true): String {
    if (kind == ManagedOutboundChoiceKind.Direct) {
        return stringResource(R.string.routing_direct)
    }
    if (kind == ManagedOutboundChoiceKind.GlobalSelector) {
        return stringResource(R.string.routing_global)
    }
    val baseLabel = groupName?.takeIf { includeGroupName }?.let { group ->
        stringResource(R.string.outbound_choice_with_group, label, group)
    } ?: label
    val typeLabel = when (kind) {
        ManagedOutboundChoiceKind.Group ->
            stringResource(R.string.selector_target_group)
        ManagedOutboundChoiceKind.Selector ->
            stringResource(R.string.selector_type_selector)
        ManagedOutboundChoiceKind.UrlTest ->
            stringResource(R.string.selector_type_urltest)
        ManagedOutboundChoiceKind.Endpoint ->
            stringResource(R.string.outbound_choice_type_endpoint)
        else -> null
    }
    return typeLabel?.let { type ->
        stringResource(R.string.outbound_choice_with_type, baseLabel, type)
    } ?: baseLabel
}
