// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.settings.sheets

import app.SingBoxDnsServerState
import app.ManagedReferenceChoice

internal fun dnsDomainResolverChoices(
    servers: List<SingBoxDnsServerState>,
    currentIndex: Int?,
): List<ManagedReferenceChoice> =
    servers.withIndex()
        .filter { (index, _) -> index != currentIndex }
        .map { (_, server) ->
            ManagedReferenceChoice(
                tag = server.tag,
                remarks = server.remarks,
            )
        }
        .distinctBy(ManagedReferenceChoice::tag)

internal fun removeDnsServerAndReferences(
    servers: List<SingBoxDnsServerState>,
    index: Int,
): List<SingBoxDnsServerState> {
    val deletedTag = servers.getOrNull(index)?.tag?.trim() ?: return servers
    return servers
        .filterIndexed { itemIndex, _ -> itemIndex != index }
        .map { server ->
            if (server.domainResolver.trim() == deletedTag) {
                server.copy(domainResolver = "")
            } else {
                server
            }
        }
}
