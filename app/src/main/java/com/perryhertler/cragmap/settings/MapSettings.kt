package com.perryhertler.cragmap.settings

import android.content.Context

private const val PREFS = "crag_atlas_map"
private const val KEY_SHOW_LABELS = "show_puck_labels"
private const val KEY_BASEMAP = "basemap"

enum class Basemap(val storageKey: String, val tilePath: String, val maxZoom: Double) {
    IMAGERY("imagery", "imagery", 18.0),
    TOPO("topo", "topo", 16.0);

    companion object {
        fun fromStorage(raw: String?): Basemap =
            entries.firstOrNull { it.storageKey == raw } ?: IMAGERY
    }
}

data class MapSettings(
    val showPuckLabels: Boolean = true,
    val basemap: Basemap = Basemap.IMAGERY,
)

fun loadMapSettings(context: Context): MapSettings {
    val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    return MapSettings(
        showPuckLabels = prefs.getBoolean(KEY_SHOW_LABELS, true),
        basemap = Basemap.fromStorage(prefs.getString(KEY_BASEMAP, Basemap.IMAGERY.storageKey)),
    )
}

fun saveMapSettings(context: Context, settings: MapSettings) {
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        .putBoolean(KEY_SHOW_LABELS, settings.showPuckLabels)
        .putString(KEY_BASEMAP, settings.basemap.storageKey)
        .apply()
}
