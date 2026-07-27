// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package engine.singbox.runtime

internal data class SingBoxConnection(
    val id: String,
    val network: String = "",
    val inboundType: String = "",
    val sourceAddress: String = "",
    val destinationAddress: String = "",
    val process: String = "",
    val processPath: String = "",
    val uid: Long? = null,
    val outbound: String = "",
    val outboundType: String = "",
    val chains: List<String> = emptyList(),
    val rule: String = "",
    val uploadBytes: Long = 0L,
    val downloadBytes: Long = 0L,
    val uploadBytesPerSecond: Long? = null,
    val downloadBytesPerSecond: Long? = null,
    val startedAtMillis: Long? = null,
)

internal data class SingBoxConnectionsState(
    val uploadTotalBytes: Long = 0L,
    val downloadTotalBytes: Long = 0L,
    val connections: List<SingBoxConnection> = emptyList(),
    val updatedAtMillis: Long = 0L,
)
