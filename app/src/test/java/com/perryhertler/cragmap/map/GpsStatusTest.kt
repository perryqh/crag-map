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
    fun `poor accuracy is weak with meters and wait hint`() {
        assertEquals(
            "Offline · GPS weak (±45m) — wait for a better fix",
            gpsStatusLabel(45f, 1_000L),
        )
    }

    @Test
    fun `stale fix names age and asks to wait`() {
        assertEquals(
            "Offline · GPS stale (60s old) — wait for a fresher fix",
            gpsStatusLabel(8f, 60_000L),
        )
    }

    @Test
    fun `weak and stale combine both reasons`() {
        assertEquals(
            "Offline · GPS weak (±50m, 45s old) — wait for a better fix",
            gpsStatusLabel(50f, 45_000L),
        )
    }
}
