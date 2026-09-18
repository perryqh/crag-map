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
                    fixAgeMillis = 500,
                    headingDegrees = 182.5f
                )
            )
        )
        assertEquals(
            """[{"targetUuid":"abc-123","targetType":"area","targetName":"17: Hawk's Nest",""" +
                """"lat":43.41353,"lng":-89.7158,"capturedAtMillis":1700000000000,"fixAgeMillis":500,"headingDegrees":182.5,"stance":"base"}]""",
            json
        )
    }

    @Test
    fun `multiple overrides are comma separated`() {
        val overrides = listOf(
            PinOverrideEntity("a1", "area", "Formation A", 43.0, -89.0, 1, 100, 90f),
            PinOverrideEntity("c1", "climb", "Climb C", 43.1, -89.1, 2, 200, 270f)
        )
        val json = overridesToJson(overrides)
        assertEquals(
            """[{"targetUuid":"a1","targetType":"area","targetName":"Formation A","lat":43.0,"lng":-89.0,"capturedAtMillis":1,"fixAgeMillis":100,"headingDegrees":90.0,"stance":"base"},""" +
                """{"targetUuid":"c1","targetType":"climb","targetName":"Climb C","lat":43.1,"lng":-89.1,"capturedAtMillis":2,"fixAgeMillis":200,"headingDegrees":270.0,"stance":"base"}]""",
            json
        )
    }

    @Test
    fun `quotes and backslashes in target name are escaped`() {
        val json = overridesToJson(
            listOf(PinOverrideEntity("u1", "climb", """The "Crux" \ Wall""", 1.0, 2.0, 3, 0, null))
        )
        assertEquals(
            """[{"targetUuid":"u1","targetType":"climb","targetName":"The \"Crux\" \\ Wall","lat":1.0,"lng":2.0,"capturedAtMillis":3,"fixAgeMillis":0,"headingDegrees":null,"stance":"base"}]""",
            json
        )
    }

    @Test
    fun `fixAgeMillis and headingDegrees default when omitted`() {
        val json = overridesToJson(listOf(PinOverrideEntity("u1", "area", "X", 1.0, 2.0, 3)))
        assertEquals(
            """[{"targetUuid":"u1","targetType":"area","targetName":"X","lat":1.0,"lng":2.0,"capturedAtMillis":3,"fixAgeMillis":0,"headingDegrees":null,"stance":"base"}]""",
            json
        )
    }

    @Test
    fun `null heading serializes as a JSON null literal, not a quoted string`() {
        val json = overridesToJson(listOf(PinOverrideEntity("u1", "area", "X", 1.0, 2.0, 3, 0, null)))
        assertEquals(true, json.contains(""""headingDegrees":null,"stance":"base"}"""))
    }

    @Test
    fun `top stance serializes`() {
        val json = overridesToJson(
            listOf(
                PinOverrideEntity(
                    targetUuid = "t1",
                    targetType = "climb",
                    targetName = "Top Rope",
                    lat = 43.0,
                    lng = -89.0,
                    capturedAtMillis = 9,
                    fixAgeMillis = 0,
                    headingDegrees = null,
                    stance = "top",
                )
            )
        )
        assertEquals(
            """[{"targetUuid":"t1","targetType":"climb","targetName":"Top Rope",""" +
                """"lat":43.0,"lng":-89.0,"capturedAtMillis":9,"fixAgeMillis":0,""" +
                """"headingDegrees":null,"stance":"top"}]""",
            json,
        )
    }



}

class PhotoOverrideTest {

    @Test
    fun `empty list serializes to empty array`() {
        assertEquals("[]", photoOverridesToJson(emptyList()))
    }

    @Test
    fun `single photo with one target serializes with fileName derived from filePath, not the full path`() {
        val photo = PhotoOverrideEntity(
            id = 1,
            filePath = "/data/user/0/com.perryhertler.cragmap/files/photos/abc-123_1700000000000.jpg",
            capturedAtMillis = 1700000000000
        )
        val json = photoOverridesToJson(
            listOf(PhotoWithTargets(photo, listOf(PhotoTargetEntity(1, "abc-123", "climb", "The Beast"))))
        )
        assertEquals(
            """[{"fileName":"abc-123_1700000000000.jpg","capturedAtMillis":1700000000000,""" +
                """"targets":[{"targetUuid":"abc-123","targetType":"climb","targetName":"The Beast"}]}]""",
            json
        )
    }

    @Test
    fun `a photo covering multiple climbs lists every target`() {
        val photo = PhotoOverrideEntity(id = 1, filePath = "/x/wall_1.jpg", capturedAtMillis = 1)
        val json = photoOverridesToJson(
            listOf(
                PhotoWithTargets(
                    photo,
                    listOf(
                        PhotoTargetEntity(1, "c1", "climb", "Climb One"),
                        PhotoTargetEntity(1, "c2", "climb", "Climb Two")
                    )
                )
            )
        )
        assertEquals(
            """[{"fileName":"wall_1.jpg","capturedAtMillis":1,""" +
                """"targets":[{"targetUuid":"c1","targetType":"climb","targetName":"Climb One"},""" +
                """{"targetUuid":"c2","targetType":"climb","targetName":"Climb Two"}]}]""",
            json
        )
    }

    @Test
    fun `multiple photos are comma separated`() {
        val photos = listOf(
            PhotoWithTargets(
                PhotoOverrideEntity(1, "/x/a1_1.jpg", 1),
                listOf(PhotoTargetEntity(1, "a1", "area", "Formation A"))
            ),
            PhotoWithTargets(
                PhotoOverrideEntity(2, "/x/a1_2.jpg", 2),
                listOf(PhotoTargetEntity(2, "a1", "area", "Formation A"))
            )
        )
        val json = photoOverridesToJson(photos)
        assertEquals(
            """[{"fileName":"a1_1.jpg","capturedAtMillis":1,""" +
                """"targets":[{"targetUuid":"a1","targetType":"area","targetName":"Formation A"}]},""" +
                """{"fileName":"a1_2.jpg","capturedAtMillis":2,""" +
                """"targets":[{"targetUuid":"a1","targetType":"area","targetName":"Formation A"}]}]""",
            json
        )
    }

    @Test
    fun `quotes and backslashes in target name are escaped`() {
        val photo = PhotoOverrideEntity(1, "/x/u1_1.jpg", 3)
        val json = photoOverridesToJson(
            listOf(PhotoWithTargets(photo, listOf(PhotoTargetEntity(1, "u1", "climb", """The "Crux" \ Wall"""))))
        )
        assertEquals(
            """[{"fileName":"u1_1.jpg","capturedAtMillis":3,""" +
                """"targets":[{"targetUuid":"u1","targetType":"climb","targetName":"The \"Crux\" \\ Wall"}]}]""",
            json
        )
    }

    @Test
    fun `a photo with no targets yet serializes an empty targets array`() {
        val photo = PhotoOverrideEntity(1, "/x/untagged.jpg", 3)
        val json = photoOverridesToJson(listOf(PhotoWithTargets(photo, emptyList())))
        assertEquals("""[{"fileName":"untagged.jpg","capturedAtMillis":3,"targets":[]}]""", json)
    }
}
