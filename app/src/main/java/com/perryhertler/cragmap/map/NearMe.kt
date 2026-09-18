package com.perryhertler.cragmap.map

import com.perryhertler.cragmap.data.AreaEntity
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

data class NearbyFormation(
    val area: AreaEntity,
    val distanceMeters: Double,
    /** Degrees clockwise from north to the formation, 0..360. */
    val bearingDegrees: Double,
)

fun haversineMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
    val earthRadiusM = 6_371_000.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLng = Math.toRadians(lng2 - lng1)
    val a = sin(dLat / 2).pow(2) +
        cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2).pow(2)
    val c = 2 * atan2(sqrt(a), sqrt(1 - a))
    return earthRadiusM * c
}

/** Initial bearing from (lat1,lng1) to (lat2,lng2), degrees clockwise from north. */
fun bearingDegrees(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
    val φ1 = Math.toRadians(lat1)
    val φ2 = Math.toRadians(lat2)
    val Δλ = Math.toRadians(lng2 - lng1)
    val y = sin(Δλ) * cos(φ2)
    val x = cos(φ1) * sin(φ2) - sin(φ1) * cos(φ2) * cos(Δλ)
    val θ = atan2(y, x)
    return (Math.toDegrees(θ) + 360.0) % 360.0
}

private val CARDINALS = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")

/** 8-wind compass label for a bearing in degrees. */
fun cardinalDirection(bearingDegrees: Double): String {
    val normalized = ((bearingDegrees % 360.0) + 360.0) % 360.0
    val index = ((normalized + 22.5) / 45.0).toInt() % 8
    return CARDINALS[index]
}

/**
 * Honest "near me": ranks formations by straight-line distance from a single
 * on-demand GPS fix, capped to a short radius so it never claims a formation
 * hundreds of meters away is "nearby". This is deliberately just a distance
 * shortlist, not a claim about which specific climb you're under — every
 * route on a formation shares one lat/lng, so GPS alone can never resolve
 * further than "this wall".
 */
fun rankNearbyFormations(
    lat: Double,
    lng: Double,
    formations: List<AreaEntity>,
    maxDistanceM: Double = 150.0,
    maxResults: Int = 5,
): List<NearbyFormation> {
    return formations
        .mapNotNull { area ->
            val areaLat = area.lat ?: return@mapNotNull null
            val areaLng = area.lng ?: return@mapNotNull null
            val distance = haversineMeters(lat, lng, areaLat, areaLng)
            if (distance <= maxDistanceM) {
                NearbyFormation(
                    area = area,
                    distanceMeters = distance,
                    bearingDegrees = bearingDegrees(lat, lng, areaLat, areaLng),
                )
            } else {
                null
            }
        }
        .sortedBy { it.distanceMeters }
        .take(maxResults)
}

/**
 * Short facing hint: how the phone's compass heading relates to a formation
 * bearing. Returns null when heading is unknown. Deliberately coarse.
 */
fun facingHint(headingDegrees: Float?, bearingDegrees: Double): String? {
    if (headingDegrees == null) return null
    var delta = bearingDegrees - headingDegrees
    while (delta > 180) delta -= 360
    while (delta < -180) delta += 360
    return when {
        kotlin.math.abs(delta) <= 30 -> "ahead"
        delta in 30.0..150.0 -> "to your right"
        delta < -30 && delta >= -150 -> "to your left"
        else -> "behind you"
    }
}
