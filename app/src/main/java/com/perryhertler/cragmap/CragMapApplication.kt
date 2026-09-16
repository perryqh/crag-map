package com.perryhertler.cragmap

import android.app.Application
import com.perryhertler.cragmap.tiles.LocalTileServer
import org.maplibre.android.MapLibre

class CragMapApplication : Application() {

    lateinit var tileServer: LocalTileServer
        private set

    val tileServerPort = 8085

    override fun onCreate() {
        super.onCreate()
        MapLibre.getInstance(this)
        tileServer = LocalTileServer(this, tileServerPort)
        tileServer.ensureStarted()
    }
}
