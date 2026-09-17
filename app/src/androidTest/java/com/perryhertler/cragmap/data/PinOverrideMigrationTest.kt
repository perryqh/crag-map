package com.perryhertler.cragmap.data

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

/**
 * Proves PinOverrideDatabase schema bumps keep field pins intact and create
 * the photo tables — the whole reason we removed
 * fallbackToDestructiveMigration(). Runs on-device via MigrationTestHelper
 * against the checked-in app/schemas/ JSON.
 */
@RunWith(AndroidJUnit4::class)
class PinOverrideMigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        PinOverrideDatabase::class.java
    )

    @Test
    @Throws(IOException::class)
    fun migrate1To4_preservesPinAndCreatesPhotoTables() {
        helper.createDatabase(TEST_DB, 1).apply {
            execSQL(
                """
                INSERT INTO pin_override
                    (targetUuid, targetType, targetName, lat, lng, capturedAtMillis)
                VALUES
                    ('uuid-v1-pin', 'climb', 'Sample Climb', 43.41353, -89.7158, 1700000000000)
                """.trimIndent()
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(
            TEST_DB,
            4,
            true,
            *PIN_OVERRIDE_MIGRATIONS
        )
        assertPinSurvived(db, expectedUuid = "uuid-v1-pin")
        assertPhotoTablesEmpty(db)
    }

    @Test
    @Throws(IOException::class)
    fun migrate3To4_createsPhotoTablesWithoutDestroyingPins() {
        helper.createDatabase(TEST_DB, 3).apply {
            execSQL(
                """
                INSERT INTO pin_override
                    (targetUuid, targetType, targetName, lat, lng,
                     capturedAtMillis, fixAgeMillis, headingDegrees)
                VALUES
                    ('uuid-v3-pin', 'area', 'Sample Area', 43.41, -89.71,
                     1700000001000, 1500, 182.5)
                """.trimIndent()
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(
            TEST_DB,
            4,
            true,
            MIGRATION_3_4
        )

        db.query("SELECT * FROM pin_override WHERE targetUuid = 'uuid-v3-pin'").use { c ->
            assertTrue("pin row from v3 must survive 3→4", c.moveToFirst())
            assertEquals(1500L, c.getLong(c.getColumnIndexOrThrow("fixAgeMillis")))
            assertEquals(182.5f, c.getFloat(c.getColumnIndexOrThrow("headingDegrees")), 0.001f)
        }
        assertPhotoTablesEmpty(db)
    }

    private fun assertPinSurvived(db: SupportSQLiteDatabase, expectedUuid: String) {
        db.query("SELECT * FROM pin_override WHERE targetUuid = ?", arrayOf(expectedUuid)).use { c ->
            assertTrue("pin row must survive migrations to v4", c.moveToFirst())
            assertEquals("climb", c.getString(c.getColumnIndexOrThrow("targetType")))
            assertEquals("Sample Climb", c.getString(c.getColumnIndexOrThrow("targetName")))
            assertEquals(43.41353, c.getDouble(c.getColumnIndexOrThrow("lat")), 0.00001)
            assertEquals(-89.7158, c.getDouble(c.getColumnIndexOrThrow("lng")), 0.00001)
            assertEquals(1700000000000L, c.getLong(c.getColumnIndexOrThrow("capturedAtMillis")))
            // Added by 1→2 with DEFAULT 0
            assertEquals(0L, c.getLong(c.getColumnIndexOrThrow("fixAgeMillis")))
            // Added by 2→3 as nullable with no default → NULL for pre-existing rows
            assertTrue(c.isNull(c.getColumnIndexOrThrow("headingDegrees")))
        }
    }

    private fun assertPhotoTablesEmpty(db: SupportSQLiteDatabase) {
        db.query("SELECT COUNT(*) FROM photo_override").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(0, c.getInt(0))
        }
        db.query("SELECT COUNT(*) FROM photo_target").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(0, c.getInt(0))
        }
    }

    companion object {
        private const val TEST_DB = "pin_override_migration_test"
    }
}
