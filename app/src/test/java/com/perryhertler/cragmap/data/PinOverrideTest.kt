package com.perryhertler.cragmap.data

import org.junit.Assert.assertEquals
import org.junit.Test

class PinOverrideTest {

    @Test
    fun `empty list serializes to empty array`() {
        assertEquals("[]", overridesToJson(emptyList()))
    }

    @Test
    fun `single override serializes all fields`() {
        val json = overridesToJson(
            listOf(
                PinOverrideEntity(
                    targetUuid = "abc-123",
                    targetType = "area",
                    targetName = "17: Hawk's Nest",
                    lat = 43.41353,
                    lng = -89.7158,
                    capturedAtMillis = 1700000000000,
                    fixAgeMillis = 500
                )
            )
        )
        assertEquals(
            """[{"targetUuid":"abc-123","targetType":"area","targetName":"17: Hawk's Nest",""" +
                """"lat":43.41353,"lng":-89.7158,"capturedAtMillis":1700000000000,"fixAgeMillis":500}]""",
            json
        )
    }

    @Test
    fun `multiple overrides are comma separated`() {
        val overrides = listOf(
            PinOverrideEntity("a1", "area", "Formation A", 43.0, -89.0, 1, 100),
            PinOverrideEntity("c1", "climb", "Climb C", 43.1, -89.1, 2, 200)
        )
        val json = overridesToJson(overrides)
        assertEquals(
            """[{"targetUuid":"a1","targetType":"area","targetName":"Formation A","lat":43.0,"lng":-89.0,"capturedAtMillis":1,"fixAgeMillis":100},""" +
                """{"targetUuid":"c1","targetType":"climb","targetName":"Climb C","lat":43.1,"lng":-89.1,"capturedAtMillis":2,"fixAgeMillis":200}]""",
            json
        )
    }

    @Test
    fun `quotes and backslashes in target name are escaped`() {
        val json = overridesToJson(
            listOf(PinOverrideEntity("u1", "climb", """The "Crux" \ Wall""", 1.0, 2.0, 3, 0))
        )
        assertEquals(
            """[{"targetUuid":"u1","targetType":"climb","targetName":"The \"Crux\" \\ Wall","lat":1.0,"lng":2.0,"capturedAtMillis":3,"fixAgeMillis":0}]""",
            json
        )
    }

    @Test
    fun `fixAgeMillis defaults to zero when omitted`() {
        val json = overridesToJson(listOf(PinOverrideEntity("u1", "area", "X", 1.0, 2.0, 3)))
        assertEquals(
            """[{"targetUuid":"u1","targetType":"area","targetName":"X","lat":1.0,"lng":2.0,"capturedAtMillis":3,"fixAgeMillis":0}]""",
            json
        )
    }
}
