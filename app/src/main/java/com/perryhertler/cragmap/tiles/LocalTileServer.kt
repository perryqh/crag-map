package com.perryhertler.cragmap.tiles

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream

/**
 * Serves tiles out of a bundled MBTiles (SQLite) file over 127.0.0.1, so
 * MapLibre's URL-template raster source can read a pre-built local tile
 * package. MapLibre's own OfflineManager/OfflineRegion API expects a live
 * style URL and doesn't fit "install a file I built myself" — see the
 * blueprint's "Serving the MBTiles to MapLibre" section for why this exists.
 */
class LocalTileServer(private val context: Context, listenPort: Int = 8085) : NanoHTTPD(listenPort) {

    private val db: SQLiteDatabase by lazy {
        val dest = File(context.filesDir, "devils_lake.mbtiles")
        // Same class of bug the Room database hit: copying only "if it doesn't
        // already exist" means a rebuilt/updated bundled asset (new bbox, more
        // tiles, etc.) never reaches an existing install — the internal copy
        // from months-old first install would just sit there forever, silently
        // stale. Comparing sizes catches any asset change without needing a
        // manually-maintained version number.
        val assetSize = context.assets.openFd("devils_lake.mbtiles").use { it.length }
        if (!dest.exists() || dest.length() != assetSize) {
            context.assets.open("devils_lake.mbtiles").use { input ->
                FileOutputStream(dest).use { output -> input.copyTo(output) }
            }
        }
        SQLiteDatabase.openDatabase(dest.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
    }

    fun ensureStarted() {
        db // force lazy copy+open before serving the first request
        val alreadyRunning = try {
            isAlive
        } catch (e: Exception) {
            false
        }
        if (!alreadyRunning) {
            start(SOCKET_READ_TIMEOUT, false)
        }
    }

    override fun serve(session: IHTTPSession): Response {
        // expects /tiles/{z}/{x}/{y}.jpg  (standard XYZ scheme, as MapLibre requests it)
        val parts = session.uri.trim('/').split("/")
        if (parts.size != 4 || parts[0] != "tiles") {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "not found: ${session.uri}")
        }
        return try {
            val z = parts[1].toInt()
            val x = parts[2].toInt()
            val y = parts[3].substringBefore(".").toInt()
            val tmsRow = (1 shl z) - 1 - y // MBTiles stores rows TMS-style (flipped from XYZ)

            db.rawQuery(
                "SELECT tile_data FROM tiles WHERE zoom_level = ? AND tile_column = ? AND tile_row = ?",
                arrayOf(z.toString(), x.toString(), tmsRow.toString())
            ).use { cursor ->
                if (cursor.moveToFirst()) {
                    val bytes = cursor.getBlob(0)
                    newFixedLengthResponse(
                        Response.Status.OK,
                        "image/jpeg",
                        ByteArrayInputStream(bytes),
                        bytes.size.toLong()
                    )
                } else {
                    Log.w("CragMap", "tile 404: z=$z x=$x y=$y (tmsRow=$tmsRow)")
                    newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "no tile at z=$z x=$x y=$y")
                }
            }
        } catch (e: Exception) {
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", e.message ?: "error")
        }
    }
}
