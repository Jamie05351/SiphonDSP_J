package app.siphondsp.dsp

import app.siphondsp.model.BmwPeqState
import app.siphondsp.model.NativeBmwDspValues
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
        val lowRaised = compute(baseValues().also { it[6] += 3f; it[7] += 3f })
        val midRaised = compute(baseValues().also { it[8] += 3f; it[9] += 3f })
        val i = nearestIndex(1_000.0)

        assertEquals(3.0, lowRaised.lowBranchDb[0][i] - baseline.lowBranchDb[0][i], 1e-6)
        assertEquals(0.0, lowRaised.midBranchDb[0][i] - baseline.midBranchDb[0][i], 1e-6)
        assertEquals(0.0, midRaised.lowBranchDb[0][i] - baseline.lowBranchDb[0][i], 1e-6)
        assertEquals(3.0, midRaised.midBranchDb[0][i] - baseline.midBranchDb[0][i], 1e-6)
    }

    @Test
    fun fullRangePeqAppliesIdenticallyToLowMidAndSumBeforeSplit() {
        val baseline = compute(baseValues())
        val filtered = compute(baseValues(), peqWith(full = listOf(band(1_000.0, -6.0))))
        val i = nearestIndex(1_000.0)

        val preDelta = filtered.preSplitDb[0][i] - baseline.preSplitDb[0][i]
        assertEquals(preDelta, filtered.lowBranchDb[0][i] - baseline.lowBranchDb[0][i], 1e-6)
        assertEquals(preDelta, filtered.midBranchDb[0][i] - baseline.midBranchDb[0][i], 1e-6)
        assertEquals(preDelta, filtered.sumDb[0][i] - baseline.sumDb[0][i], 1e-6)
    }

    @Test
    fun lowBandPeqAffectsOnlyLowBranch() {
        val baseline = compute(baseValues())
        val filtered = compute(baseValues(), peqWith(low = listOf(band(80.0, -12.0))))
        val i = nearestIndex(80.0)

        assertTrue(abs(filtered.lowBranchDb[0][i] - baseline.lowBranchDb[0][i]) > 6.0)
        assertEquals(0.0, filtered.midBranchDb[0][i] - baseline.midBranchDb[0][i], 1e-6)
    }

    @Test
    fun midBandPeqAffectsOnlyMidBranch() {
        val baseline = compute(baseValues())
        val filtered = compute(baseValues(), peqWith(mid = listOf(band(1_000.0, -12.0))))
        val i = nearestIndex(1_000.0)

        assertEquals(0.0, filtered.lowBranchDb[0][i] - baseline.lowBranchDb[0][i], 1e-6)
        assertTrue(abs(filtered.midBranchDb[0][i] - baseline.midBranchDb[0][i]) > 6.0)
    }

    @Test
    fun lowCrossoverBypassedStillAppliesSubsonicFilter() {
        val bypassed = compute(baseValues().also { it[1] = 1f })
        val subsonicOff = compute(baseValues().also { it[1] = 1f; it[12] = 0f })
        val i = nearestIndex(20.0)

        assertTrue(bypassed.lowBranchDb[0][i] < subsonicOff.lowBranchDb[0][i] - 3.0)
    }

    @Test
    fun bothCrossoversBypassedReturnsPreSplitSignalNotDoubled() {
        val result = compute(baseValues().also { it[1] = 1f; it[2] = 1f; it[25] = 0f })
        assertTrue(result.bothCrossoversBypassed)
        for (i in result.sumDb[0].indices) {
            assertEquals(result.preSplitDb[0][i], result.sumDb[0][i], 1e-6)
        }
    }

    @Test
    fun tiltAppliesAfterSummationNotToIndividualBranches() {
        val baseline = compute(baseValues().also { it[25] = 0f })
        val tilted = compute(baseValues())
        val i = nearestIndex(100.0)

        assertEquals(0.0, tilted.lowBranchDb[0][i] - baseline.lowBranchDb[0][i], 1e-6)
        assertEquals(0.0, tilted.midBranchDb[0][i] - baseline.midBranchDb[0][i], 1e-6)
        assertTrue(abs(tilted.sumDb[0][i] - baseline.sumDb[0][i]) > 0.5)
    }

    @Test
    fun postGainIsAppliedLastToTheSumOnly() {
        val baseline = compute(baseValues())
        val raised = compute(baseValues().also { it[10] += 4f; it[11] += 4f })
        val i = nearestIndex(1_000.0)

        assertEquals(0.0, raised.lowBranchDb[0][i] - baseline.lowBranchDb[0][i], 1e-6)
        assertEquals(0.0, raised.midBranchDb[0][i] - baseline.midBranchDb[0][i], 1e-6)
        assertEquals(4.0, raised.sumDb[0][i] - baseline.sumDb[0][i], 1e-6)
    }

    @Test
    fun leftTaggedFullRangeBandLandsOnPhysicalRightOutput() {
        val baseline = compute(baseValues())
        val filtered = compute(
            baseValues(),
            peqWith(full = listOf(band(1_000.0, -12.0, channel = ParametricEqChannel.LEFT))),
        )
        val i = nearestIndex(1_000.0)

        assertEquals(0.0, filtered.sumDb[BmwOutputChannel.LEFT.ordinal][i] - baseline.sumDb[BmwOutputChannel.LEFT.ordinal][i], 1e-6)
        assertTrue(abs(filtered.sumDb[BmwOutputChannel.RIGHT.ordinal][i] - baseline.sumDb[BmwOutputChannel.RIGHT.ordinal][i]) > 6.0)
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
    fun lowOutputAllPassLeavesMagnitudeUnityButShiftsPhaseOnItsOwnPhysicalSideOnly() {
        // Physical LEFT is fed by native's internal "right" chain (see BmwSignalChain KDoc),
        // which is NativeBmwRouting::OutputId::LowRight -- ordinal 1 -- so its all-pass lives
        // at INDEX_ALL_PASS + (1*2+0)*4 = 62. This pins down the output-ordinal wiring, not
        // just the underlying all-pass math (already covered in isolation elsewhere).
        // 140Hz sits inside the crossover overlap between hpf=125Hz and lpf=150Hz, where low
        // and mid contribute comparable levels to the sum -- the region where a phase shift
        // on one branch actually has a visible effect on the recombined magnitude.
        val allPassBase = NativeBmwDspValues.INDEX_ALL_PASS + (1 * 2 + 0) * NativeBmwDspValues.ALL_PASS_SECTION_WIDTH
        val baseline = compute(baseValues())
        val withAllPass = compute(
            baseValues().also {
                it[allPassBase] = 1f      // enabled
                it[allPassBase + 1] = 2f  // second order
                it[allPassBase + 2] = 140f
                it[allPassBase + 3] = 0.70710677f
            },
        )
        val i = nearestIndex(140.0)

        // Unity magnitude (an all-pass section changes phase, not level) on the side it was
        // configured for...
        assertEquals(
            baseline.lowBranchDb[BmwOutputChannel.LEFT.ordinal][i],
            withAllPass.lowBranchDb[BmwOutputChannel.LEFT.ordinal][i],
            1e-4,
        )
        // ...and must not leak onto the opposite physical side or into the mid branch.
        assertEquals(
            baseline.lowBranchDb[BmwOutputChannel.RIGHT.ordinal][i],
            withAllPass.lowBranchDb[BmwOutputChannel.RIGHT.ordinal][i],
            1e-6,
        )
        assertEquals(
            baseline.midBranchDb[BmwOutputChannel.LEFT.ordinal][i],
            withAllPass.midBranchDb[BmwOutputChannel.LEFT.ordinal][i],
            1e-6,
        )
        // The sum on the affected side must actually have changed (phase shift recombines
        // differently with the mid branch) -- otherwise the all-pass wiring is a no-op.
        val delta = withAllPass.sumDb[BmwOutputChannel.LEFT.ordinal][i] - baseline.sumDb[BmwOutputChannel.LEFT.ordinal][i]
        assertTrue("expected sum to change at the crossover overlap, delta was $delta", abs(delta) > 0.05)
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
        0f, -10f, 1.5f, 6f, 10f, 180f, 0f,
        0f, 80f, 100f, 0f,
        // Low L, Low R, Mid L, Mid R: [Front L, Front R] -- unity same-side, zero crossfeed.
        1f, 0f, 0f, 1f, 1f, 0f, 0f, 1f,
        // Two disabled second-order all-pass sections per output: enabled, order, Hz, Q.
        0f, 2f, 150f, 0.70710677f, 0f, 2f, 150f, 0.70710677f,
        0f, 2f, 150f, 0.70710677f, 0f, 2f, 150f, 0.70710677f,
        0f, 2f, 150f, 0.70710677f, 0f, 2f, 150f, 0.70710677f,
        0f, 2f, 150f, 0.70710677f, 0f, 2f, 150f, 0.70710677f,
    )

    companion object {
        private const val POINT_COUNT = 192
        private const val SAMPLE_RATE = 48_000.0
        private const val FLOOR_DB = -120.0
    }
}
