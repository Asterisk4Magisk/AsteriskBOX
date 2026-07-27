// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.outbound

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import ui.theme.AsteriskMotion

internal data class OutboundEditorContentState(
    val schema: OutboundEditorSchema,
    val document: OutboundEditorDocument,
    val errors: Map<String, OutboundEditorValidationError>,
    val referenceOptions: Map<String, List<OutboundReferenceOption>>,
    val onDocumentChange: (OutboundEditorDocument) -> Unit,
)

internal data class OutboundReferenceOption(
    val value: String,
    val label: String,
)

private data class VisibleOutboundEditorSection(
    val section: OutboundEditorSection,
    val fields: List<OutboundFieldSpec>,
)

internal fun LazyListScope.outboundEditorContent(state: OutboundEditorContentState) {
    when (state.schema.type) {
        "socks" -> socksOutboundEditor(state)
        "http" -> httpOutboundEditor(state)
        "shadowsocks" -> shadowsocksOutboundEditor(state)
        "vmess" -> vmessOutboundEditor(state)
        "trojan" -> trojanOutboundEditor(state)
        "vless" -> vlessOutboundEditor(state)
        "hysteria" -> hysteriaOutboundEditor(state)
        "tuic" -> tuicOutboundEditor(state)
        "hysteria2" -> hysteria2OutboundEditor(state)
        "shadowtls" -> shadowTlsOutboundEditor(state)
        "anytls" -> anyTlsOutboundEditor(state)
        "snell" -> snellOutboundEditor(state)
        "ssh" -> sshOutboundEditor(state)
        else -> error("Unsupported outbound editor: ${state.schema.type}")
    }
}

internal fun LazyListScope.outboundEditorSections(state: OutboundEditorContentState) {
    val visibleSections = state.schema.sections.mapNotNull { section ->
        section.fields
            .filter(state.document::isVisible)
            .takeIf(List<OutboundFieldSpec>::isNotEmpty)
            ?.let { fields ->
                VisibleOutboundEditorSection(
                    section = section.section,
                    fields = fields,
                )
            }
    }
    items(
        items = visibleSections,
        key = { section -> section.section.name },
    ) { section ->
        EditorSectionCard(
            title = section.section.localizedTitle(),
            description = section.section.localizedSummary(),
        ) {
            val effectsMotion = AsteriskMotion.fastEffects<Float>()
            AnimatedContent(
                targetState = section.fields,
                transitionSpec = {
                    fadeIn(animationSpec = effectsMotion)
                        .togetherWith(fadeOut(animationSpec = effectsMotion))
                },
                label = "outbound-${section.section.name.lowercase()}-fields",
            ) { fields ->
                Column(
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    fields.forEach { field ->
                        key(field.path) {
                            OutboundEditorField(
                                field = field,
                                document = state.document,
                                error = state.errors[field.path],
                                referenceOptions = state.referenceOptions[field.path].orEmpty(),
                                onDocumentChange = state.onDocumentChange,
                            )
                        }
                    }
                }
            }
        }
    }
}
