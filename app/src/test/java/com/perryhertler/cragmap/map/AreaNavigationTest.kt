package com.perryhertler.cragmap.map

import com.perryhertler.cragmap.data.AreaEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class AreaNavigationTest {

    private fun area(uuid: String, lat: Double, lng: Double) =
        AreaEntity(uuid, uuid, "parent-uuid", 3, 1, lat, lng, 1)

    @Test
    fun `sorts by longitude when the group spans more east-west than north-south`() {
        val west = area("west", 43.00, -89.60)
        val mid = area("mid", 43.01, -89.50)
        val east = area("east", 43.00, -89.40)
        val sorted = sortByDominantAxis(listOf(east, west, mid))
        assertEquals(listOf("west", "mid", "east"), sorted.map { it.uuid })
    }

    @Test
    fun `sorts by latitude when the group spans more north-south than east-west`() {
        val south = area("south", 43.40, -89.00)
        val mid = area("mid", 43.50, -89.00)
        val north = area("north", 43.60, -89.01)
        val sorted = sortByDominantAxis(listOf(north, south, mid))
        assertEquals(listOf("south", "mid", "north"), sorted.map { it.uuid })
    }

    @Test
    fun `fewer than two coordinated areas are returned unchanged`() {
        val onlyOne = listOf(area("solo", 43.0, -89.0))
        assertEquals(onlyOne, sortByDominantAxis(onlyOne))
    }
}
