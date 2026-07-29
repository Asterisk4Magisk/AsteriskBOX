// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import app.OutboundGroupState
import app.OutboundGroupUpdateStatus
import app.OutboundState
import app.SingBoxEndpointState
import app.SingBoxSelectorState
import app.SingBoxSelectorTypeSelector
import app.DefaultSingBoxUrlTestIdleTimeout
import app.DefaultSingBoxUrlTestInterval
import app.DefaultSingBoxUrlTestTolerance
import app.DefaultSingBoxUrlTestUrl

@Entity(
    tableName = "outbound_groups",
    indices = [Index("position")],
)
internal data class OutboundGroupEntity(
    @PrimaryKey val id: Int,
    val position: Int,
    val name: String,
    val url: String,
    val userAgent: String,
    val updateInterval: String,
    val hwid: String,
    val updateViaProxy: Boolean,
    val ageSecretKey: String,
    val enabled: Boolean,
    val strictImport: Boolean,
    val lastUpdateAttemptAtMillis: Long,
    val lastUpdatedAtMillis: Long,
    val lastUpdateStatus: String,
    val lastUpdateImportedCount: Int,
    val lastUpdateSkippedCount: Int,
    val lastUpdateDuplicateCount: Int,
    val consecutiveUpdateFailures: Int,
    val lastUpdateErrorSummary: String,
    val subscriptionEtag: String,
    val subscriptionLastModified: String,
) {
    fun toState(): OutboundGroupState =
        OutboundGroupState(
            id = id,
            name = name,
            url = url,
            userAgent = userAgent,
            updateInterval = updateInterval,
            hwid = hwid,
            updateViaProxy = updateViaProxy,
            ageSecretKey = ageSecretKey,
            enabled = enabled,
            strictImport = strictImport,
            lastUpdateAttemptAtMillis = lastUpdateAttemptAtMillis,
            lastUpdatedAtMillis = lastUpdatedAtMillis,
            lastUpdateStatus = runCatching {
                OutboundGroupUpdateStatus.valueOf(lastUpdateStatus)
            }.getOrDefault(OutboundGroupUpdateStatus.NEVER),
            lastUpdateImportedCount = lastUpdateImportedCount,
            lastUpdateSkippedCount = lastUpdateSkippedCount,
            lastUpdateDuplicateCount = lastUpdateDuplicateCount,
            consecutiveUpdateFailures = consecutiveUpdateFailures,
            lastUpdateErrorSummary = lastUpdateErrorSummary,
            subscriptionEtag = subscriptionEtag,
            subscriptionLastModified = subscriptionLastModified,
        )

    companion object {
        fun from(position: Int, group: OutboundGroupState): OutboundGroupEntity =
            OutboundGroupEntity(
                id = group.id,
                position = position,
                name = group.name,
                url = group.url,
                userAgent = group.userAgent,
                updateInterval = group.updateInterval,
                hwid = group.hwid,
                updateViaProxy = group.updateViaProxy,
                ageSecretKey = group.ageSecretKey,
                enabled = group.enabled,
                strictImport = group.strictImport,
                lastUpdateAttemptAtMillis = group.lastUpdateAttemptAtMillis,
                lastUpdatedAtMillis = group.lastUpdatedAtMillis,
                lastUpdateStatus = group.lastUpdateStatus.name,
                lastUpdateImportedCount = group.lastUpdateImportedCount,
                lastUpdateSkippedCount = group.lastUpdateSkippedCount,
                lastUpdateDuplicateCount = group.lastUpdateDuplicateCount,
                consecutiveUpdateFailures = group.consecutiveUpdateFailures,
                lastUpdateErrorSummary = group.lastUpdateErrorSummary,
                subscriptionEtag = group.subscriptionEtag,
                subscriptionLastModified = group.subscriptionLastModified,
            )
    }
}

@Entity(
    tableName = "outbounds",
    indices = [
        Index("groupId"),
        Index("position"),
    ],
)
internal data class OutboundEntity(
    @PrimaryKey val id: Int,
    val position: Int,
    val groupId: Int,
    val remarks: String,
    val type: String,
    val json: String,
    val pingMillis: Long?,
) {
    fun toState(): OutboundState =
        OutboundState(
            id = id,
            groupId = groupId,
            remarks = remarks,
            type = type,
            json = json,
            pingMillis = pingMillis,
        )

    companion object {
        fun from(position: Int, outbound: OutboundState): OutboundEntity =
            OutboundEntity(
                id = outbound.id,
                position = position,
                groupId = outbound.groupId,
                remarks = outbound.remarks,
                type = outbound.type,
                json = outbound.json,
                pingMillis = outbound.pingMillis,
            )
    }
}

@Entity(
    tableName = "endpoints",
    indices = [Index("position")],
)
internal data class EndpointEntity(
    @PrimaryKey val id: Int,
    val position: Int,
    val remarks: String,
    val type: String,
    val json: String,
) {
    fun toState(): SingBoxEndpointState =
        SingBoxEndpointState(
            id = id,
            remarks = remarks,
            type = type,
            json = json,
        )

    companion object {
        fun from(position: Int, endpoint: SingBoxEndpointState): EndpointEntity =
            EndpointEntity(
                id = endpoint.id,
                position = position,
                remarks = endpoint.remarks,
                type = endpoint.type,
                json = endpoint.json,
            )
    }
}

@Entity(
    tableName = "selectors",
    indices = [Index("position")],
)
internal data class SelectorEntity(
    @PrimaryKey val id: Int,
    val position: Int,
    val remarks: String,
    val outbounds: List<String>,
    val defaultOutbound: String,
    val type: String = SingBoxSelectorTypeSelector,
    val url: String = DefaultSingBoxUrlTestUrl,
    val interval: String = DefaultSingBoxUrlTestInterval,
    val tolerance: Int = DefaultSingBoxUrlTestTolerance,
    val idleTimeout: String = DefaultSingBoxUrlTestIdleTimeout,
    val interruptExistConnections: Boolean,
) {
    fun toState(): SingBoxSelectorState =
        SingBoxSelectorState(
            id = id,
            remarks = remarks,
            outbounds = outbounds,
            default = defaultOutbound,
            type = type,
            url = url,
            interval = interval,
            tolerance = tolerance,
            idleTimeout = idleTimeout,
            interruptExistConnections = interruptExistConnections,
        )

    companion object {
        fun from(position: Int, selector: SingBoxSelectorState): SelectorEntity =
            SelectorEntity(
                id = selector.id,
                position = position,
                remarks = selector.remarks,
                outbounds = selector.outbounds,
                defaultOutbound = selector.default,
                type = selector.type,
                url = selector.url,
                interval = selector.interval,
                tolerance = selector.tolerance,
                idleTimeout = selector.idleTimeout,
                interruptExistConnections = selector.interruptExistConnections,
            )
    }
}

@Entity(
    tableName = "proxy_app_list_selected_apps",
    indices = [Index("position")],
)
internal data class ProxyAppListSelectedAppEntity(
    @PrimaryKey val packageKey: String,
    val position: Int,
)
