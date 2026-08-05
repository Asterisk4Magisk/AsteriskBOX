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
        .filter { (index, server) ->
            index != currentIndex && server.type != "group"
        }
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
    val remaining = servers
        .filterIndexed { itemIndex, _ -> itemIndex != index }
        .map { server ->
            server.copy(
                domainResolver = server.domainResolver
                    .takeUnless { it.trim() == deletedTag }
                    .orEmpty(),
                servers = if (server.type == "group") {
                    server.servers.filter { member -> member != deletedTag }
                } else {
                    server.servers
                },
            )
        }
    return remaining.filterNot { server ->
        server.type == "group" && server.servers.isEmpty()
    }
}
