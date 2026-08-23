// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

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
