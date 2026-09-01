package app.siphondsp.view

import app.siphondsp.model.NativeBmwDspValues
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CompressorSurfaceMathTest {

    private fun defaults() = NativeBmwDspValues.DEFAULTS.copyOf()

    @Test
    fun splitFrequenciesReadTheShippedDefaults() {
        val splits = CompressorSurfaceMath.splitFrequencies(defaults())
        assertEquals(120.0, splits[0], 0.0)
        assertEquals(500.0, splits[1], 0.0)
        assertEquals(4000.0, splits[2], 0.0)
    }

    @Test
    fun splitFrequenciesAreClampedMonotonicLikeNative() {
        val values = defaults().also {
            it[NativeBmwDspValues.INDEX_MBC_XO_0] = 900f
            it[NativeBmwDspValues.INDEX_MBC_XO_1] = 300f
            it[NativeBmwDspValues.INDEX_MBC_XO_2] = 100f
        }
        val splits = CompressorSurfaceMath.splitFrequencies(values)
        assertEquals(900.0, splits[0], 0.0)
        assertTrue(splits[1] >= splits[0] * 1.05)
        assertTrue(splits[2] >= splits[1] * 1.05)
    }

    @Test
    fun bandForFrequencyBucketsAroundTheSplits() {
        val splits = doubleArrayOf(120.0, 500.0, 4000.0)
        assertEquals(0, CompressorSurfaceMath.bandForFrequency(20.0, splits))
        assertEquals(0, CompressorSurfaceMath.bandForFrequency(119.9, splits))
        assertEquals(1, CompressorSurfaceMath.bandForFrequency(120.0, splits))
        assertEquals(1, CompressorSurfaceMath.bandForFrequency(499.0, splits))
        assertEquals(2, CompressorSurfaceMath.bandForFrequency(500.0, splits))
        assertEquals(2, CompressorSurfaceMath.bandForFrequency(3999.0, splits))
        assertEquals(3, CompressorSurfaceMath.bandForFrequency(4000.0, splits))
        assertEquals(3, CompressorSurfaceMath.bandForFrequency(20_000.0, splits))
    }

    @Test
    fun bandRangeSpansTheWholeAxisContiguously() {
        val splits = doubleArrayOf(120.0, 500.0, 4000.0)
        assertEquals(CompressorSurfaceMath.MIN_FREQUENCY to 120.0, CompressorSurfaceMath.bandRange(0, splits))
        assertEquals(120.0 to 500.0, CompressorSurfaceMath.bandRange(1, splits))
        assertEquals(500.0 to 4000.0, CompressorSurfaceMath.bandRange(2, splits))
        assertEquals(4000.0 to CompressorSurfaceMath.MAX_FREQUENCY, CompressorSurfaceMath.bandRange(3, splits))
    }

    @Test
    fun dbToFractionMapsAxisEndsToZeroAndOne() {
        assertEquals(0f, CompressorSurfaceMath.dbToFraction(CompressorSurfaceMath.MAX_DB), 1e-6f)
        assertEquals(1f, CompressorSurfaceMath.dbToFraction(CompressorSurfaceMath.MIN_DB), 1e-6f)
        // 0 dBFS reference sits near the top.
        assertTrue(CompressorSurfaceMath.dbToFraction(0.0) < 0.15f)
        // Out-of-range clamps rather than extrapolates.
        assertEquals(1f, CompressorSurfaceMath.dbToFraction(-200.0), 1e-6f)
        assertEquals(0f, CompressorSurfaceMath.dbToFraction(50.0), 1e-6f)
    }

    @Test
    fun frequencyToFractionIsLogAndSpansTheAxis() {
        assertEquals(0f, CompressorSurfaceMath.frequencyToFraction(CompressorSurfaceMath.MIN_FREQUENCY), 1e-6f)
        assertEquals(1f, CompressorSurfaceMath.frequencyToFraction(CompressorSurfaceMath.MAX_FREQUENCY), 1e-6f)
        // ~632 Hz is the geometric midpoint of 20..20k.
        assertEquals(0.5f, CompressorSurfaceMath.frequencyToFraction(632.46), 1e-3f)
    }

    @Test
    fun gainCurveDbForReductionIsNegativeAndClamped() {
        assertEquals(0.0, CompressorSurfaceMath.gainCurveDbForReduction(0f), 0.0)
        assertEquals(-6.0, CompressorSurfaceMath.gainCurveDbForReduction(6f), 0.0)
        assertEquals(CompressorSurfaceMath.MIN_DB, CompressorSurfaceMath.gainCurveDbForReduction(999f), 0.0)
    }
}
