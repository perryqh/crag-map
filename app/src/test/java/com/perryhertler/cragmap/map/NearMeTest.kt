package com.perryhertler.cragmap.map

import com.perryhertler.cragmap.data.AreaEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NearMeTest {

    private fun area(uuid: String, lat: Double?, lng: Double?) =
        AreaEntity(uuid, uuid, "parent-uuid", 3, 1, lat, lng, 5)

    @Test
    fun `haversine of a point to itself is zero`() {
        assertEquals(0.0, haversineMeters(43.41353, -89.7158, 43.41353, -89.7158), 0.001)
    }

    @Test
    fun `haversine approximates one degree of latitude as about 111km`() {
        // A well-known constant (lines of latitude are equally spaced everywhere)
        // — a safe way to sanity-check the formula without depending on any
        // specific crag's real coordinates.
        val d = haversineMeters(43.0, -89.0, 44.0, -89.0)
        assertTrue("expected ~111km, got $d", d in 110_000.0..112_000.0)
    }

    @Test
    fun `rankNearbyFormations excludes anything past the max distance`() {
        val near = area("near", 43.41353, -89.7158)
        val far = area("far", 44.0, -90.0)
        val ranked = rankNearbyFormations(43.41353, -89.7158, listOf(near, far), maxDistanceM = 150.0)
        assertEquals(listOf("near"), ranked.map { it.area.uuid })
    }

    @Test
    fun `rankNearbyFormations sorts closest first`() {
        val farther = area("farther", 43.4140, -89.7160)
        val closer = area("closer", 43.41355, -89.71581)
        val ranked = rankNearbyFormations(43.41353, -89.7158, listOf(farther, closer), maxDistanceM = 1000.0)
        assertEquals(listOf("closer", "farther"), ranked.map { it.area.uuid })
    }

    @Test
    fun `rankNearbyFormations caps to maxResults`() {
        val areas = (1..10).map { area("a$it", 43.41353 + it * 0.0001, -89.7158) }
        val ranked = rankNearbyFormations(43.41353, -89.7158, areas, maxDistanceM = 10_000.0, maxResults = 3)
        assertEquals(3, ranked.size)
    }

    @Test
    fun `areas missing coordinates are skipped, not crashed on`() {
        val noCoords = area("no-coords", null, null)
        val ranked = rankNearbyFormations(43.41353, -89.7158, listOf(noCoords))
        assertEquals(emptyList<NearbyFormation>(), ranked)
    }
}
