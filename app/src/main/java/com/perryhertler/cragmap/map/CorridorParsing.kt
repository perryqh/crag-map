package com.perryhertler.cragmap.map

import com.perryhertler.cragmap.data.CliffCorridorEntity
import com.perryhertler.cragmap.orientation.Corridor
import com.perryhertler.cragmap.orientation.LatLngPoint
import org.json.JSONArray
import org.json.JSONObject

/** Parse a Room cliff_corridor row into an orientation [Corridor]. */
fun CliffCorridorEntity.toCorridor(): Corridor? {
    return try {
        val root = JSONObject(geojson)
        val coords: JSONArray = when (root.optString("type")) {
            "Feature" -> root.getJSONObject("geometry").getJSONArray("coordinates")
            "LineString" -> root.getJSONArray("coordinates")
            else -> {
                // Bare FeatureCollection not expected; try geometry.coordinates
                root.optJSONObject("geometry")?.getJSONArray("coordinates")
                    ?: return null
            }
        }
        val points = mutableListOf<LatLngPoint>()
        for (i in 0 until coords.length()) {
            val pair = coords.getJSONArray(i)
            val lng = pair.getDouble(0)
            val lat = pair.getDouble(1)
            points += LatLngPoint(lat, lng)
        }
        val childUuids = mutableListOf<String>()
        val arr = JSONArray(childUuidsJson)
        for (i in 0 until arr.length()) childUuids += arr.getString(i)
        if (points.size < 2 || points.size != childUuids.size) return null
        Corridor(
            parentUuid = parentUuid,
            name = name,
            points = points,
            childUuids = childUuids
        )
    } catch (_: Exception) {
        null
    }
}
