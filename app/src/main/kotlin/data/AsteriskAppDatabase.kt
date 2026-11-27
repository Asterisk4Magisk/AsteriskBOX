// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import features.subscription.encodeSubscriptionRequestHeaders
import features.subscription.legacySubscriptionRequestHeaders

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
    version = 9,
    exportSchema = true,
)
@TypeConverters(RoomTypeConverters::class)
internal abstract class AsteriskAppDatabase : RoomDatabase() {
    abstract fun appStateDao(): AppStateDao
}

internal val MIGRATION_8_9 = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
                CREATE TABLE `outbound_groups_new` (
                    `id` INTEGER NOT NULL,
                    `position` INTEGER NOT NULL,
                    `name` TEXT NOT NULL,
                    `url` TEXT NOT NULL,
                    `userAgent` TEXT NOT NULL,
                    `detour` TEXT NOT NULL DEFAULT '',
                    `updateInterval` TEXT NOT NULL,
                `requestHeaders` TEXT,
                    `updateViaProxy` INTEGER NOT NULL,
                    `ageSecretKey` TEXT NOT NULL,
                    `enabled` INTEGER NOT NULL,
                    `strictImport` INTEGER NOT NULL,
                    `lastUpdateAttemptAtMillis` INTEGER NOT NULL,
                    `lastUpdatedAtMillis` INTEGER NOT NULL,
                    `lastUpdateStatus` TEXT NOT NULL,
                    `lastUpdateImportedCount` INTEGER NOT NULL,
                    `lastUpdateSkippedCount` INTEGER NOT NULL,
                    `lastUpdateDuplicateCount` INTEGER NOT NULL,
                    `consecutiveUpdateFailures` INTEGER NOT NULL,
                    `lastUpdateErrorSummary` TEXT NOT NULL,
                    `subscriptionEtag` TEXT NOT NULL,
                    `subscriptionLastModified` TEXT NOT NULL,
                    `subscriptionUploadBytes` INTEGER NOT NULL,
                    `subscriptionDownloadBytes` INTEGER NOT NULL,
                    `subscriptionTotalBytes` INTEGER NOT NULL,
                    `subscriptionExpireAtSeconds` INTEGER NOT NULL,
                    PRIMARY KEY(`id`)
                )
            """.trimIndent(),
        )
        db.execSQL(
            """
                INSERT INTO outbound_groups_new (
                    id, position, name, url, userAgent,
                    detour, updateInterval, requestHeaders, updateViaProxy, ageSecretKey,
                    enabled, strictImport, lastUpdateAttemptAtMillis, lastUpdatedAtMillis, lastUpdateStatus,
                    lastUpdateImportedCount, lastUpdateSkippedCount, lastUpdateDuplicateCount, consecutiveUpdateFailures, lastUpdateErrorSummary,
                    subscriptionEtag, subscriptionLastModified, subscriptionUploadBytes, subscriptionDownloadBytes, subscriptionTotalBytes,
                    subscriptionExpireAtSeconds
                )
                SELECT
                    id, position, name, url, userAgent,
                detour, updateInterval, NULL, updateViaProxy, ageSecretKey,
                    enabled, strictImport, lastUpdateAttemptAtMillis, lastUpdatedAtMillis, lastUpdateStatus,
                    lastUpdateImportedCount, lastUpdateSkippedCount, lastUpdateDuplicateCount, consecutiveUpdateFailures, lastUpdateErrorSummary,
                    subscriptionEtag, subscriptionLastModified, subscriptionUploadBytes, subscriptionDownloadBytes, subscriptionTotalBytes,
                    subscriptionExpireAtSeconds
                FROM outbound_groups
            """.trimIndent(),
        )
        db.query("SELECT id, hwid FROM outbound_groups WHERE hwid <> ''").use { cursor ->
            db.compileStatement("UPDATE outbound_groups_new SET requestHeaders = ? WHERE id = ?").use { update ->
                while (cursor.moveToNext()) {
                    val headers = legacySubscriptionRequestHeaders(cursor.getString(1)) ?: continue
                    update.bindString(1, encodeSubscriptionRequestHeaders(headers))
                    update.bindLong(2, cursor.getLong(0))
                    update.executeUpdateDelete()
                }
            }
        }
        db.execSQL("DROP TABLE outbound_groups")
        db.execSQL("ALTER TABLE outbound_groups_new RENAME TO outbound_groups")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_outbound_groups_position ON outbound_groups(position)")
    }
}
