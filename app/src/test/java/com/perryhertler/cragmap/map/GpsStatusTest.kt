package com.perryhertler.cragmap.map

import org.junit.Assert.assertEquals
import org.junit.Test

class GpsStatusTest {
    @Test
    fun `unknown fix shows ellipsis`() {
        assertEquals("Offline · GPS …", gpsStatusLabel(null, null))
    }

    @Test
    fun `fresh accurate fix is ok`() {
        assertEquals("Offline · GPS ok", gpsStatusLabel(8f, 2_000L))
    }

    @Test
    fun `poor accuracy is weak`() {
        assertEquals("Offline · GPS weak", gpsStatusLabel(45f, 1_000L))
    }

    @Test
    fun `stale fix is weak`() {
        assertEquals("Offline · GPS weak", gpsStatusLabel(8f, 60_000L))
    }
}
