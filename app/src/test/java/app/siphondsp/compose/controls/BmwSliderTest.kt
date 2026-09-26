package app.siphondsp.compose.controls

import org.junit.Assert.assertEquals
import org.junit.Test

class BmwSliderTest {
    @Test
    fun accessibilityProgressClampsContinuousValuesToRange() {
        assertEquals(-12f, sliderValueForProgress(-20f, -12f..12f, steps = 0), 0.001f)
        assertEquals(3.25f, sliderValueForProgress(3.25f, -12f..12f, steps = 0), 0.001f)
        assertEquals(12f, sliderValueForProgress(20f, -12f..12f, steps = 0), 0.001f)
    }

    @Test
    fun accessibilityProgressSnapsDiscreteValuesToSliderIntervals() {
        assertEquals(25f, sliderValueForProgress(24f, 0f..100f, steps = 3), 0.001f)
        assertEquals(75f, sliderValueForProgress(63f, 0f..100f, steps = 3), 0.001f)
    }
}
