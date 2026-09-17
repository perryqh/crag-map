package com.perryhertler.cragmap.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * A field-captured GPS pin for one area or climb, taken with the app's Edit
 * Mode (Phase 3: East Rampart baseline pack) to replace OpenBeta's rough
 * centroid with a real on-the-ground reading. Lives in its own writable
 * database — never the asset-backed AppDatabase, which is destructively
 * recreated any time its bundled snapshot changes (see AppDatabase's
 * version-bump comment) and would silently wipe field work.
 */
@Entity(tableName = "pin_override")
data class PinOverrideEntity(
    @PrimaryKey val targetUuid: String,
    val targetType: String, // "area" or "climb"
    val targetName: String,
    val lat: Double,
    val lng: Double,
    val capturedAtMillis: Long
)

@Dao
interface PinOverrideDao {
    @Query("SELECT * FROM pin_override ORDER BY capturedAtMillis")
    suspend fun all(): List<PinOverrideEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: PinOverrideEntity)
}

@Database(entities = [PinOverrideEntity::class], version = 1, exportSchema = false)
abstract class PinOverrideDatabase : RoomDatabase() {
    abstract fun pinOverrideDao(): PinOverrideDao

    companion object {
        @Volatile private var instance: PinOverrideDatabase? = null

        fun getInstance(context: Context): PinOverrideDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    PinOverrideDatabase::class.java,
                    "pin_overrides.db"
                )
                    .build()
                    .also { instance = it }
            }
    }
}

/**
 * Serializes captured overrides for the desktop merge step
 * (tools/merge_pin_overrides.py). Hand-built rather than via org.json so this
 * stays a plain, JVM-testable function — org.json's real implementation only
 * exists on-device/under Robolectric.
 */
fun overridesToJson(overrides: List<PinOverrideEntity>): String {
    val items = overrides.joinToString(",") { o ->
        """{"targetUuid":"${jsonEscape(o.targetUuid)}",""" +
            """"targetType":"${jsonEscape(o.targetType)}",""" +
            """"targetName":"${jsonEscape(o.targetName)}",""" +
            """"lat":${o.lat},"lng":${o.lng},""" +
            """"capturedAtMillis":${o.capturedAtMillis}}"""
    }
    return "[$items]"
}

private fun jsonEscape(s: String): String = s.replace("\\", "\\\\").replace("\"", "\\\"")
