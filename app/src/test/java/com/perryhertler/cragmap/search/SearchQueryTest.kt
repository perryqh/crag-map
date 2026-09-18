package com.perryhertler.cragmap.search

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchQueryTest {
    @Test
    fun `yds and v-scale look like grades`() {
        assertTrue(looksLikeGradeQuery("5.8"))
        assertTrue(looksLikeGradeQuery("5.10a"))
        assertTrue(looksLikeGradeQuery("5.11D"))
        assertTrue(looksLikeGradeQuery("V3"))
        assertTrue(looksLikeGradeQuery("V10+"))
    }

    @Test
    fun `names are not grades`() {
        assertFalse(looksLikeGradeQuery("East Rampart"))
        assertFalse(looksLikeGradeQuery("5."))
        assertFalse(looksLikeGradeQuery(""))
    }
}
