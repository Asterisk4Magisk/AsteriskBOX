// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package engine.singbox

internal const val DefaultEbpfLocalDataPlane = "tc"
internal const val DefaultEbpfSharedDataPlane = "socket_assign"
internal const val DefaultEbpfDnsMode = "hijack"

internal val EbpfLocalDataPlanes = listOf("tc", "cgroup")
internal val EbpfSharedDataPlanes = listOf("socket_assign", "packet_rewrite")
internal val EbpfDnsModes = listOf("hijack", "respect_policy")

internal fun String.effectiveEbpfDnsMode(enableLocalDns: Boolean): String {
    if (!enableLocalDns) return "off"
    require(this in EbpfDnsModes) { "eBPF dns_mode must be hijack or respect_policy" }
    return this
}
