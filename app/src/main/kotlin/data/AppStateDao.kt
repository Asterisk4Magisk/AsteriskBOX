// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
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
            if (replaceAll) {
                replaceOutboundGroups(nextState.outboundGroups.mapIndexed { index, group ->
                    OutboundGroupEntity.from(index, group)
                })
            } else {
                val current = findOutboundGroups()
                val desired = entitiesWithStablePositions(
                    current = current,
                    next = nextState.outboundGroups,
                    currentKeyOf = OutboundGroupEntity::id,
                    currentPositionOf = OutboundGroupEntity::position,
                    nextKeyOf = { it.id },
                    entityOf = OutboundGroupEntity::from,
                )
                saveOutboundGroupDelta(entityDelta(current, desired, OutboundGroupEntity::id))
            }
        }

        if (replaceAll || previousState.outbounds != nextState.outbounds) {
            if (replaceAll) {
                replaceOutbounds(outboundEntitiesForReplacement(nextState.outbounds))
            } else {
                val current = findOutbounds()
                val positionById = buildMap {
                    nextState.outbounds.groupBy { it.groupId }.forEach { (groupId, outbounds) ->
                        putAll(
                            stablePositions(
                                current.filter { it.groupId == groupId }
                                    .map { PositionedKey(it.id, it.position) },
                                outbounds.map { it.id },
                            ),
                        )
                    }
                }
                val desired = nextState.outbounds.map { outbound ->
                    OutboundEntity.from(positionById.getValue(outbound.id), outbound)
                }
                saveOutboundDelta(entityDelta(current, desired, OutboundEntity::id))
            }
        }

        if (replaceAll || previousState.endpoints != nextState.endpoints) {
            if (replaceAll) {
                replaceEndpoints(nextState.endpoints.mapIndexed { index, endpoint ->
                    EndpointEntity.from(index, endpoint)
                })
            } else {
                val current = findEndpoints()
                val desired = entitiesWithStablePositions(
                    current = current,
                    next = nextState.endpoints,
                    currentKeyOf = EndpointEntity::id,
                    currentPositionOf = EndpointEntity::position,
                    nextKeyOf = { it.id },
                    entityOf = EndpointEntity::from,
                )
                saveEndpointDelta(entityDelta(current, desired, EndpointEntity::id))
            }
        }

        if (replaceAll || previousState.selectors != nextState.selectors) {
            if (replaceAll) {
                replaceSelectors(nextState.selectors.mapIndexed { index, selector ->
                    SelectorEntity.from(index, selector)
                })
            } else {
                val current = findSelectors()
                val desired = entitiesWithStablePositions(
                    current = current,
                    next = nextState.selectors,
                    currentKeyOf = SelectorEntity::id,
                    currentPositionOf = SelectorEntity::position,
                    nextKeyOf = { it.id },
                    entityOf = SelectorEntity::from,
                )
                saveSelectorDelta(entityDelta(current, desired, SelectorEntity::id))
            }
        }

        if (replaceAll || previousState.routeRules != nextState.routeRules) {
            if (replaceAll) {
                replaceRouteRules(nextState.routeRules.mapIndexed(RouteRuleEntity::from))
            } else {
                val current = findRouteRules()
                val desired = entitiesWithStablePositions(
                    current = current,
                    next = nextState.routeRules,
                    currentKeyOf = RouteRuleEntity::id,
                    currentPositionOf = RouteRuleEntity::position,
                    nextKeyOf = { it.id },
                    entityOf = RouteRuleEntity::from,
                )
                saveRouteRuleDelta(entityDelta(current, desired, RouteRuleEntity::id))
            }
        }

        if (replaceAll || previousState.dnsServers != nextState.dnsServers) {
            if (replaceAll) {
                replaceDnsServers(nextState.dnsServers.mapIndexed(DnsServerEntity::from))
            } else {
                val current = findDnsServers()
                val desired = entitiesWithStablePositions(
                    current = current,
                    next = nextState.dnsServers,
                    currentKeyOf = DnsServerEntity::id,
                    currentPositionOf = DnsServerEntity::position,
                    nextKeyOf = { it.id },
                    entityOf = DnsServerEntity::from,
                )
                saveDnsServerDelta(entityDelta(current, desired, DnsServerEntity::id))
            }
        }

        if (replaceAll || previousState.dnsRules != nextState.dnsRules) {
            if (replaceAll) {
                replaceDnsRules(nextState.dnsRules.mapIndexed(DnsRuleEntity::from))
            } else {
                val current = findDnsRules()
                val desired = entitiesWithStablePositions(
                    current = current,
                    next = nextState.dnsRules,
                    currentKeyOf = DnsRuleEntity::id,
                    currentPositionOf = DnsRuleEntity::position,
                    nextKeyOf = { it.id },
                    entityOf = DnsRuleEntity::from,
                )
                saveDnsRuleDelta(entityDelta(current, desired, DnsRuleEntity::id))
            }
        }

        if (replaceAll || previousState.customResourceFiles != nextState.customResourceFiles) {
            if (replaceAll) {
                replaceCustomResourceFiles(
                    nextState.customResourceFiles.mapIndexed(CustomResourceFileEntity::from),
                )
            } else {
                val current = findCustomResourceFiles()
                val desired = entitiesWithStablePositions(
                    current = current,
                    next = nextState.customResourceFiles,
                    currentKeyOf = CustomResourceFileEntity::id,
                    currentPositionOf = CustomResourceFileEntity::position,
                    nextKeyOf = { it.id },
                    entityOf = CustomResourceFileEntity::from,
                )
                saveCustomResourceFileDelta(entityDelta(current, desired, CustomResourceFileEntity::id))
            }
        }

        if (replaceAll || previousState.proxyAppListSelectedApps != nextState.proxyAppListSelectedApps) {
            if (replaceAll) {
                replaceProxyAppListSelectedApps(nextState.proxyAppListSelectedApps.mapIndexed { index, packageKey ->
                    ProxyAppListSelectedAppEntity(position = index, packageKey = packageKey)
                })
            } else {
                val current = findProxyAppListSelectedApps()
                val desired = entitiesWithStablePositions(
                    current = current,
                    next = nextState.proxyAppListSelectedApps,
                    currentKeyOf = ProxyAppListSelectedAppEntity::packageKey,
                    currentPositionOf = ProxyAppListSelectedAppEntity::position,
                    nextKeyOf = { it },
                    entityOf = { position, packageKey ->
                        ProxyAppListSelectedAppEntity(position = position, packageKey = packageKey)
                    },
                )
                saveProxyAppListSelectedAppDelta(
                    entityDelta(current, desired, ProxyAppListSelectedAppEntity::packageKey),
                )
            }
        }
    }

    private fun <S, E, K> entitiesWithStablePositions(
        current: List<E>,
        next: List<S>,
        currentKeyOf: (E) -> K,
        currentPositionOf: (E) -> Int,
        nextKeyOf: (S) -> K,
        entityOf: (Int, S) -> E,
    ): List<E> {
        val positions = stablePositions(
            current.map { PositionedKey(currentKeyOf(it), currentPositionOf(it)) },
            next.map(nextKeyOf),
        )
        return next.map { state -> entityOf(positions.getValue(nextKeyOf(state)), state) }
    }

    @Query("SELECT * FROM outbound_groups ORDER BY position ASC")
    protected abstract suspend fun findOutboundGroups(): List<OutboundGroupEntity>

    @Query("SELECT * FROM outbounds ORDER BY groupId ASC, position ASC, id ASC")
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

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertOutboundGroups(entities: List<OutboundGroupEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertOutbounds(entities: List<OutboundEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertEndpoints(entities: List<EndpointEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertSelectors(entities: List<SelectorEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun insertMetadata(entity: AppStateMetadataEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertRouteRules(entities: List<RouteRuleEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertDnsServers(entities: List<DnsServerEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertDnsRules(entities: List<DnsRuleEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertCustomResourceFiles(entities: List<CustomResourceFileEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertProxyAppListSelectedApps(entities: List<ProxyAppListSelectedAppEntity>)

    @Update
    protected abstract suspend fun updateOutboundGroups(entities: List<OutboundGroupEntity>)

    @Update
    protected abstract suspend fun updateOutbounds(entities: List<OutboundEntity>)

    @Update
    protected abstract suspend fun updateEndpoints(entities: List<EndpointEntity>)

    @Update
    protected abstract suspend fun updateSelectors(entities: List<SelectorEntity>)

    @Update
    protected abstract suspend fun updateRouteRules(entities: List<RouteRuleEntity>)

    @Update
    protected abstract suspend fun updateDnsServers(entities: List<DnsServerEntity>)

    @Update
    protected abstract suspend fun updateDnsRules(entities: List<DnsRuleEntity>)

    @Update
    protected abstract suspend fun updateCustomResourceFiles(entities: List<CustomResourceFileEntity>)

    @Update
    protected abstract suspend fun updateProxyAppListSelectedApps(entities: List<ProxyAppListSelectedAppEntity>)

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

    @Query("DELETE FROM outbound_groups WHERE id IN (:ids)")
    protected abstract suspend fun deleteOutboundGroupsById(ids: Set<Int>)

    @Query("DELETE FROM outbounds WHERE id IN (:ids)")
    protected abstract suspend fun deleteOutboundsById(ids: Set<Int>)

    @Query("DELETE FROM endpoints WHERE id IN (:ids)")
    protected abstract suspend fun deleteEndpointsById(ids: Set<Int>)

    @Query("DELETE FROM selectors WHERE id IN (:ids)")
    protected abstract suspend fun deleteSelectorsById(ids: Set<Int>)

    @Query("DELETE FROM route_rules WHERE id IN (:ids)")
    protected abstract suspend fun deleteRouteRulesById(ids: Set<Int>)

    @Query("DELETE FROM dns_servers WHERE id IN (:ids)")
    protected abstract suspend fun deleteDnsServersById(ids: Set<Int>)

    @Query("DELETE FROM dns_rules WHERE id IN (:ids)")
    protected abstract suspend fun deleteDnsRulesById(ids: Set<Int>)

    @Query("DELETE FROM custom_resource_files WHERE id IN (:ids)")
    protected abstract suspend fun deleteCustomResourceFilesById(ids: Set<Int>)

    @Query("DELETE FROM proxy_app_list_selected_apps WHERE packageKey IN (:packageKeys)")
    protected abstract suspend fun deleteProxyAppListSelectedAppsByPackageKey(packageKeys: Set<String>)

    private suspend fun saveOutboundGroupDelta(delta: EntityDelta<Int, OutboundGroupEntity>) {
        if (delta.removedKeys.isNotEmpty()) deleteOutboundGroupsById(delta.removedKeys)
        if (delta.added.isNotEmpty()) insertOutboundGroups(delta.added)
        if (delta.updated.isNotEmpty()) updateOutboundGroups(delta.updated)
    }

    private suspend fun saveOutboundDelta(delta: EntityDelta<Int, OutboundEntity>) {
        if (delta.removedKeys.isNotEmpty()) deleteOutboundsById(delta.removedKeys)
        if (delta.added.isNotEmpty()) insertOutbounds(delta.added)
        if (delta.updated.isNotEmpty()) updateOutbounds(delta.updated)
    }

    private suspend fun saveEndpointDelta(delta: EntityDelta<Int, EndpointEntity>) {
        if (delta.removedKeys.isNotEmpty()) deleteEndpointsById(delta.removedKeys)
        if (delta.added.isNotEmpty()) insertEndpoints(delta.added)
        if (delta.updated.isNotEmpty()) updateEndpoints(delta.updated)
    }

    private suspend fun saveSelectorDelta(delta: EntityDelta<Int, SelectorEntity>) {
        if (delta.removedKeys.isNotEmpty()) deleteSelectorsById(delta.removedKeys)
        if (delta.added.isNotEmpty()) insertSelectors(delta.added)
        if (delta.updated.isNotEmpty()) updateSelectors(delta.updated)
    }

    private suspend fun saveRouteRuleDelta(delta: EntityDelta<Int, RouteRuleEntity>) {
        if (delta.removedKeys.isNotEmpty()) deleteRouteRulesById(delta.removedKeys)
        if (delta.added.isNotEmpty()) insertRouteRules(delta.added)
        if (delta.updated.isNotEmpty()) updateRouteRules(delta.updated)
    }

    private suspend fun saveDnsServerDelta(delta: EntityDelta<Int, DnsServerEntity>) {
        if (delta.removedKeys.isNotEmpty()) deleteDnsServersById(delta.removedKeys)
        if (delta.added.isNotEmpty()) insertDnsServers(delta.added)
        if (delta.updated.isNotEmpty()) updateDnsServers(delta.updated)
    }

    private suspend fun saveDnsRuleDelta(delta: EntityDelta<Int, DnsRuleEntity>) {
        if (delta.removedKeys.isNotEmpty()) deleteDnsRulesById(delta.removedKeys)
        if (delta.added.isNotEmpty()) insertDnsRules(delta.added)
        if (delta.updated.isNotEmpty()) updateDnsRules(delta.updated)
    }

    private suspend fun saveCustomResourceFileDelta(delta: EntityDelta<Int, CustomResourceFileEntity>) {
        if (delta.removedKeys.isNotEmpty()) deleteCustomResourceFilesById(delta.removedKeys)
        if (delta.added.isNotEmpty()) insertCustomResourceFiles(delta.added)
        if (delta.updated.isNotEmpty()) updateCustomResourceFiles(delta.updated)
    }

    private suspend fun saveProxyAppListSelectedAppDelta(
        delta: EntityDelta<String, ProxyAppListSelectedAppEntity>,
    ) {
        if (delta.removedKeys.isNotEmpty()) {
            deleteProxyAppListSelectedAppsByPackageKey(delta.removedKeys)
        }
        if (delta.added.isNotEmpty()) insertProxyAppListSelectedApps(delta.added)
        if (delta.updated.isNotEmpty()) updateProxyAppListSelectedApps(delta.updated)
    }

    private suspend fun replaceOutboundGroups(entities: List<OutboundGroupEntity>) {
        deleteOutboundGroups()
        if (entities.isNotEmpty()) insertOutboundGroups(entities)
    }

    private suspend fun replaceOutbounds(entities: List<OutboundEntity>) {
        deleteOutbounds()
        if (entities.isNotEmpty()) insertOutbounds(entities)
    }

    private suspend fun replaceEndpoints(entities: List<EndpointEntity>) {
        deleteEndpoints()
        if (entities.isNotEmpty()) insertEndpoints(entities)
    }

    private suspend fun replaceSelectors(entities: List<SelectorEntity>) {
        deleteSelectors()
        if (entities.isNotEmpty()) insertSelectors(entities)
    }

    private suspend fun replaceRouteRules(entities: List<RouteRuleEntity>) {
        deleteRouteRules()
        if (entities.isNotEmpty()) insertRouteRules(entities)
    }

    private suspend fun replaceDnsServers(entities: List<DnsServerEntity>) {
        deleteDnsServers()
        if (entities.isNotEmpty()) insertDnsServers(entities)
    }

    private suspend fun replaceDnsRules(entities: List<DnsRuleEntity>) {
        deleteDnsRules()
        if (entities.isNotEmpty()) insertDnsRules(entities)
    }

    private suspend fun replaceCustomResourceFiles(entities: List<CustomResourceFileEntity>) {
        deleteCustomResourceFiles()
        if (entities.isNotEmpty()) insertCustomResourceFiles(entities)
    }

    private suspend fun replaceProxyAppListSelectedApps(entities: List<ProxyAppListSelectedAppEntity>) {
        deleteProxyAppListSelectedApps()
        if (entities.isNotEmpty()) insertProxyAppListSelectedApps(entities)
    }
}
