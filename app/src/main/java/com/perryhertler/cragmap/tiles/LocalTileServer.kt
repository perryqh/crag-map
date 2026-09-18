package com.perryhertler.cragmap.tiles

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream

/**
 * Serves tiles out of bundled MBTiles packs over 127.0.0.1 so MapLibre's
 * URL-template raster sources can read offline packages.
 *
 * Paths:
 *   /tiles/imagery/{z}/{x}/{y}.jpg  — USGS ImageryTopo + NAIP (z13–18)
 *   /tiles/topo/{z}/{x}/{y}.jpg     — USGS Topo (z13–16)
 *
 * Legacy /tiles/{z}/{x}/{y}.jpg still maps to imagery.
 */
class LocalTileServer(private val context: Context, listenPort: Int = 8085) : NanoHTTPD(listenPort) {

    private val imageryDb: SQLiteDatabase by lazy {
        openPack("devils_lake_imagery.mbtiles", "devils_lake_imagery.mbtiles")
    }
    private val topoDb: SQLiteDatabase by lazy {
        openPack("devils_lake_topo.mbtiles", "devils_lake_topo.mbtiles")
    }

    private fun openPack(assetName: String, destName: String): SQLiteDatabase {
        val dest = File(context.filesDir, destName)
        val assetSize = context.assets.openFd(assetName).use { it.length }
        if (!dest.exists() || dest.length() != assetSize) {
            context.assets.open(assetName).use { input ->
                FileOutputStream(dest).use { output -> input.copyTo(output) }
            }
        }
        return SQLiteDatabase.openDatabase(dest.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
    }

    fun ensureStarted() {
        imageryDb
        topoDb
        val alreadyRunning = try {
            isAlive
        } catch (_: Exception) {
            false
        }
        if (!alreadyRunning) {
            start(SOCKET_READ_TIMEOUT, false)
        }
    }

    override fun serve(session: IHTTPSession): Response {
        val parts = session.uri.trim('/').split('/')
        // /tiles/{pack}/{z}/{x}/{y}.jpg  OR legacy /tiles/{z}/{x}/{y}.jpg
        val (pack, z, x, y) = when {
            parts.size == 5 && parts[0] == "tiles" ->
                Quadruple(parts[1], parts[2].toIntOrNull(), parts[3].toIntOrNull(), parts[4].substringBefore('.').toIntOrNull())
            parts.size == 4 && parts[0] == "tiles" ->
                Quadruple("imagery", parts[1].toIntOrNull(), parts[2].toIntOrNull(), parts[3].substringBefore('.').toIntOrNull())
            else ->
                return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "not found: ${session.uri}")
        }
        if (z == null || x == null || y == null) {
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "bad tile path")
        }
        val db = when (pack) {
            "topo" -> topoDb
            else -> imageryDb
        }
        return try {
            val tmsRow = (1 shl z) - 1 - y
            db.rawQuery(
                "SELECT tile_data FROM tiles WHERE zoom_level = ? AND tile_column = ? AND tile_row = ?",
                arrayOf(z.toString(), x.toString(), tmsRow.toString()),
            ).use { cursor ->
                if (cursor.moveToFirst()) {
                    val bytes = cursor.getBlob(0)
                    newFixedLengthResponse(
                        Response.Status.OK,
                        "image/jpeg",
                        ByteArrayInputStream(bytes),
                        bytes.size.toLong(),
                    )
                } else {
                    Log.w("CragMap", "tile 404: pack=$pack z=$z x=$x y=$y")
                    newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "no tile")
                }
            }
        } catch (e: Exception) {
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", e.message ?: "error")
        }
    }

    private data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)
}
