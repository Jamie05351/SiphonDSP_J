package app.siphondsp.dsp

import app.siphondsp.model.BmwPeqState
import app.siphondsp.model.ParametricEqBand
import app.siphondsp.model.ParametricEqBandList
import app.siphondsp.model.ParametricEqChannel
import app.siphondsp.model.ParametricEqFilterType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Verifies BmwResponseCalculator mirrors NativeBmwDspProcessor::processFrame's stage order,
 * including the bugs the extraction fixed relative to the earlier settings-card-only model
 * (see dsp package KDoc): the +6dB bypass error, subsonic applying outside the lpfPass
 * guard, and the l=oR/r=oL output swap.
 */
class BmwSignalChainModelTest {
    private val calculator = BmwResponseCalculator(pointCount = POINT_COUNT)
    private val curves = BmwResponseCurves(POINT_COUNT)

    /** Independent, immutable snapshot -- [BmwResponseCurves] itself is a reused mutable buffer. */
    private class Snapshot(
        val sumDb: Array<DoubleArray>,
        val lowBranchDb: Array<DoubleArray>,
        val midBranchDb: Array<DoubleArray>,
        val preSplitDb: Array<DoubleArray>,
        val processorEnabled: Boolean,
        val bothCrossoversBypassed: Boolean,
    )

    private fun compute(values: FloatArray, peq: BmwPeqState = BmwPeqState.empty()): Snapshot {
        calculator.invalidateAll()
        calculator.configureAxis(SAMPLE_RATE, 20.0, 20_000.0)
        calculator.compute(values, peq, curves)
        return Snapshot(
            sumDb = Array(2) { curves.sumDb[it].copyOf() },
            lowBranchDb = Array(2) { curves.lowBranchDb[it].copyOf() },
            midBranchDb = Array(2) { curves.midBranchDb[it].copyOf() },
            preSplitDb = Array(2) { curves.preSplitDb[it].copyOf() },
            processorEnabled = curves.processorEnabled,
            bothCrossoversBypassed = curves.bothCrossoversBypassed,
        )
    }

    private fun nearestIndex(frequency: Double): Int =
        curves.frequencies.indices.minByOrNull { abs(curves.frequencies[it] - frequency) }!!

    private fun band(
        frequency: Double,
        gain: Double,
        q: Double = 1.0,
        type: ParametricEqFilterType = ParametricEqFilterType.PEAKING,
        channel: ParametricEqChannel = ParametricEqChannel.LEFT_RIGHT,
    ) = ParametricEqBand(frequency, gain, q, type, channel)

    private fun peqWith(
        full: List<ParametricEqBand> = emptyList(),
        low: List<ParametricEqBand> = emptyList(),
        mid: List<ParametricEqBand> = emptyList(),
        preampDb: Float = 0f,
    ) = BmwPeqState(
        enabled = true,
        preampDb = preampDb,
        fullRangeBands = ParametricEqBandList().apply { addAll(full) },
        lowBandBands = ParametricEqBandList().apply { addAll(low) },
        midBandBands = ParametricEqBandList().apply { addAll(mid) },
    )

    @Test
    fun headroomShiftsEveryStageByExactlyItsOwnDelta() {
        val baseline = compute(baseValues())
        val lowered = compute(baseValues().also { it[5] = it[5] - 4f })

        for (i in baseline.sumDb[0].indices) {
            assertEquals("preSplit at point $i", -4.0, lowered.preSplitDb[0][i] - baseline.preSplitDb[0][i], 1e-6)
            assertEquals("sum at point $i", -4.0, lowered.sumDb[0][i] - baseline.sumDb[0][i], 1e-6)
        }
    }

    @Test
    fun branchGainsAffectOnlyTheirOwnBranch() {
        val baseline = compute(baseValues())
        val lowBoost = compute(baseValues().also { it[6] = it[6] + 3f; it[7] = it[7] + 3f })

        val freqIndex = nearestIndex(50.0) // deep in the low passband (lpf=150Hz)
        val lowDelta = lowBoost.lowBranchDb[0][freqIndex] - baseline.lowBranchDb[0][freqIndex]
        val midDelta = lowBoost.midBranchDb[0][freqIndex] - baseline.midBranchDb[0][freqIndex]

        assertEquals(3.0, lowDelta, 1e-6)
        assertEquals(0.0, midDelta, 1e-6)
    }

    @Test
    fun fullRangePeqAppliesIdenticallyToLowMidAndSumBeforeSplit() {
        val values = baseValues()
        val without = compute(values, peqWith())
        val with = compute(values, peqWith(full = listOf(band(1_000.0, 12.0, q = 2.0))))

        val freqIndex = nearestIndex(1_000.0)
        val lowDelta = with.lowBranchDb[0][freqIndex] - without.lowBranchDb[0][freqIndex]
        val midDelta = with.midBranchDb[0][freqIndex] - without.midBranchDb[0][freqIndex]
        val sumDelta = with.sumDb[0][freqIndex] - without.sumDb[0][freqIndex]

        // A pre-split boost multiplies pre by the same complex factor into both branches, so
        // sum = pre*(lowH+midH) scales by that same factor too -- all three deltas must
        // agree (within FP tolerance), proving the PEQ runs before the split, not per-branch.
        assertTrue("expected a real boost near 1kHz, got $lowDelta", lowDelta > 3.0)
        assertEquals(lowDelta, midDelta, 0.05)
        assertEquals(lowDelta, sumDelta, 0.05)
    }

    @Test
    fun lowBandPeqAffectsOnlyLowBranch() {
        val values = baseValues()
        val without = compute(values, peqWith())
        val with = compute(values, peqWith(low = listOf(band(80.0, 10.0, q = 2.0))))

        val freqIndex = nearestIndex(80.0)
        val lowDelta = with.lowBranchDb[0][freqIndex] - without.lowBranchDb[0][freqIndex]
        val midDelta = with.midBranchDb[0][freqIndex] - without.midBranchDb[0][freqIndex]

        assertTrue("expected a real boost near 80Hz, got $lowDelta", lowDelta > 5.0)
        assertEquals(0.0, midDelta, 1e-6)
    }

    @Test
    fun midBandPeqAffectsOnlyMidBranch() {
        val values = baseValues()
        val without = compute(values, peqWith())
        val with = compute(values, peqWith(mid = listOf(band(2_000.0, 10.0, q = 2.0))))

        val freqIndex = nearestIndex(2_000.0)
        val midDelta = with.midBranchDb[0][freqIndex] - without.midBranchDb[0][freqIndex]
        val lowDelta = with.lowBranchDb[0][freqIndex] - without.lowBranchDb[0][freqIndex]

        assertTrue("expected a real boost near 2kHz, got $midDelta", midDelta > 5.0)
        assertEquals(0.0, lowDelta, 1e-6)
    }

    @Test
    fun bothCrossoversBypassedReturnsPreSplitSignalNotDoubled() {
        // Tilt/post-gain still apply after the bypass shortcut, so disable both here to
        // isolate the bypass behavior itself: sum must equal preSplit exactly, not 2x it.
        val values = baseValues().also { it[1] = 1f; it[2] = 1f; it[25] = 0f }
        val result = compute(values)

        for (channel in 0..1) {
            for (i in result.sumDb[channel].indices) {
                assertEquals("channel=$channel point=$i", result.preSplitDb[channel][i], result.sumDb[channel][i], 1e-6)
            }
        }
        assertTrue(result.bothCrossoversBypassed)
    }

    @Test
    fun lowCrossoverBypassedStillAppliesSubsonicFilter() {
        // subFreq raised to 60Hz (still within its 20-60Hz clamp) so there's real headroom
        // below cutoff within the axis's 20Hz floor.
        val bypassedNoSubsonic = compute(baseValues().also { it[1] = 1f; it[12] = 0f; it[13] = 60f })
        val bypassedWithSubsonic = compute(baseValues().also { it[1] = 1f; it[12] = 1f; it[13] = 60f })

        val freqIndex = nearestIndex(20.0) // the axis floor, well below the 60Hz subsonic cutoff
        val withoutDb = bypassedNoSubsonic.lowBranchDb[0][freqIndex]
        val withDb = bypassedWithSubsonic.lowBranchDb[0][freqIndex]

        // Subsonic must still attenuate deep bass even though the crossover itself is
        // bypassed -- native applies it unconditionally, outside the `!lpfPass` guard.
        assertTrue("expected subsonic attenuation ($withDb) well below unfiltered ($withoutDb)", withDb < withoutDb - 10.0)
    }

    @Test
    fun tiltAppliesAfterSummationNotToIndividualBranches() {
        val values = baseValues()
        val tiltOff = compute(values.copyOf().also { it[25] = 0f })
        val tiltOn = compute(values.copyOf().also { it[25] = 1f; it[26] = 6f; it[27] = 550f })

        val freqIndex = nearestIndex(200.0) // below the tilt pivot, where the low-shelf boost applies
        val lowDelta = tiltOn.lowBranchDb[0][freqIndex] - tiltOff.lowBranchDb[0][freqIndex]
        val midDelta = tiltOn.midBranchDb[0][freqIndex] - tiltOff.midBranchDb[0][freqIndex]
        val sumDelta = tiltOn.sumDb[0][freqIndex] - tiltOff.sumDb[0][freqIndex]

        assertEquals("tilt must not appear in the low branch curve", 0.0, lowDelta, 1e-6)
        assertEquals("tilt must not appear in the mid branch curve", 0.0, midDelta, 1e-6)
        assertTrue("expected a real tilt boost below the pivot, got $sumDelta", sumDelta > 1.0)
    }

    @Test
    fun postGainIsAppliedLastToTheSumOnly() {
        val values = baseValues()
        val without = compute(values)
        val boosted = compute(values.copyOf().also { it[10] = it[10] + 4f; it[11] = it[11] + 4f })

        val freqIndex = nearestIndex(1_000.0)
        val lowDelta = boosted.lowBranchDb[0][freqIndex] - without.lowBranchDb[0][freqIndex]
        val sumDelta = boosted.sumDb[0][freqIndex] - without.sumDb[0][freqIndex]

        assertEquals("post gain must not leak into the low branch curve", 0.0, lowDelta, 1e-6)
        assertEquals(4.0, sumDelta, 1e-6)
    }

    @Test
    fun leftTaggedFullRangeBandLandsOnPhysicalRightOutput() {
        val values = baseValues()
        val without = compute(values, peqWith())
        val with = compute(values, peqWith(full = listOf(band(1_000.0, 12.0, q = 2.0, channel = ParametricEqChannel.LEFT))))

        val freqIndex = nearestIndex(1_000.0)
        val rightDelta = with.sumDb[BmwOutputChannel.RIGHT.ordinal][freqIndex] - without.sumDb[BmwOutputChannel.RIGHT.ordinal][freqIndex]
        val leftDelta = with.sumDb[BmwOutputChannel.LEFT.ordinal][freqIndex] - without.sumDb[BmwOutputChannel.LEFT.ordinal][freqIndex]

        // Native's processLeft() results feed the physical RIGHT output (l=oR; r=oL;).
        assertTrue("LEFT-tagged band should boost the physical RIGHT output, got $rightDelta", rightDelta > 3.0)
        assertEquals("LEFT-tagged band must not affect the physical LEFT output", 0.0, leftDelta, 1e-6)
    }

    @Test
    fun channelMuteOneSilencesPhysicalRightOutputOnly() {
        val result = compute(baseValues().also { it[3] = 1f })

        val freqIndex = nearestIndex(1_000.0)
        assertEquals(FLOOR_DB, result.sumDb[BmwOutputChannel.RIGHT.ordinal][freqIndex], 1e-6)
        assertTrue(result.sumDb[BmwOutputChannel.LEFT.ordinal][freqIndex] > FLOOR_DB + 1.0)
    }

    @Test
    fun lowInvertChangesSumButNotLowBranchMagnitude() {
        // Bypass the mid crossover (hpfPass=1) so mid == the unfiltered pre-split signal --
        // a generic, non-adversarial phase reference to sum the low branch against. (At the
        // low/mid crossover itself, a matched Butterworth-style pair can legitimately land
        // low and mid in exact phase quadrature, where |sum| is invariant to inverting
        // either one -- that's real crossover math, not a bug, so this test avoids relying
        // on that specific region.)
        val values = baseValues().also { it[2] = 1f }
        val notInverted = compute(values.copyOf().also { it[19] = 0f })
        val inverted = compute(values.copyOf().also { it[19] = 1f })

        // Polarity flips sign, not magnitude: |-z| == |z|, at every point.
        for (i in notInverted.lowBranchDb[0].indices) {
            assertEquals("low branch magnitude at point $i", notInverted.lowBranchDb[0][i], inverted.lowBranchDb[0][i], 1e-6)
        }
        val region = nearestIndex(50.0)..nearestIndex(500.0)
        val maxDelta = region.maxOf { abs(inverted.sumDb[0][it] - notInverted.sumDb[0][it]) }
        assertTrue("expected inverting the low branch to change the summed result somewhere in 50-500Hz, max delta was $maxDelta", maxDelta > 0.5)
    }

    @Test
    fun processorDisabledIsUnityAcrossTheBoard() {
        val result = compute(baseValues().also { it[0] = 0f })
        assertTrue(!result.processorEnabled)
        for (channel in 0..1) {
            result.sumDb[channel].forEach { assertEquals(0.0, it, 1e-6) }
        }
    }

    private fun baseValues(): FloatArray = floatArrayOf(
        1f, 0f, 0f, 0f, 0f,
        -6f, 0f, 0f, -1f, -1f, 0f, 0f,
        1f, 32f,
        0f, 150f, 0f,
        0f, 125f,
        0f, 0f,
        0f, 0f, 0f, 0f,
        1f, 3f, 550f,
        1f, -12f, 2f, 8f, 40f, 250f, 1.5f,
    )

    companion object {
        private const val POINT_COUNT = 192
        private const val SAMPLE_RATE = 48_000.0
        private const val FLOOR_DB = -120.0
    }
}
