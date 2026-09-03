package app.siphondsp.view

import org.junit.Assert.assertEquals
import org.junit.Test

class PeqGraphMathTest {
    @Test
    fun logarithmicFrequencyMappingRoundTrips() {
        listOf(20.0, 63.0, 1000.0, 6300.0, 20000.0).forEach { frequency ->
            val fraction = PeqGraphMath.frequencyToFraction(frequency)
            assertEquals(frequency, PeqGraphMath.fractionToFrequency(fraction), frequency * 0.00001)
        }
    }

    @Test
    fun gainAxisIsAsymmetricMinus18ToPlus12() {
        assertEquals(-18.0, PeqGraphMath.MIN_GAIN, 0.0)
        assertEquals(12.0, PeqGraphMath.MAX_GAIN, 0.0)
        // Fraction 0 = top of plot = MAX_GAIN, fraction 1 = bottom = MIN_GAIN.
        assertEquals(12.0, PeqGraphMath.fractionToGain(0f), 1e-9)
        assertEquals(-18.0, PeqGraphMath.fractionToGain(1f), 1e-9)
        assertEquals(0f, PeqGraphMath.gainToFraction(12.0), 1e-6f)
        assertEquals(1f, PeqGraphMath.gainToFraction(-18.0), 1e-6f)
    }

    @Test
    fun spectrumDbToGraphGainMapsFloorAndCeilingToAxisEnds() {
        val floorGain = PeqGraphMath.spectrumDbToGraphGain(-80f, floorDb = -80f, ceilingDb = 0f)
        val ceilingGain = PeqGraphMath.spectrumDbToGraphGain(0f, floorDb = -80f, ceilingDb = 0f)

        assertEquals(PeqGraphMath.MIN_GAIN, floorGain, 1e-6)
        assertEquals(PeqGraphMath.MAX_GAIN, ceilingGain, 1e-6)
    }

    @Test
    fun spectrumDbToGraphGainClampsOutOfRangeInput() {
        val belowFloor = PeqGraphMath.spectrumDbToGraphGain(-120f, floorDb = -80f, ceilingDb = 0f)
        val aboveCeiling = PeqGraphMath.spectrumDbToGraphGain(10f, floorDb = -80f, ceilingDb = 0f)

        assertEquals(PeqGraphMath.MIN_GAIN, belowFloor, 1e-6)
        assertEquals(PeqGraphMath.MAX_GAIN, aboveCeiling, 1e-6)
    }
}
