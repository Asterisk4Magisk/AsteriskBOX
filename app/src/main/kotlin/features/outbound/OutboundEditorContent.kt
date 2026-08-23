// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.outbound

import androidx.compose.animation.AnimatedVisibility
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

internal data class OutboundEditorFieldSlot(
    val field: OutboundFieldSpec,
    val visible: Boolean,
)

internal data class OutboundEditorSectionSlot(
    val section: OutboundEditorSection,
    val fields: List<OutboundEditorFieldSlot>,
)

internal fun resolveOutboundEditorSections(
    schema: OutboundEditorSchema,
    document: OutboundEditorDocument,
): List<OutboundEditorSectionSlot> = schema.sections.mapNotNull { section ->
    section.fields
        .map { field ->
            OutboundEditorFieldSlot(
                field = field,
                visible = document.isVisible(field),
            )
        }
        .takeIf { fields -> fields.any(OutboundEditorFieldSlot::visible) }
        ?.let { fields ->
            OutboundEditorSectionSlot(
                section = section.section,
                fields = fields,
            )
        }
}

internal fun LazyListScope.outboundEditorContent(state: OutboundEditorContentState) {
    when (state.schema.type) {
        "socks" -> socksOutboundEditor(state)
        "http" -> httpOutboundEditor(state)
        "naive" -> naiveOutboundEditor(state)
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
    val visibleSections = resolveOutboundEditorSections(state.schema, state.document)
    items(
        items = visibleSections,
        key = { section -> section.section.name },
    ) { section ->
        EditorSectionCard(
            title = section.section.localizedTitle(),
            description = section.section.localizedSummary(),
        ) {
            val fieldEnter = AsteriskMotion.contentEnter()
            val fieldExit = AsteriskMotion.contentExit()
            Column(
                modifier = Modifier.fillMaxWidth(),
            ) {
                section.fields.forEach { slot ->
                    key(slot.field.path) {
                        AnimatedVisibility(
                            visible = slot.visible,
                            enter = fieldEnter,
                            exit = fieldExit,
                            label = "outbound-${slot.field.path}-visibility",
                        ) {
                            OutboundEditorField(
                                field = slot.field,
                                document = state.document,
                                error = state.errors[slot.field.path],
                                referenceOptions = state.referenceOptions[slot.field.path].orEmpty(),
                                onDocumentChange = state.onDocumentChange,
                            )
                        }
                    }
                }
            }
        }
    }
}
