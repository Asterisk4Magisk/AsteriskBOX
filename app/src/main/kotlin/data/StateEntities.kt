// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import app.AppState
import app.CustomResourceFileState
import app.SingBoxDnsRuleState
import app.SingBoxDnsServerState
import app.SingBoxRouteRuleState

@Entity(tableName = "app_state_metadata")
internal data class AppStateMetadataEntity(
    @PrimaryKey val id: Int = 1,
    val nextOutboundGroupId: Int,
    val nextOutboundId: Int,
    val nextEndpointId: Int,
    val nextSelectorId: Int,
    val nextRouteRuleId: Int,
    val nextDnsServerId: Int,
    val nextDnsRuleId: Int,
    val nextCustomResourceFileId: Int,
) {
    companion object {
        fun from(state: AppState): AppStateMetadataEntity = AppStateMetadataEntity(
            nextOutboundGroupId = state.nextOutboundGroupId,
            nextOutboundId = state.nextOutboundId,
            nextEndpointId = state.nextEndpointId,
            nextSelectorId = state.nextSelectorId,
            nextRouteRuleId = state.nextRouteRuleId,
            nextDnsServerId = state.nextDnsServerId,
            nextDnsRuleId = state.nextDnsRuleId,
            nextCustomResourceFileId = state.nextCustomResourceFileId,
        )
    }
}

@Entity(tableName = "route_rules", indices = [Index("position")])
internal data class RouteRuleEntity(
    @PrimaryKey val id: Int,
    val position: Int,
    val remarks: String,
    val payload: String,
) {
    fun toState(): SingBoxRouteRuleState = SingBoxRouteRuleJson.decode(payload)

    companion object {
        fun from(position: Int, rule: SingBoxRouteRuleState): RouteRuleEntity = RouteRuleEntity(
            id = rule.id,
            position = position,
            remarks = rule.remarks,
            payload = SingBoxRouteRuleJson.encode(rule),
        )
    }
}

@Entity(tableName = "dns_servers", indices = [Index("position")])
internal data class DnsServerEntity(
    @PrimaryKey val id: Int,
    val position: Int,
    val remarks: String,
    val type: String,
    val payload: String,
) {
    fun toState(): SingBoxDnsServerState = SingBoxDnsServerJson.decode(payload)

    companion object {
        fun from(position: Int, server: SingBoxDnsServerState): DnsServerEntity = DnsServerEntity(
            id = server.id,
            position = position,
            remarks = server.remarks,
            type = server.type,
            payload = SingBoxDnsServerJson.encode(server),
        )
    }
}

@Entity(tableName = "dns_rules", indices = [Index("position")])
internal data class DnsRuleEntity(
    @PrimaryKey val id: Int,
    val position: Int,
    val remarks: String,
    val payload: String,
) {
    fun toState(): SingBoxDnsRuleState = SingBoxDnsRuleJson.decode(payload)

    companion object {
        fun from(position: Int, rule: SingBoxDnsRuleState): DnsRuleEntity = DnsRuleEntity(
            id = rule.id,
            position = position,
            remarks = rule.remarks,
            payload = SingBoxDnsRuleJson.encode(rule),
        )
    }
}

@Entity(tableName = "custom_resource_files", indices = [Index("position")])
internal data class CustomResourceFileEntity(
    @PrimaryKey val id: Int,
    val position: Int,
    val name: String,
    val url: String,
) {
    fun toState(): CustomResourceFileState = CustomResourceFileState(id, name, url)

    companion object {
        fun from(position: Int, file: CustomResourceFileState): CustomResourceFileEntity =
            CustomResourceFileEntity(file.id, position, file.name, file.url)
    }
}
