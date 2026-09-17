package com.perryhertler.cragmap.orientation

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** One cliff corridor: a LineString of sibling formation pins. */
data class Corridor(
    val parentUuid: String,
    val name: String,
    /** Vertex coordinates as (lat, lng) — converted from GeoJSON [lng, lat]. */
    val points: List<LatLngPoint>,
    /** Child area uuids aligned 1:1 with [points]. */
    val childUuids: List<String>
)

data class LatLngPoint(val lat: Double, val lng: Double)

data class RankedFormation(
    val uuid: String,
    val name: String,
    val lat: Double,
    val lng: Double,
    val distanceM: Double,
    val score: Double,
    val parentUuid: String,
    val parentName: String
)

/**
 * Pure orientation math for "Under me" formation shortlist.
 *
 * Heading heuristic: for the closest corridor segment, compute the signed
 * cross-track (which side of the directed segment the user is on). The
 * inward normal (from the user toward the line) is the facing direction of
 * the cliff face from the user's side. When [headingDeg] is present, boost
 * the score by how closely the user's heading aligns with that inward
 * normal (cos of heading error, floored at 0 — facing away does not help).
 * Distance still dominates: closer corridors beat far ones even with a
 * perfect heading boost.
 */
object CliffOrientation {

    private const val EARTH_RADIUS_M = 6_371_000.0

    fun haversineM(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
            sin(dLng / 2) * sin(dLng / 2)
        return 2 * EARTH_RADIUS_M * atan2(sqrt(a), sqrt(1 - a))
    }

    /** Local equirectangular meters relative to [originLat]/[originLng]. */
    fun toLocalMeters(lat: Double, lng: Double, originLat: Double, originLng: Double): Pair<Double, Double> {
        val x = Math.toRadians(lng - originLng) * cos(Math.toRadians(originLat)) * EARTH_RADIUS_M
        val y = Math.toRadians(lat - originLat) * EARTH_RADIUS_M
        return x to y
    }

    /**
     * Distance in meters from a point to a polyline, plus the index of the
     * nearest vertex (for formation lookup) and the closest segment endpoints
     * in local meters (for heading).
     */
    data class PolylineHit(
        val distanceM: Double,
        val nearestVertexIndex: Int,
        /** Unit-ish segment direction in local meters (dx, dy). */
        val segDx: Double,
        val segDy: Double,
        /** Signed cross-track in local meters: >0 means left of A→B. */
        val crossTrack: Double
    )

    fun distanceToPolyline(lat: Double, lng: Double, points: List<LatLngPoint>): PolylineHit {
        require(points.size >= 2) { "polyline needs ≥2 points" }
        var bestDist = Double.POSITIVE_INFINITY
        var bestVertex = 0
        var bestSegDx = 0.0
        var bestSegDy = 0.0
        var bestCross = 0.0

        for (i in 0 until points.size - 1) {
            val a = points[i]
            val b = points[i + 1]
            val (ax, ay) = toLocalMeters(a.lat, a.lng, lat, lng)
            val (bx, by) = toLocalMeters(b.lat, b.lng, lat, lng)
            // User is at local origin (0,0)
            val abx = bx - ax
            val aby = by - ay
            val abLen2 = abx * abx + aby * aby
            val t = if (abLen2 < 1e-12) 0.0 else {
                // projection of (0-a) onto ab = (-ax,-ay)·ab / |ab|^2
                val raw = ((-ax) * abx + (-ay) * aby) / abLen2
                min(1.0, max(0.0, raw))
            }
            val px = ax + t * abx
            val py = ay + t * aby
            val dist = sqrt(px * px + py * py)
            if (dist < bestDist) {
                bestDist = dist
                // Nearest vertex among the two endpoints of this segment
                // (prefer the closer of A/B; if projected mid-segment, pick
                // nearer endpoint by t).
                bestVertex = if (t < 0.5) i else i + 1
                bestSegDx = abx
                bestSegDy = aby
                // Signed cross-track: (P - A) × AB with P at local origin.
                // (-ax, -ay) × (abx, aby) = (-ax)*aby - (-ay)*abx
                bestCross = (-ax) * aby - (-ay) * abx
            }
        }
        return PolylineHit(bestDist, bestVertex, bestSegDx, bestSegDy, bestCross)
    }

    /**
     * Heading boost in [0, 1]: 1 when facing the cliff (toward the line),
     * 0 when facing away or parallel-enough that cos is negative.
     */
    fun headingBoost(headingDeg: Float?, hit: PolylineHit): Double {
        if (headingDeg == null) return 0.0
        val segLen = sqrt(hit.segDx * hit.segDx + hit.segDy * hit.segDy)
        if (segLen < 1e-6) return 0.0
        // Left-of-segment normal (rotated AB by +90°): (-dy, dx)
        val leftNx = -hit.segDy / segLen
        val leftNy = hit.segDx / segLen
        // User is on the left when crossTrack > 0. Inward (toward line from user)
        // is opposite the outward normal pointing to the user's side.
        val outwardNx = if (hit.crossTrack >= 0) leftNx else -leftNx
        val outwardNy = if (hit.crossTrack >= 0) leftNy else -leftNy
        val inwardNx = -outwardNx
        val inwardNy = -outwardNy
        // Bearing of inward normal: atan2(east, north) = atan2(x, y) in local meters
        val inwardBearingDeg = Math.toDegrees(atan2(inwardNx, inwardNy))
        val diff = smallestAngleDeg(headingDeg.toDouble(), inwardBearingDeg)
        return max(0.0, cos(Math.toRadians(diff)))
    }

    fun smallestAngleDeg(a: Double, b: Double): Double {
        var d = (a - b) % 360.0
        if (d < -180) d += 360
        if (d > 180) d -= 360
        return kotlin.math.abs(d)
    }

    /**
     * Rank formations near the user from cliff corridors.
     *
     * @param areasByUuid map of child (formation) uuid → (name, lat, lng)
     */
    fun rankFormations(
        lat: Double,
        lng: Double,
        headingDeg: Float?,
        corridors: List<Corridor>,
        areasByUuid: Map<String, Triple<String, Double, Double>>,
        maxResults: Int = 3,
        maxDistanceM: Double = 120.0
    ): List<RankedFormation> {
        val ranked = mutableListOf<RankedFormation>()
        for (corridor in corridors) {
            if (corridor.points.size < 2) continue
            val hit = distanceToPolyline(lat, lng, corridor.points)
            if (hit.distanceM > maxDistanceM) continue
            val idx = hit.nearestVertexIndex.coerceIn(0, corridor.childUuids.lastIndex)
            val childUuid = corridor.childUuids[idx]
            val area = areasByUuid[childUuid] ?: continue
            val (name, aLat, aLng) = area
            val boost = headingBoost(headingDeg, hit)
            // Closer is better; heading boost adds up to ~30% when facing the cliff.
            val score = (1.0 / (1.0 + hit.distanceM)) * (1.0 + 0.3 * boost)
            ranked += RankedFormation(
                uuid = childUuid,
                name = name,
                lat = aLat,
                lng = aLng,
                distanceM = hit.distanceM,
                score = score,
                parentUuid = corridor.parentUuid,
                parentName = corridor.name
            )
        }
        return ranked
            .sortedByDescending { it.score }
            .distinctBy { it.uuid }
            .take(maxResults)
    }
}
