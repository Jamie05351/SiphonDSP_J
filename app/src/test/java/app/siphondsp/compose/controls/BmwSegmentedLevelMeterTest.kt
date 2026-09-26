package app.siphondsp.compose.controls

import org.junit.Assert.assertEquals
import org.junit.Test

class BmwSegmentedLevelMeterTest {
    @Test
    fun dbValuesMapAcrossTheConfiguredRange() {
        assertEquals(0f, meterFractionFor(-80f, -60f..0f), 0.001f)
        assertEquals(0.5f, meterFractionFor(-30f, -60f..0f), 0.001f)
        assertEquals(1f, meterFractionFor(3f, -60f..0f), 0.001f)
    }

    @Test
    fun anyVisibleLevelLightsAWholeSegment() {
        assertEquals(0, activeMeterSegmentCount(0f, 28))
        assertEquals(1, activeMeterSegmentCount(0.001f, 28))
        assertEquals(14, activeMeterSegmentCount(0.5f, 28))
        assertEquals(28, activeMeterSegmentCount(2f, 28))
    }
}
