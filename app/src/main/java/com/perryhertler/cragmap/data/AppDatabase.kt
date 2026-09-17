package com.perryhertler.cragmap.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SimpleSQLiteQuery

data class ClimbSearchResult(
    val uuid: String,
    val areaUuid: String,
    val name: String,
    val ydsGrade: String?,
    val climbType: String?,
    val lat: Double?,
    val lng: Double?
)

// Bump this version any time the bundled asset's schema changes (e.g. a new
// column on an entity). Room's createFromAsset() only copies the asset into
// the app's internal database on first install — on an existing install it
// just opens whatever's already there, then throws on the identity-hash
// mismatch this causes if the version wasn't also bumped. Since this whole
// database is a read-only bundled snapshot (never user-written), destructive
// migration — drop and recopy from the asset — is exactly the right recovery.
@Database(entities = [AreaEntity::class, ClimbEntity::class], version = 3, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun areaDao(): AreaDao
    abstract fun climbDao(): ClimbDao

    /**
     * climb_fts (FTS4) is created directly by tools/openbeta_export.py and is
     * deliberately NOT modeled as a Room @Entity, so Room's compile-time query
     * validation never needs to know its shape. Queried raw instead.
     */
    fun searchClimbs(term: String): List<ClimbSearchResult> {
        val trimmed = term.trim()
        if (trimmed.isEmpty()) return emptyList()
        val cursor = query(
            SimpleSQLiteQuery(
                "SELECT climb.uuid, climb.area_uuid, climb.name, climb.yds_grade, " +
                    "climb.climb_type, climb.lat, climb.lng " +
                    "FROM climb_fts JOIN climb ON climb.rowid = climb_fts.rowid " +
                    "WHERE climb_fts MATCH ? LIMIT 25",
                arrayOf("$trimmed*")
            ),
            null
        )
        val results = mutableListOf<ClimbSearchResult>()
        cursor.use {
            while (it.moveToNext()) {
                results += ClimbSearchResult(
                    uuid = it.getString(0),
                    areaUuid = it.getString(1),
                    name = it.getString(2),
                    ydsGrade = it.getString(3),
                    climbType = it.getString(4),
                    lat = if (it.isNull(5)) null else it.getDouble(5),
                    lng = if (it.isNull(6)) null else it.getDouble(6)
                )
            }
        }
        return results
    }

    companion object {
        @Volatile private var instance: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "devils_lake.db"
                )
                    .createFromAsset("devils_lake.db")
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { instance = it }
            }
    }
}
