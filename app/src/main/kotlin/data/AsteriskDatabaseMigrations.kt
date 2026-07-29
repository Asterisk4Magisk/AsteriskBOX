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
