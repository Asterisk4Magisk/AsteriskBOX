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
    version = 10,
    exportSchema = true,
)

@TypeConverters(RoomTypeConverters::class)
internal abstract class AsteriskAppDatabase : RoomDatabase() {
    abstract fun appStateDao(): AppStateDao
}

internal val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "ALTER TABLE outbound_groups ADD COLUMN strictImport INTEGER NOT NULL DEFAULT 0",
        )
        db.execSQL(
            "ALTER TABLE outbound_groups ADD COLUMN lastUpdateAttemptAtMillis INTEGER NOT NULL DEFAULT 0",
        )
        db.execSQL(
            "ALTER TABLE outbound_groups ADD COLUMN lastUpdateStatus TEXT NOT NULL DEFAULT 'NEVER'",
        )
        db.execSQL(
            "ALTER TABLE outbound_groups ADD COLUMN lastUpdateImportedCount INTEGER NOT NULL DEFAULT 0",
        )
        db.execSQL(
            "ALTER TABLE outbound_groups ADD COLUMN lastUpdateSkippedCount INTEGER NOT NULL DEFAULT 0",
        )
        db.execSQL(
            "ALTER TABLE outbound_groups ADD COLUMN lastUpdateDuplicateCount INTEGER NOT NULL DEFAULT 0",
        )
        db.execSQL(
            "ALTER TABLE outbound_groups ADD COLUMN consecutiveUpdateFailures INTEGER NOT NULL DEFAULT 0",
        )
        db.execSQL(
            "ALTER TABLE outbound_groups ADD COLUMN lastUpdateErrorSummary TEXT NOT NULL DEFAULT ''",
        )
        db.execSQL(
            "ALTER TABLE outbound_groups ADD COLUMN subscriptionEtag TEXT NOT NULL DEFAULT ''",
        )
        db.execSQL(
            "ALTER TABLE outbound_groups ADD COLUMN subscriptionLastModified TEXT NOT NULL DEFAULT ''",
        )
        db.execSQL(
            """
                UPDATE outbound_groups
                SET lastUpdateAttemptAtMillis = lastUpdatedAtMillis,
                    lastUpdateStatus = 'SUCCESS'
                WHERE lastUpdatedAtMillis > 0
            """.trimIndent(),
        )
    }
}

internal val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS outbounds_new (
                id INTEGER NOT NULL,
                position INTEGER NOT NULL,
                groupId INTEGER NOT NULL,
                remarks TEXT NOT NULL,
                type TEXT NOT NULL,
                json TEXT NOT NULL,
                PRIMARY KEY(id)
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO outbounds_new(id, position, groupId, remarks, type, json)
            SELECT
                current.id,
                (
                    SELECT COUNT(*)
                    FROM outbounds AS preceding
                    WHERE preceding.groupId = current.groupId
                      AND (
                          preceding.position < current.position OR
                          (preceding.position = current.position AND preceding.id < current.id)
                      )
                ),
                current.groupId,
                current.remarks,
                current.type,
                current.json
            FROM outbounds AS current
            ORDER BY current.groupId, current.position, current.id
            """.trimIndent(),
        )
        db.execSQL("DROP TABLE outbounds")
        db.execSQL("ALTER TABLE outbounds_new RENAME TO outbounds")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_outbounds_groupId ON outbounds(groupId)")
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_outbounds_groupId_position ON outbounds(groupId, position)",
        )
    }
}

internal val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "ALTER TABLE outbound_groups ADD COLUMN subscriptionUploadBytes INTEGER NOT NULL DEFAULT 0",
        )
        db.execSQL(
            "ALTER TABLE outbound_groups ADD COLUMN subscriptionDownloadBytes INTEGER NOT NULL DEFAULT 0",
        )
        db.execSQL(
            "ALTER TABLE outbound_groups ADD COLUMN subscriptionTotalBytes INTEGER NOT NULL DEFAULT 0",
        )
        db.execSQL(
            "ALTER TABLE outbound_groups ADD COLUMN subscriptionExpireAtSeconds INTEGER NOT NULL DEFAULT 0",
        )
    }
}

internal val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE app_state_metadata ADD COLUMN bundledRuleSetsInitialized INTEGER NOT NULL DEFAULT 0")
    }
}

internal val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE outbound_groups ADD COLUMN detour TEXT NOT NULL DEFAULT ''")
    }
}

internal val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE selectors ADD COLUMN fallbackEnabled INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE selectors ADD COLUMN fallbackMaxDelay TEXT NOT NULL DEFAULT ''")
    }
}

internal val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE selectors ADD COLUMN nodeNameRegex TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE selectors ADD COLUMN matchOutboundGroupIds TEXT")
    }
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

internal val MIGRATION_9_10 = object : Migration(9, 10) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE outbound_groups ADD COLUMN landing TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE outbound_groups ADD COLUMN aliasFilter TEXT NOT NULL DEFAULT ''")
    }
}
