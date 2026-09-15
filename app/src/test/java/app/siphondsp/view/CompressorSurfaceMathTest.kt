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
        assertEquals(80.0, splits[0], 0.0)
        assertEquals(500.0, splits[1], 0.0)
        assertEquals(4000.0, splits[2], 0.0)
    }

    @Test
    fun splitFrequenciesSortBeforeClampingLikeNative() {
        // NativeBmwDspProcessor::configure sorts the raw magnitudes before clamping, so a stored
        // triple that is out of slot order still normalizes by magnitude, not by stored position.
        val values = defaults().also {
            it[NativeBmwDspValues.INDEX_MBC_XO_0] = 900f
            it[NativeBmwDspValues.INDEX_MBC_XO_1] = 300f
            it[NativeBmwDspValues.INDEX_MBC_XO_2] = 100f
        }
        val splits = CompressorSurfaceMath.splitFrequencies(values)
        assertEquals(100.0, splits[0], 0.0)
        assertEquals(300.0, splits[1], 0.0)
        assertEquals(900.0, splits[2], 0.0)
        assertTrue(splits[1] >= splits[0] * 1.05)
        assertTrue(splits[2] >= splits[1] * 1.05)
    }

    @Test
    fun splitFrequenciesApplyNativePerSlotCapsAfterSorting() {
        // NativeBmwDspProcessor::configure clamps each sorted slot to [20,2000]/[40,8000]/
        // [80,20000]. A raw f1 magnitude above the slot-1 cap must display at the cap.
        val values = defaults().also {
            it[NativeBmwDspValues.INDEX_MBC_XO_0] = 500f
            it[NativeBmwDspValues.INDEX_MBC_XO_1] = 9000f
            it[NativeBmwDspValues.INDEX_MBC_XO_2] = 15000f
        }
        val splits = CompressorSurfaceMath.splitFrequencies(values, sampleRateHz = 48_000.0)
        assertEquals(500.0, splits[0], 0.0)
        assertEquals(8000.0, splits[1], 0.0)
        assertEquals(15000.0, splits[2], 0.0)
    }

    @Test
    fun splitFrequenciesRespectTheSampleRateCeiling() {
        // rebuildMbc() caps at 0.45 * sampleRate; at 44.1 kHz that's 19,845 Hz, below the
        // otherwise-legal 20,000 Hz slot-2 cap.
        val values = defaults().also {
            it[NativeBmwDspValues.INDEX_MBC_XO_0] = 500f
            it[NativeBmwDspValues.INDEX_MBC_XO_1] = 4000f
            it[NativeBmwDspValues.INDEX_MBC_XO_2] = 20000f
        }
        val splits = CompressorSurfaceMath.splitFrequencies(values, sampleRateHz = 44_100.0)
        assertEquals(19_845.0, splits[2], 1e-9)
    }

    @Test
    fun splitFrequenciesReserveSpacingBelowTheCeilingLikeNative() {
        // At a low sample rate, capping the lower splits' own upper bound at the ceiling itself
        // (rather than pulling it in by the 5% owed to the split(s) above) would collapse two
        // adjacent splits onto the same value -- see rebuildMbc's f0Ceiling/f1Ceiling headroom.
        val values = defaults().also {
            it[NativeBmwDspValues.INDEX_MBC_XO_0] = 2000f
            it[NativeBmwDspValues.INDEX_MBC_XO_1] = 8000f
            it[NativeBmwDspValues.INDEX_MBC_XO_2] = 20000f
        }
        val splits = CompressorSurfaceMath.splitFrequencies(values, sampleRateHz = 8_000.0)
        assertTrue(splits[1] < splits[2])
        assertTrue(splits[1] >= splits[0] * 1.05 - 1e-6)
        assertTrue(splits[2] >= splits[1] * 1.05 - 1e-6)
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
