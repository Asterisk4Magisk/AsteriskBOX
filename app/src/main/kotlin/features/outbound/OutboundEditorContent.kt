// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.outbound

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
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
    val httpVersion: Int? = null,
)

internal fun resolveOutboundEditorSections(
    schema: OutboundEditorSchema,
    document: OutboundEditorDocument,
): List<OutboundEditorSectionSlot> = schema.sections.mapNotNull { section ->
    val httpVersion = if (section.section == OutboundEditorSection.HTTP_VERSION_OPTIONS) {
        document.effectiveHttpVersion()
    } else {
        null
    }
    section.fields
        .map { field ->
            OutboundEditorFieldSlot(
                field = field,
                visible = document.isVisible(field),
            )
        }
        .takeIf { fields -> httpVersion != null || fields.any(OutboundEditorFieldSlot::visible) }
        ?.let { fields ->
            OutboundEditorSectionSlot(
                section = section.section,
                fields = fields,
                httpVersion = httpVersion,
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
        "mieru", "shadowquic", "juicity", "sudoku" -> pluginOutboundEditor(state)
        else -> error("Unsupported outbound editor: ${state.schema.type}")
    }
}

internal fun LazyListScope.outboundEditorSections(state: OutboundEditorContentState) {
    val sections = resolveOutboundEditorSections(state.schema, state.document)
    items(
        items = sections,
        key = { section -> section.section.name },
    ) { section ->
        if (section.section == OutboundEditorSection.HTTP_VERSION_OPTIONS) {
            AnimatedContent(
                targetState = section.takeIf { it.fields.any(OutboundEditorFieldSlot::visible) },
                contentKey = { it?.httpVersion },
                transitionSpec = AsteriskMotion.fadeThrough(AsteriskMotion.contentFade(), AsteriskMotion.contentSize()),
                label = "outbound-http-version-options",
            ) { visibleSection ->
                if (visibleSection == null) {
                    Spacer(Modifier.fillMaxWidth())
                } else {
                    OutboundEditorSectionCard(visibleSection, state)
                }
            }
        } else {
            OutboundEditorSectionCard(section, state)
        }
    }
}

@Composable
private fun OutboundEditorSectionCard(
    section: OutboundEditorSectionSlot,
    state: OutboundEditorContentState,
) {
    EditorSectionCard(
        title = section.section.localizedTitle(section.httpVersion),
        description = stringResource(section.section.summaryRes(state.document, section.httpVersion)),
        endsWithTextField = when (section.fields.last { it.visible }.field.kind) {
            OutboundFieldKind.TEXT,
            OutboundFieldKind.INTEGER,
            OutboundFieldKind.TEXT_LIST,
            OutboundFieldKind.MULTILINE -> true
            else -> false
        },
    ) {
        val fieldEnter = AsteriskMotion.contentEnter()
        val fieldExit = AsteriskMotion.contentExit()
        Column(Modifier.fillMaxWidth()) {
            section.fields.forEach { slot ->
                key(slot.field.path, slot.field.labelRes) {
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
