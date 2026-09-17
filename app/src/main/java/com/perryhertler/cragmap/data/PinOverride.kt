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
import java.io.File

/**
 * A fix older than this when "set pin" is tapped is flagged rather than
 * trusted silently — under cliff/canopy the location engine's last reading
 * can be stale by minutes, and a stale fix captured as if fresh would only
 * surface as a wrong pin during the desk merge, far from the rock to re-check.
 */
const val STALE_FIX_THRESHOLD_MILLIS = 20_000L

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
    val capturedAtMillis: Long,
    // Age of the GPS fix itself at capture time, not how long ago the capture
    // happened — see STALE_FIX_THRESHOLD_MILLIS. Kept in the export so a
    // suspect pin is visible at the desk merge too, not just on the phone.
    val fixAgeMillis: Long = 0,
    // Compass heading (0-360, 0=north) the phone was facing at capture time —
    // which way the wall was, for telling a formation's faces apart later.
    // Null if no rotation sensor reading arrived in time (see readHeadingOnce).
    val headingDegrees: Float? = null
)

@Dao
interface PinOverrideDao {
    @Query("SELECT * FROM pin_override ORDER BY capturedAtMillis")
    suspend fun all(): List<PinOverrideEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: PinOverrideEntity)

    @Query("DELETE FROM pin_override WHERE targetUuid = :targetUuid")
    suspend fun delete(targetUuid: String)
}

/**
 * A field-captured photo (Edit Mode, same gating as pin capture — decided in
 * the blueprint's "make photos available" discussion: these travel through
 * the same offline capture -> export -> desk-merge pipeline as GPS
 * overrides, never a live upload).
 *
 * One photo commonly covers several climbs on the same formation — a few
 * meters apart on one wall, all in frame from a single shot — so which
 * area/climb(s) it shows lives in the separate PhotoTargetEntity join table,
 * not a column here. A future step (not this one) is annotating the photo
 * itself with route lines/names, Mountain-Project-topo style; today it's
 * just the raw photo plus which targets it's tagged to.
 *
 * filePath points at a file already inside app-private storage
 * (filesDir/photos/), written directly by the camera app via the
 * FileProvider Uri handed to it — never a content:// Uri kept long-term,
 * since those aren't guaranteed stable across app restarts.
 */
@Entity(tableName = "photo_override")
data class PhotoOverrideEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val filePath: String,
    val capturedAtMillis: Long
)

/** One row per area/climb a captured photo (photoId) is tagged to. */
@Entity(tableName = "photo_target", primaryKeys = ["photoId", "targetUuid"])
data class PhotoTargetEntity(
    val photoId: Long,
    val targetUuid: String,
    val targetType: String, // "area" or "climb"
    val targetName: String
)

data class PhotoWithTargets(val photo: PhotoOverrideEntity, val targets: List<PhotoTargetEntity>)

@Dao
interface PhotoOverrideDao {
    @Query("SELECT * FROM photo_override ORDER BY capturedAtMillis")
    suspend fun allPhotos(): List<PhotoOverrideEntity>

    @Insert
    suspend fun insertPhoto(entity: PhotoOverrideEntity): Long

    @Query("DELETE FROM photo_override WHERE id = :photoId")
    suspend fun deletePhoto(photoId: Long)

    @Insert
    suspend fun insertTargets(targets: List<PhotoTargetEntity>)

    @Query("SELECT * FROM photo_target")
    suspend fun allTargets(): List<PhotoTargetEntity>

    @Query("SELECT * FROM photo_target WHERE targetUuid = :targetUuid")
    suspend fun targetsFor(targetUuid: String): List<PhotoTargetEntity>

    // photo_target has no foreign-key cascade, so deleting a photo needs this
    // run alongside deletePhoto — see the deletePhotoAndTargets helper below.
    @Query("DELETE FROM photo_target WHERE photoId = :photoId")
    suspend fun deleteTargetsForPhoto(photoId: Long)
}

suspend fun PhotoOverrideDao.deletePhotoAndTargets(photoId: Long) {
    deleteTargetsForPhoto(photoId)
    deletePhoto(photoId)
}

suspend fun PhotoOverrideDao.allWithTargets(): List<PhotoWithTargets> {
    val photos = allPhotos()
    val grouped = allTargets().groupBy { it.photoId }
    return photos.map { PhotoWithTargets(it, grouped[it.id].orEmpty()) }
}

@Database(
    entities = [PinOverrideEntity::class, PhotoOverrideEntity::class, PhotoTargetEntity::class],
    version = 4,
    exportSchema = false
)
abstract class PinOverrideDatabase : RoomDatabase() {
    abstract fun pinOverrideDao(): PinOverrideDao
    abstract fun photoOverrideDao(): PhotoOverrideDao

    companion object {
        @Volatile private var instance: PinOverrideDatabase? = null

        fun getInstance(context: Context): PinOverrideDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    PinOverrideDatabase::class.java,
                    "pin_overrides.db"
                )
                    .fallbackToDestructiveMigration()
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
            """"capturedAtMillis":${o.capturedAtMillis},""" +
            """"fixAgeMillis":${o.fixAgeMillis},""" +
            """"headingDegrees":${o.headingDegrees ?: "null"}}"""
    }
    return "[$items]"
}

/**
 * Manifest for the photos bundled into the same export zip as
 * pin_overrides.json (see MapScreen's exportOverrides). fileName (not the
 * on-device absolute filePath) is what ties a manifest entry to the actual
 * "photos/<fileName>" entry in that zip. Each photo carries a list of
 * targets, not one — tools/merge_photo_overrides.py copies the same photo
 * file into every tagged target's assets/photos/<uuid>/ folder.
 */
fun photoOverridesToJson(photos: List<PhotoWithTargets>): String {
    val items = photos.joinToString(",") { pwt ->
        val targetsJson = pwt.targets.joinToString(",") { t ->
            """{"targetUuid":"${jsonEscape(t.targetUuid)}",""" +
                """"targetType":"${jsonEscape(t.targetType)}",""" +
                """"targetName":"${jsonEscape(t.targetName)}"}"""
        }
        """{"fileName":"${jsonEscape(File(pwt.photo.filePath).name)}",""" +
            """"capturedAtMillis":${pwt.photo.capturedAtMillis},""" +
            """"targets":[$targetsJson]}"""
    }
    return "[$items]"
}

private fun jsonEscape(s: String): String = s.replace("\\", "\\\\").replace("\"", "\\\"")
