package app.siphondsp.dsp

import org.junit.Assert.assertEquals
import org.junit.Test

/** The Bands page curve matches the engine's soft-knee gain computer (NativeBmwDspMath.h). */
class CompressorCurveTest {

    @Test
    fun belowTheKneeNothingChanges() {
        assertEquals(0.0, CompressorCurve.gainReductionDb(-10.0, 3.0, 6.0), 0.0)
        assertEquals(-30.0, CompressorCurve.outputDb(-30.0, -18.0, 3.0, 6.0, 0.0), 1e-9)
    }

    @Test
    fun aboveTheKneeTheRatioApplies() {
        // 12 dB over at 3:1 comes out 4 dB over: an 8 dB reduction. In at -6: -6 - 8 + 2 makeup = -12.
        assertEquals(-8.0, CompressorCurve.gainReductionDb(12.0, 3.0, 6.0), 1e-9)
        assertEquals(-12.0, CompressorCurve.outputDb(-6.0, -18.0, 3.0, 6.0, 2.0), 1e-9)
    }

    @Test
    fun theKneeIsTheEnginesQuadratic() {
        // At the threshold, half way into a 6 dB knee at 3:1: -(2/3) * 3^2 / 12 = -0.5 dB.
        assertEquals(-0.5, CompressorCurve.gainReductionDb(0.0, 3.0, 6.0), 1e-9)
    }

    @Test
    fun aHardKneeAndATinyRatioMatchTheNativeGuards() {
        assertEquals(-5.0 * (1 - 1 / 2.0), CompressorCurve.gainReductionDb(5.0, 2.0, 0.0), 1e-9)
        assertEquals(-10.0 * (1 - 1 / 1.001), CompressorCurve.gainReductionDb(10.0, 1.0, 0.0), 1e-9)
    }
}
