package app.siphondsp.view

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PeqPlotGeometryTest {

    private val geo = PeqPlotGeometry(
        left = 34f, right = 356f, top = 16f, bottom = 278f,
        maximumFrequency = PeqGraphMath.MAX_FREQUENCY,
    )

    @Test
    fun xMapsMinFrequencyToLeftAndMaxToRight() {
        assertEquals(34f, geo.xForFrequency(PeqGraphMath.MIN_FREQUENCY), 1e-3f)
        assertEquals(356f, geo.xForFrequency(PeqGraphMath.MAX_FREQUENCY), 1e-3f)
    }

    @Test
    fun xIsMonotonicIncreasingInFrequency() {
        var prev = geo.xForFrequency(PeqGraphMath.MIN_FREQUENCY)
        for (f in listOf(50.0, 200.0, 1_000.0, 5_000.0, 15_000.0)) {
            val x = geo.xForFrequency(f)
            assertTrue("x should increase with frequency", x > prev)
            prev = x
        }
    }

    @Test
    fun xClampsFrequenciesOutsideTheAxis() {
        assertEquals(34f, geo.xForFrequency(1.0), 1e-3f)
        assertEquals(356f, geo.xForFrequency(96_000.0), 1e-3f)
    }

    @Test
    fun yForGainPutsMaxGainAtTheTopAndMinGainAtTheBottom() {
        assertEquals(16f, geo.yForGain(PeqGraphMath.MAX_GAIN), 1e-3f)
        assertEquals(278f, geo.yForGain(PeqGraphMath.MIN_GAIN), 1e-3f)
        // 0 dB sits proportionally between the rails.
        val zero = geo.yForGain(0.0)
        assertTrue(zero in 16f..278f)
    }

    @Test
    fun yForRangeClampsAndMapsEndsAndMidpoint() {
        assertEquals(16f, geo.yForRange(10.0, min = -2.0, max = 10.0), 1e-3f)   // max -> top
        assertEquals(278f, geo.yForRange(-2.0, min = -2.0, max = 10.0), 1e-3f)  // min -> bottom
        assertEquals(147f, geo.yForRange(4.0, min = -2.0, max = 10.0), 1e-3f)   // midpoint -> centre
        // out of range clamps to the rails
        assertEquals(16f, geo.yForRange(999.0, min = -2.0, max = 10.0), 1e-3f)
        assertEquals(278f, geo.yForRange(-999.0, min = -2.0, max = 10.0), 1e-3f)
    }

    @Test
    fun phaseAndGroupDelayHelpersUseTheirFixedRanges() {
        assertEquals(16f, geo.yForPhaseDeg(180.0), 1e-3f)
        assertEquals(278f, geo.yForPhaseDeg(-180.0), 1e-3f)
        assertEquals(147f, geo.yForPhaseDeg(0.0), 1e-3f)

        assertEquals(16f, geo.yForGroupDelayMs(10.0), 1e-3f)
        assertEquals(278f, geo.yForGroupDelayMs(-2.0), 1e-3f)
    }
}
