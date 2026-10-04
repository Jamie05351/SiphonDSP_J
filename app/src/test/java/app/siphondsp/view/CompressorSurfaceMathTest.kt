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

    private fun withSplits(a: Float, b: Float, c: Float) = defaults().also {
        it[NativeBmwDspValues.INDEX_MBC_XO_0] = a
        it[NativeBmwDspValues.INDEX_MBC_XO_1] = b
        it[NativeBmwDspValues.INDEX_MBC_XO_2] = c
    }

    @Test
    fun normalizedSplitSlotsSortAnOutOfOrderTripleLikeNative() {
        // The engine processes [4000, 500, 80] as [80, 500, 4000]; the stored slots normalize to it.
        val slots = CompressorSurfaceMath.normalizedSplitSlots(withSplits(4000f, 500f, 80f))
        assertEquals(listOf(80f, 500f, 4000f), slots.toList())
    }

    @Test
    fun normalizedSplitSlotsClampEachSlotAfterSorting() {
        val slots = CompressorSurfaceMath.normalizedSplitSlots(withSplits(30000f, 1f, 9000f))
        assertEquals(listOf(20f, 8000f, 20000f), slots.toList())
    }

    @Test
    fun splitStepRangeComesFromTheNormalizedNeighbours() {
        // The out-of-order example: the middle split's range is a tone clear of 80 and 4000, not
        // the collapsed 4480..4480 that the raw slots would give.
        val slots = CompressorSurfaceMath.normalizedSplitSlots(withSplits(4000f, 500f, 80f))
        val middle = CompressorSurfaceMath.splitStepRange(slots, 1)
        assertEquals(80f * 1.12f, middle.start, 1e-3f)
        assertEquals(4000f / 1.12f, middle.endInclusive, 1e-2f)
        assertTrue(500f in middle)
        assertEquals(20f, CompressorSurfaceMath.splitStepRange(slots, 0).start, 0f)
        assertEquals(20000f, CompressorSurfaceMath.splitStepRange(slots, 2).endInclusive, 0f)
    }

    @Test
    fun splitStepRangeAlwaysContainsTheCurrentValue() {
        // Neighbours closer than a tone: the range still holds the stored value and can move.
        val slots = CompressorSurfaceMath.normalizedSplitSlots(withSplits(100f, 105f, 4000f))
        val middle = CompressorSurfaceMath.splitStepRange(slots, 1)
        assertTrue(105f in middle)
        assertTrue(middle.endInclusive > 105f)
    }
}
