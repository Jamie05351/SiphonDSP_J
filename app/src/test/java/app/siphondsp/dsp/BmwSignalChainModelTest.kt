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
 * Verifies BmwResponseCalculator mirrors NativeBmwDspProcessor::processFrame's linear
 * response stages, including the independent per-output configuration block.
 */
class BmwSignalChainModelTest {
    private val calculator = BmwResponseCalculator(pointCount = POINT_COUNT)
    private val curves = BmwResponseCurves(POINT_COUNT)

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

    private fun setOutput(values: FloatArray, output: Int, field: Int, value: Float) {
        values[NativeBmwDspValues.outputIndex(output, field)] = value
    }

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
    fun lowCrossoverBypassAlsoBypassesSubsonicLikeNative() {
        val withSubsonicConfigured = compute(baseValues().also { it[NativeBmwDspValues.INDEX_LPF_PASS] = 1f })
        val subsonicDisabled = compute(baseValues().also {
            it[NativeBmwDspValues.INDEX_LPF_PASS] = 1f
            setOutput(it, NativeBmwDspValues.OUTPUT_LOW_LEFT, NativeBmwDspValues.FIELD_SUBSONIC_ENABLED, 0f)
            setOutput(it, NativeBmwDspValues.OUTPUT_LOW_RIGHT, NativeBmwDspValues.FIELD_SUBSONIC_ENABLED, 0f)
        })
        val i = nearestIndex(20.0)

        assertEquals(subsonicDisabled.lowBranchDb[0][i], withSubsonicConfigured.lowBranchDb[0][i], 1e-6)
        assertEquals(subsonicDisabled.lowBranchDb[1][i], withSubsonicConfigured.lowBranchDb[1][i], 1e-6)
    }

    @Test
    fun subsonicIsLr4AtItsCornerFrequency() {
        val values = baseValues()
        val corner = values[NativeBmwDspValues.outputIndex(NativeBmwDspValues.OUTPUT_LOW_RIGHT, NativeBmwDspValues.FIELD_SUBSONIC_FREQ)].toDouble()
        val enabled = compute(values)
        val disabled = compute(values.copyOf().also {
            setOutput(it, NativeBmwDspValues.OUTPUT_LOW_LEFT, NativeBmwDspValues.FIELD_SUBSONIC_ENABLED, 0f)
            setOutput(it, NativeBmwDspValues.OUTPUT_LOW_RIGHT, NativeBmwDspValues.FIELD_SUBSONIC_ENABLED, 0f)
        })
        val i = nearestIndex(corner)

        assertEquals(-6.0, enabled.lowBranchDb[BmwOutputChannel.LEFT.ordinal][i] - disabled.lowBranchDb[BmwOutputChannel.LEFT.ordinal][i], 0.35)
    }

    @Test
    fun independentLowCrossoversAffectOnlyTheirPhysicalOutput() {
        val baseline = compute(baseValues())
        val changed = compute(baseValues().also {
            // Physical LEFT is fed by native LowRight after the final native L/R swap.
            setOutput(it, NativeBmwDspValues.OUTPUT_LOW_RIGHT, NativeBmwDspValues.FIELD_CROSSOVER_FREQ, 90f)
        })
        val i = nearestIndex(120.0)

        assertTrue(abs(changed.lowBranchDb[BmwOutputChannel.LEFT.ordinal][i] - baseline.lowBranchDb[BmwOutputChannel.LEFT.ordinal][i]) > 0.5)
        assertEquals(
            baseline.lowBranchDb[BmwOutputChannel.RIGHT.ordinal][i],
            changed.lowBranchDb[BmwOutputChannel.RIGHT.ordinal][i],
            1e-6,
        )
    }

    @Test
    fun bothCrossoversBypassedReturnsPreSplitSignalNotDoubled() {
        val result = compute(baseValues().also {
            it[NativeBmwDspValues.INDEX_LPF_PASS] = 1f
            it[NativeBmwDspValues.INDEX_HPF_PASS] = 1f
            it[NativeBmwDspValues.INDEX_TILT_ENABLED] = 0f
        })
        assertTrue(result.bothCrossoversBypassed)
        for (i in result.sumDb[0].indices) {
            assertEquals(result.preSplitDb[0][i], result.sumDb[0][i], 1e-6)
        }
    }

    @Test
    fun tiltAppliesAfterSummationNotToIndividualBranches() {
        val baseline = compute(baseValues().also { it[NativeBmwDspValues.INDEX_TILT_ENABLED] = 0f })
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
        val result = compute(baseValues().also { it[NativeBmwDspValues.INDEX_CHANNEL_MUTE] = 1f })

        val freqIndex = nearestIndex(1_000.0)
        assertEquals(FLOOR_DB, result.sumDb[BmwOutputChannel.RIGHT.ordinal][freqIndex], 1e-6)
        assertTrue(result.sumDb[BmwOutputChannel.LEFT.ordinal][freqIndex] > FLOOR_DB + 1.0)
    }

    @Test
    fun perOutputLowInvertChangesOnlyMappedPhysicalSum() {
        val baseline = compute(baseValues().also { it[NativeBmwDspValues.INDEX_HPF_PASS] = 1f })
        val inverted = compute(baseValues().also {
            it[NativeBmwDspValues.INDEX_HPF_PASS] = 1f
            setOutput(it, NativeBmwDspValues.OUTPUT_LOW_RIGHT, NativeBmwDspValues.FIELD_INVERT, 1f)
        })

        for (i in baseline.lowBranchDb[BmwOutputChannel.LEFT.ordinal].indices) {
            assertEquals(
                baseline.lowBranchDb[BmwOutputChannel.LEFT.ordinal][i],
                inverted.lowBranchDb[BmwOutputChannel.LEFT.ordinal][i],
                1e-6,
            )
            assertEquals(
                baseline.sumDb[BmwOutputChannel.RIGHT.ordinal][i],
                inverted.sumDb[BmwOutputChannel.RIGHT.ordinal][i],
                1e-6,
            )
        }
        val region = nearestIndex(50.0)..nearestIndex(500.0)
        val maxDelta = region.maxOf {
            abs(inverted.sumDb[BmwOutputChannel.LEFT.ordinal][it] - baseline.sumDb[BmwOutputChannel.LEFT.ordinal][it])
        }
        assertTrue("expected LowRight inversion to change physical LEFT sum, max delta was $maxDelta", maxDelta > 0.5)
    }

    @Test
    fun lowOutputAllPassLeavesMagnitudeUnityButShiftsPhaseOnItsOwnPhysicalSideOnly() {
        val allPassBase = NativeBmwDspValues.INDEX_ALL_PASS + (1 * 2 + 0) * NativeBmwDspValues.ALL_PASS_SECTION_WIDTH
        val baseline = compute(baseValues())
        val withAllPass = compute(
            baseValues().also {
                it[allPassBase] = 1f
                it[allPassBase + 1] = 2f
                it[allPassBase + 2] = 140f
                it[allPassBase + 3] = 0.70710677f
            },
        )
        val i = nearestIndex(140.0)

        assertEquals(
            baseline.lowBranchDb[BmwOutputChannel.LEFT.ordinal][i],
            withAllPass.lowBranchDb[BmwOutputChannel.LEFT.ordinal][i],
            1e-4,
        )
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
        val delta = withAllPass.sumDb[BmwOutputChannel.LEFT.ordinal][i] - baseline.sumDb[BmwOutputChannel.LEFT.ordinal][i]
        assertTrue("expected sum to change at the crossover overlap, delta was $delta", abs(delta) > 0.05)
    }

    @Test
    fun processorDisabledIsUnityAcrossTheBoard() {
        val result = compute(baseValues().also { it[NativeBmwDspValues.INDEX_ENABLED] = 0f })
        assertTrue(!result.processorEnabled)
        for (channel in 0..1) {
            result.sumDb[channel].forEach { assertEquals(0.0, it, 1e-6) }
        }
    }

    private fun baseValues(): FloatArray = NativeBmwDspValues.DEFAULTS.copyOf().also {
        it[NativeBmwDspValues.INDEX_OUTPUT_SCHEMA_VERSION] = NativeBmwDspValues.OUTPUT_SCHEMA_VERSION
    }

    companion object {
        private const val POINT_COUNT = 192
        private const val SAMPLE_RATE = 48_000.0
        private const val FLOOR_DB = -120.0
    }
}
