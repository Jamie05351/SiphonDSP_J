package app.siphondsp.view

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class GraphDbRangeTest {

    @Test
    fun defaultIsPlus12Minus24() {
        assertEquals(12.0, GraphDbRange.DEFAULT.topDb, 0.0)
        assertEquals(-24.0, GraphDbRange.DEFAULT.bottomDb, 0.0)
    }

    @Test
    fun gridlinesEverySixDbIncludingBothEnds() {
        assertArrayEquals(doubleArrayOf(12.0, 6.0, 0.0, -6.0, -12.0, -18.0, -24.0), GraphDbRange.PLUS_12_MINUS_24.gridLines, 0.0)
        assertArrayEquals(doubleArrayOf(18.0, 12.0, 6.0, 0.0, -6.0, -12.0, -18.0), GraphDbRange.PLUS_18_MINUS_18.gridLines, 0.0)
        assertEquals(9, GraphDbRange.PLUS_24_MINUS_24.gridLines.size)
        for (range in GraphDbRange.entries) {
            assertEquals(range.topDb, range.gridLines.first(), 0.0)
            assertEquals(range.bottomDb, range.gridLines.last(), 0.0)
        }
    }

    @Test
    fun storedNameRoundTripsAndAnythingElseFallsBackToTheDefault() {
        for (range in GraphDbRange.entries) assertEquals(range, GraphDbRange.fromName(range.name))
        assertEquals(GraphDbRange.DEFAULT, GraphDbRange.fromName(null))
        assertEquals(GraphDbRange.DEFAULT, GraphDbRange.fromName("PLUS_6_MINUS_30"))
    }

    @Test
    fun labels() {
        assertEquals("+12 / −24 dB", GraphDbRange.PLUS_12_MINUS_24.label)
        assertEquals("+18 / −18 dB", GraphDbRange.PLUS_18_MINUS_18.label)
        assertEquals("+24 / −24 dB", GraphDbRange.PLUS_24_MINUS_24.label)
    }
}
