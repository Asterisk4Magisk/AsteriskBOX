// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

internal const val AsteriskDatabaseName = "asteriskbox-state.db"

@Database(
    entities = [
        OutboundGroupEntity::class,
        OutboundEntity::class,
        EndpointEntity::class,
        SelectorEntity::class,
        AppStateMetadataEntity::class,
        RouteRuleEntity::class,
        DnsServerEntity::class,
        DnsRuleEntity::class,
        CustomResourceFileEntity::class,
        ProxyAppListSelectedAppEntity::class,
    ],
    version = 3,
    exportSchema = true,
)
@TypeConverters(RoomTypeConverters::class)
internal abstract class AsteriskAppDatabase : RoomDatabase() {
    abstract fun appStateDao(): AppStateDao
}
