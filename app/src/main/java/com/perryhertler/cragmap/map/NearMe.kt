package com.perryhertler.cragmap.map

import com.perryhertler.cragmap.data.AreaEntity
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

data class NearbyFormation(val area: AreaEntity, val distanceMeters: Double)

fun haversineMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
    val earthRadiusM = 6_371_000.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLng = Math.toRadians(lng2 - lng1)
    val a = sin(dLat / 2).pow(2) +
        cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2).pow(2)
    val c = 2 * atan2(sqrt(a), sqrt(1 - a))
    return earthRadiusM * c
}

/**
 * Honest "near me": ranks formations by straight-line distance from a single
 * on-demand GPS fix, capped to a short radius so it never claims a formation
 * hundreds of meters away is "nearby". This is deliberately just a distance
 * shortlist, not a claim about which specific climb you're under — every
 * route on a formation shares one lat/lng (see addAreaLayer's comment), so
 * GPS alone can never resolve further than "this wall".
 */
fun rankNearbyFormations(
    lat: Double,
    lng: Double,
    formations: List<AreaEntity>,
    maxDistanceM: Double = 150.0,
    maxResults: Int = 5
): List<NearbyFormation> {
    return formations
        .mapNotNull { area ->
            val areaLat = area.lat ?: return@mapNotNull null
            val areaLng = area.lng ?: return@mapNotNull null
            val distance = haversineMeters(lat, lng, areaLat, areaLng)
            if (distance <= maxDistanceM) NearbyFormation(area, distance) else null
        }
        .sortedBy { it.distanceMeters }
        .take(maxResults)
}
