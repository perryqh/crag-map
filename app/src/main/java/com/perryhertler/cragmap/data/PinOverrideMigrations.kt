package com.perryhertler.cragmap.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Explicit Room migrations for [PinOverrideDatabase].
 *
 * Field pins/photos live in a writable DB that survives across app updates.
 * Destructive fallback would wipe that on any schema bump — the opposite of
 * what we want for data captured offline at the crag. These migrations keep
 * existing rows intact when the schema grows.
 *
 * Column names match Kotlin property names on the entities (no @ColumnInfo
 * renames). Keep SQL here in lockstep with those property names.
 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // v2: record GPS fix age at capture so stale readings can be flagged
        // both on-device and at the desk merge (see STALE_FIX_THRESHOLD_MILLIS).
        db.execSQL(
            "ALTER TABLE pin_override ADD COLUMN fixAgeMillis INTEGER NOT NULL DEFAULT 0"
        )
    }
}

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // v3: optional compass heading at capture (null when no reading arrived).
        db.execSQL(
            "ALTER TABLE pin_override ADD COLUMN headingDegrees REAL"
        )
    }
}

val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // v4: field photos + multi-target tags. New tables only — pin rows
        // must survive unchanged.
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS photo_override (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                filePath TEXT NOT NULL,
                capturedAtMillis INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS photo_target (
                photoId INTEGER NOT NULL,
                targetUuid TEXT NOT NULL,
                targetType TEXT NOT NULL,
                targetName TEXT NOT NULL,
                PRIMARY KEY(photoId, targetUuid)
            )
            """.trimIndent()
        )
    }
}

val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // v5: base vs top capture stance (default base for existing rows).
        db.execSQL(
            "ALTER TABLE pin_override ADD COLUMN stance TEXT NOT NULL DEFAULT 'base'"
        )
    }
}

/** All known PinOverrideDatabase migrations, newest last. */
val PIN_OVERRIDE_MIGRATIONS = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
