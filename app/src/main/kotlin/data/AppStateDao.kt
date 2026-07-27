// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import app.AppState

@Dao
internal abstract class AppStateDao {
    @Transaction
    open suspend fun loadState(): PersistedAppState {
        return PersistedAppState(
            metadata = findMetadata(),
            outboundGroups = findOutboundGroups(),
            outbounds = findOutbounds(),
            endpoints = findEndpoints(),
            selectors = findSelectors(),
            routeRules = findRouteRules(),
            dnsServers = findDnsServers(),
            dnsRules = findDnsRules(),
            customResourceFiles = findCustomResourceFiles(),
            proxyAppListSelectedApps = findProxyAppListSelectedApps(),
        )
    }

    @Transaction
    open suspend fun saveState(previousState: AppState, nextState: AppState, replaceAll: Boolean) {
        saveLists(previousState, nextState, replaceAll)
    }

    private suspend fun saveLists(previousState: AppState, nextState: AppState, replaceAll: Boolean) {
        if (
            replaceAll ||
            AppStateMetadataEntity.from(previousState) != AppStateMetadataEntity.from(nextState)
        ) {
            insertMetadata(AppStateMetadataEntity.from(nextState))
        }

        if (replaceAll || previousState.outboundGroups != nextState.outboundGroups) {
            replaceOutboundGroups(nextState.outboundGroups.mapIndexed { index, group ->
                OutboundGroupEntity.from(index, group)
            })
        }

        if (replaceAll || previousState.outbounds != nextState.outbounds) {
            replaceOutbounds(nextState.outbounds.mapIndexed { index, outbound ->
                OutboundEntity.from(index, outbound)
            })
        }

        if (replaceAll || previousState.endpoints != nextState.endpoints) {
            replaceEndpoints(nextState.endpoints.mapIndexed { index, endpoint ->
                EndpointEntity.from(index, endpoint)
            })
        }

        if (replaceAll || previousState.selectors != nextState.selectors) {
            replaceSelectors(nextState.selectors.mapIndexed { index, selector ->
                SelectorEntity.from(index, selector)
            })
        }

        if (replaceAll || previousState.routeRules != nextState.routeRules) {
            replaceRouteRules(nextState.routeRules.mapIndexed(RouteRuleEntity::from))
        }

        if (replaceAll || previousState.dnsServers != nextState.dnsServers) {
            replaceDnsServers(nextState.dnsServers.mapIndexed(DnsServerEntity::from))
        }

        if (replaceAll || previousState.dnsRules != nextState.dnsRules) {
            replaceDnsRules(nextState.dnsRules.mapIndexed(DnsRuleEntity::from))
        }

        if (replaceAll || previousState.customResourceFiles != nextState.customResourceFiles) {
            replaceCustomResourceFiles(
                nextState.customResourceFiles.mapIndexed(CustomResourceFileEntity::from),
            )
        }

        if (replaceAll || previousState.proxyAppListSelectedApps != nextState.proxyAppListSelectedApps) {
            replaceProxyAppListSelectedApps(nextState.proxyAppListSelectedApps.mapIndexed { index, packageKey ->
                ProxyAppListSelectedAppEntity(position = index, packageKey = packageKey)
            })
        }
    }

    @Query("SELECT * FROM outbound_groups ORDER BY position ASC")
    protected abstract suspend fun findOutboundGroups(): List<OutboundGroupEntity>

    @Query("SELECT * FROM outbounds ORDER BY position ASC")
    protected abstract suspend fun findOutbounds(): List<OutboundEntity>

    @Query("SELECT * FROM endpoints ORDER BY position ASC")
    protected abstract suspend fun findEndpoints(): List<EndpointEntity>

    @Query("SELECT * FROM selectors ORDER BY position ASC")
    protected abstract suspend fun findSelectors(): List<SelectorEntity>

    @Query("SELECT * FROM app_state_metadata WHERE id = 1")
    protected abstract suspend fun findMetadata(): AppStateMetadataEntity?

    @Query("SELECT * FROM route_rules ORDER BY position ASC")
    protected abstract suspend fun findRouteRules(): List<RouteRuleEntity>

    @Query("SELECT * FROM dns_servers ORDER BY position ASC")
    protected abstract suspend fun findDnsServers(): List<DnsServerEntity>

    @Query("SELECT * FROM dns_rules ORDER BY position ASC")
    protected abstract suspend fun findDnsRules(): List<DnsRuleEntity>

    @Query("SELECT * FROM custom_resource_files ORDER BY position ASC")
    protected abstract suspend fun findCustomResourceFiles(): List<CustomResourceFileEntity>

    @Query("SELECT * FROM proxy_app_list_selected_apps ORDER BY position ASC")
    protected abstract suspend fun findProxyAppListSelectedApps(): List<ProxyAppListSelectedAppEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun insertOutboundGroups(entities: List<OutboundGroupEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun insertOutbounds(entities: List<OutboundEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun insertEndpoints(entities: List<EndpointEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun insertSelectors(entities: List<SelectorEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun insertMetadata(entity: AppStateMetadataEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun insertRouteRules(entities: List<RouteRuleEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun insertDnsServers(entities: List<DnsServerEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun insertDnsRules(entities: List<DnsRuleEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun insertCustomResourceFiles(entities: List<CustomResourceFileEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun insertProxyAppListSelectedApps(entities: List<ProxyAppListSelectedAppEntity>)

    @Query("DELETE FROM outbound_groups")
    protected abstract suspend fun deleteOutboundGroups()

    @Query("DELETE FROM outbounds")
    protected abstract suspend fun deleteOutbounds()

    @Query("DELETE FROM endpoints")
    protected abstract suspend fun deleteEndpoints()

    @Query("DELETE FROM selectors")
    protected abstract suspend fun deleteSelectors()

    @Query("DELETE FROM route_rules")
    protected abstract suspend fun deleteRouteRules()

    @Query("DELETE FROM dns_servers")
    protected abstract suspend fun deleteDnsServers()

    @Query("DELETE FROM dns_rules")
    protected abstract suspend fun deleteDnsRules()

    @Query("DELETE FROM custom_resource_files")
    protected abstract suspend fun deleteCustomResourceFiles()

    @Query("DELETE FROM proxy_app_list_selected_apps")
    protected abstract suspend fun deleteProxyAppListSelectedApps()

    private suspend fun replaceOutboundGroups(entities: List<OutboundGroupEntity>) {
        deleteOutboundGroups()
        insertOutboundGroups(entities)
    }

    private suspend fun replaceOutbounds(entities: List<OutboundEntity>) {
        deleteOutbounds()
        insertOutbounds(entities)
    }

    private suspend fun replaceEndpoints(entities: List<EndpointEntity>) {
        deleteEndpoints()
        insertEndpoints(entities)
    }

    private suspend fun replaceSelectors(entities: List<SelectorEntity>) {
        deleteSelectors()
        insertSelectors(entities)
    }

    private suspend fun replaceRouteRules(entities: List<RouteRuleEntity>) {
        deleteRouteRules()
        insertRouteRules(entities)
    }

    private suspend fun replaceDnsServers(entities: List<DnsServerEntity>) {
        deleteDnsServers()
        insertDnsServers(entities)
    }

    private suspend fun replaceDnsRules(entities: List<DnsRuleEntity>) {
        deleteDnsRules()
        insertDnsRules(entities)
    }

    private suspend fun replaceCustomResourceFiles(entities: List<CustomResourceFileEntity>) {
        deleteCustomResourceFiles()
        insertCustomResourceFiles(entities)
    }

    private suspend fun replaceProxyAppListSelectedApps(entities: List<ProxyAppListSelectedAppEntity>) {
        deleteProxyAppListSelectedApps()
        insertProxyAppListSelectedApps(entities)
    }
}
