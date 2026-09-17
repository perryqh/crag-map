package com.perryhertler.cragmap.map

import org.junit.Assert.assertEquals
import org.junit.Test

class CameraZoomTest {

    @Test
    fun `leaf areas use leaf zoom band plus half stop`() {
        assertEquals(17.5, zoomForSelectedArea(isLeaf = 1), 0.0)
    }

    @Test
    fun `non-leaf areas use intermediate zoom band plus half stop`() {
        assertEquals(15.5, zoomForSelectedArea(isLeaf = 0), 0.0)
    }
}
