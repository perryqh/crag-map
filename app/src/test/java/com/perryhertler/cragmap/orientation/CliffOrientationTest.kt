package com.perryhertler.cragmap.orientation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for cliff corridor distance / ranking — no Robolectric.
 */
class CliffOrientationTest {

    private val eastWestCorridor = Corridor(
        parentUuid = "p-ew",
        name = "EW Wall",
        // Three pins along an E-W line at lat 43.0
        points = listOf(
            LatLngPoint(43.0, -89.60),
            LatLngPoint(43.0, -89.50),
            LatLngPoint(43.0, -89.40)
        ),
        childUuids = listOf("c-west", "c-mid", "c-east")
    )

    private val areas = mapOf(
        "c-west" to Triple("West Pin", 43.0, -89.60),
        "c-mid" to Triple("Mid Pin", 43.0, -89.50),
        "c-east" to Triple("East Pin", 43.0, -89.40)
    )

    @Test
    fun haversine_knownShortDistance() {
        // ~111m per 0.001° lat
        val d = CliffOrientation.haversineM(43.0, -89.5, 43.001, -89.5)
        assertTrue("expected ~111m, got $d", d in 100.0..120.0)
    }

    @Test
    fun distanceToPolyline_pointOnSouthSide() {
        // 50m south of mid vertex (approx 0.00045° lat)
        val lat = 43.0 - 0.00045
        val lng = -89.50
        val hit = CliffOrientation.distanceToPolyline(lat, lng, eastWestCorridor.points)
        assertTrue("distance ~50m, got ${hit.distanceM}", hit.distanceM in 40.0..60.0)
        assertEquals(1, hit.nearestVertexIndex)
    }

    @Test
    fun rankFormations_filtersBeyondMaxDistance() {
        // Far south of the corridor
        val ranked = CliffOrientation.rankFormations(
            lat = 42.0,
            lng = -89.50,
            headingDeg = null,
            corridors = listOf(eastWestCorridor),
            areasByUuid = areas,
            maxDistanceM = 120.0
        )
        assertTrue(ranked.isEmpty())
    }

    @Test
    fun rankFormations_returnsNearestChildWithoutHeading() {
        val lat = 43.0 - 0.0003 // ~33m south of mid
        val lng = -89.50
        val ranked = CliffOrientation.rankFormations(
            lat = lat,
            lng = lng,
            headingDeg = null,
            corridors = listOf(eastWestCorridor),
            areasByUuid = areas,
            maxResults = 3,
            maxDistanceM = 120.0
        )
        assertEquals(1, ranked.size)
        assertEquals("c-mid", ranked[0].uuid)
        assertTrue(ranked[0].distanceM < 50.0)
    }

    @Test
    fun rankFormations_headingTowardCliffBoostsScore() {
        val lat = 43.0 - 0.0003 // south of line
        val lng = -89.50
        // Facing north (0°) → toward the E-W cliff from the south
        val facing = CliffOrientation.rankFormations(
            lat, lng, 0f, listOf(eastWestCorridor), areas
        )
        val facingAway = CliffOrientation.rankFormations(
            lat, lng, 180f, listOf(eastWestCorridor), areas
        )
        assertEquals(1, facing.size)
        assertEquals(1, facingAway.size)
        assertTrue(
            "facing score ${facing[0].score} should beat facing-away ${facingAway[0].score}",
            facing[0].score > facingAway[0].score
        )
    }

    @Test
    fun smallestAngleDeg_wraps() {
        assertEquals(10.0, CliffOrientation.smallestAngleDeg(5.0, 355.0), 1e-6)
        assertEquals(0.0, CliffOrientation.smallestAngleDeg(0.0, 360.0), 1e-6)
    }
}
