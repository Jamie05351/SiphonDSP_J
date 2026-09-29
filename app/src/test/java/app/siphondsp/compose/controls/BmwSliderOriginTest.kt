package app.siphondsp.compose.controls

import org.junit.Assert.assertEquals
import org.junit.Test

class BmwSliderOriginTest {
    @Test
    fun rangeCrossingZeroFillsFromZero() {
        assertEquals(0f, defaultSliderOrigin(-12f..12f), 0f)
        assertEquals(0f, defaultSliderOrigin(-6f..6f), 0f)
    }

    @Test
    fun rangeNotCrossingZeroFillsFromItsStart() {
        assertEquals(0f, defaultSliderOrigin(0f..100f), 0f)
        assertEquals(3f, defaultSliderOrigin(3f..9f), 0f)
        assertEquals(-24f, defaultSliderOrigin(-24f..0f), 0f)
    }
}
