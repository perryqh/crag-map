package com.perryhertler.cragmap.map

import com.perryhertler.cragmap.data.PinOverrideEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class CapturedCoordsTest {

    @Test
    fun `formats lat lng to six decimal places`() {
        assertEquals(
            "43.058493, -88.157804",
            formatCapturedCoordinates(43.0584931, -88.1578044),
        )
    }

    @Test
    fun `pads shorter fractions to six decimals`() {
        assertEquals("43.400000, -89.700000", formatCapturedCoordinates(43.4, -89.7))
    }

    @Test
    fun `captured pin line includes heading when present`() {
        val o = PinOverrideEntity(
            targetUuid = "u1",
            targetType = "area",
            targetName = "Hawk's Nest",
            lat = 43.058493,
            lng = -88.157804,
            capturedAtMillis = 1,
            headingDegrees = 182.4f,
        )
        assertEquals("Captured (Base): 43.058493, -88.157804 · 182°", formatCapturedPinLine(o))
    }

    @Test
    fun `captured pin line omits heading when null`() {
        val o = PinOverrideEntity(
            targetUuid = "u1",
            targetType = "climb",
            targetName = "Route",
            lat = 43.0,
            lng = -89.0,
            capturedAtMillis = 1,
            headingDegrees = null,
        )
        assertEquals("Captured (Base): 43.000000, -89.000000", formatCapturedPinLine(o))
    }

    @Test
    fun `capture feedback line includes accuracy and age`() {
        val f = CaptureFeedback(
            targetName = "Hawk's Nest",
            lat = 43.058493,
            lng = -88.157804,
            accuracyMeters = 8.2f,
            fixAgeMillis = 12_400L,
        )
        assertEquals(
            "Captured Hawk's Nest (Base) · 43.058493, -88.157804 · ±8 m · 12s old",
            formatCaptureFeedbackLine(f),
        )
    }

    @Test
    fun `capture feedback line omits accuracy when null`() {
        val f = CaptureFeedback(
            targetName = "Route",
            lat = 43.0,
            lng = -89.0,
            accuracyMeters = null,
            fixAgeMillis = 500L,
        )
        assertEquals(
            "Captured Route (Base) · 43.000000, -89.000000 · 0s old",
            formatCaptureFeedbackLine(f),
        )
    }

    @Test
    fun `captured pin line shows Top and hides heading`() {
        val o = PinOverrideEntity(
            targetUuid = "u1",
            targetType = "climb",
            targetName = "Route",
            lat = 43.0,
            lng = -89.0,
            capturedAtMillis = 1,
            headingDegrees = 90f,
            stance = "top",
        )
        assertEquals("Captured (Top): 43.000000, -89.000000", formatCapturedPinLine(o))
    }
}
