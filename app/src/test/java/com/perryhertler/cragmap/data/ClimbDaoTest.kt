package com.perryhertler.cragmap.data

import android.app.Application
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Exercises the actual DAOs and the raw FTS search query the app runs at
 * runtime, against an in-memory Room database built the same way
 * AppDatabase.getInstance() does — minus createFromAsset, since this test
 * populates its own fixture rows instead of loading the bundled snapshot.
 *
 * climb_fts is deliberately not a Room @Entity (see AppDatabase's comment),
 * so it's created and populated here exactly as tools/openbeta_export.py
 * does: CREATE VIRTUAL TABLE ... USING fts4(...), then an explicit rebuild.
 */
// CragMapApplication.onCreate() loads MapLibre's native library and starts the
// tile server — neither works (or is needed) in a plain JVM unit test, so use
// the bare Application Robolectric ships instead of the manifest's real one.
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ClimbDaoTest {

    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .addCallback(object : RoomDatabase.Callback() {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL("CREATE VIRTUAL TABLE climb_fts USING fts4(name, yds_grade, content='climb')")
                }
            })
            // searchClimbs() runs a plain (non-suspend) query, same as production — the
            // app always calls it from Dispatchers.IO. Robolectric tests run single-threaded,
            // so allow it here purely to call that same production code path from the test.
            .allowMainThreadQueries()
            .build()

        val writable = db.openHelper.writableDatabase
        writable.execSQL(
            "INSERT INTO area (uuid, name, parent_uuid, depth, is_leaf, lat, lng, total_climbs) VALUES " +
                "('formation-1', '17: Hawk''s Nest', 'bluff-1', 3, 1, 43.41353, -89.7158, 3)"
        )
        writable.execSQL(
            "INSERT INTO area (uuid, name, parent_uuid, depth, is_leaf, lat, lng, total_climbs) VALUES " +
                "('bluff-1', 'East Bluff', NULL, 1, 0, 43.41, -89.71, 100)"
        )
        val climbs = listOf(
            Triple("Vivesection", "5.11a", "trad"),
            Triple("Scylla", "5.7", "trad"),
            Triple("Angina", "5.9", "trad"),
        )
        for ((name, grade, type) in climbs) {
            writable.execSQL(
                "INSERT INTO climb (uuid, area_uuid, name, yds_grade, climb_type, description, lat, lng) VALUES " +
                    "(?, 'formation-1', ?, ?, ?, NULL, 43.41353, -89.7158)",
                arrayOf<Any>(name, name, grade, type)
            )
        }
        writable.execSQL("INSERT INTO climb_fts(climb_fts) VALUES ('rebuild')")
    }

    @Test
    fun `areasAtDepth returns only areas at that depth`() = runBlocking {
        val bluffs = db.areaDao().areasAtDepth(1)
        assertEquals(1, bluffs.size)
        assertEquals("East Bluff", bluffs[0].name)
    }

    @Test
    fun `leafAreas is depth-agnostic and matches on is_leaf`() = runBlocking {
        val leaves = db.areaDao().leafAreas()
        assertEquals(listOf("formation-1"), leaves.map { it.uuid })
    }

    @Test
    fun `climbsInArea returns every climb under that formation, sorted by name`() = runBlocking {
        val climbs = db.climbDao().climbsInArea("formation-1")
        assertEquals(listOf("Angina", "Scylla", "Vivesection"), climbs.map { it.name })
    }

    @Test
    fun `search matches by name prefix`() {
        val results = db.searchClimbs("vive")
        assertEquals(listOf("Vivesection"), results.map { it.name })
    }

    @Test
    fun `search matches by grade prefix`() {
        val results = db.searchClimbs("5.11")
        assertEquals(listOf("Vivesection"), results.map { it.name })
    }

    @Test
    fun `search results carry the formation's coordinates for the camera jump`() {
        val result = db.searchClimbs("scylla").single()
        assertEquals(43.41353, result.lat!!, 0.0001)
        assertEquals(-89.7158, result.lng!!, 0.0001)
    }

    @Test
    fun `blank search returns nothing`() {
        assertEquals(emptyList<ClimbSearchResult>(), db.searchClimbs("   "))
    }
}
