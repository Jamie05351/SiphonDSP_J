package app.siphondsp.dsp

import app.siphondsp.model.BmwPeqState
import app.siphondsp.model.NativeBmwDspValues
import app.siphondsp.model.ParametricEqBand
import app.siphondsp.model.ParametricEqBandList
import app.siphondsp.model.ParametricEqChannel
import app.siphondsp.model.ParametricEqFilterType
import app.siphondsp.model.ThreeWayCrossover
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
        val highBranchDb: Array<DoubleArray>,
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
            highBranchDb = Array(2) { curves.highBranchDb[it].copyOf() },
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
        high: List<ParametricEqBand> = emptyList(),
        preampDb: Float = 0f,
    ) = BmwPeqState(
        enabled = true,
        preampDb = preampDb,
        fullRangeBands = ParametricEqBandList().apply { addAll(full) },
        lowBandBands = ParametricEqBandList().apply { addAll(low) },
        midBandBands = ParametricEqBandList().apply { addAll(mid) },
        highBandBands = ParametricEqBandList().apply { addAll(high) },
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
    fun fullRangePeqDeliversItsFullRequestedGainAtCentre() {
        // A moderate-Q peaking band must land its whole nominal dB at its centre frequency --
        // no hidden scaling. -6 dB in => ~-6 dB out at 1 kHz, +12 in => ~+12 out at 250 Hz.
        for ((freq, gainDb) in listOf(250.0 to 12.0, 1_000.0 to -6.0, 4_000.0 to -18.0)) {
            val baseline = compute(baseValues())
            val filtered = compute(baseValues(), peqWith(full = listOf(band(freq, gainDb, q = 1.0))))
            val i = nearestIndex(freq)
            assertEquals(
                "full-bank peaking at $freq Hz",
                gainDb,
                filtered.sumDb[0][i] - baseline.sumDb[0][i],
                0.25,
            )
        }
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
    fun highBandPeqAffectsOnlyHighBranch() {
        // High ships muted/bypassed (highXoPass) by default -- un-mute and un-bypass it so its
        // branch is actually audible/comparable, mirroring the native crossover_test.cpp pattern.
        fun unmuteHigh(values: FloatArray) = values.also {
            it[NativeBmwDspValues.INDEX_HIGH_XO_PASS] = 0f
            it[NativeBmwDspValues.highOutputIndex(NativeBmwDspValues.OUTPUT_HIGH_LEFT, NativeBmwDspValues.FIELD_MUTE)] = 0f
            it[NativeBmwDspValues.highOutputIndex(NativeBmwDspValues.OUTPUT_HIGH_RIGHT, NativeBmwDspValues.FIELD_MUTE)] = 0f
        }
        val baseline = compute(unmuteHigh(baseValues()))
        val filtered = compute(unmuteHigh(baseValues()), peqWith(high = listOf(band(6_000.0, -12.0))))
        val i = nearestIndex(6_000.0)

        assertEquals(0.0, filtered.lowBranchDb[0][i] - baseline.lowBranchDb[0][i], 1e-6)
        assertEquals(0.0, filtered.midBranchDb[0][i] - baseline.midBranchDb[0][i], 1e-6)
        assertTrue(abs(filtered.highBranchDb[0][i] - baseline.highBranchDb[0][i]) > 6.0)
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
    fun subsonicIsSecondOrderButterworthAtItsCornerFrequency() {
        val values = baseValues()
        val corner = values[NativeBmwDspValues.outputIndex(NativeBmwDspValues.OUTPUT_LOW_RIGHT, NativeBmwDspValues.FIELD_SUBSONIC_FREQ)].toDouble()
        val enabled = compute(values)
        val disabled = compute(values.copyOf().also {
            setOutput(it, NativeBmwDspValues.OUTPUT_LOW_LEFT, NativeBmwDspValues.FIELD_SUBSONIC_ENABLED, 0f)
            setOutput(it, NativeBmwDspValues.OUTPUT_LOW_RIGHT, NativeBmwDspValues.FIELD_SUBSONIC_ENABLED, 0f)
        })
        val i = nearestIndex(corner)

        assertEquals(-3.0, enabled.lowBranchDb[BmwOutputChannel.LEFT.ordinal][i] - disabled.lowBranchDb[BmwOutputChannel.LEFT.ordinal][i], 0.35)
    }

    @Test
    fun independentLowCrossoversAffectOnlyTheirPhysicalOutput() {
        val baseline = compute(baseValues())
        val changed = compute(baseValues().also {
            // Native's final l=oR;r=oL swap cancels the vehicle's physically-reversed wiring
            // harness, so OUTPUT_LOW_RIGHT maps directly (unswapped) to physical RIGHT -- see
            // BmwSignalChain.kt's BmwOutputChannel doc comment.
            setOutput(it, NativeBmwDspValues.OUTPUT_LOW_RIGHT, NativeBmwDspValues.FIELD_CROSSOVER_FREQ, 90f)
        })
        val i = nearestIndex(120.0)

        assertTrue(abs(changed.lowBranchDb[BmwOutputChannel.RIGHT.ordinal][i] - baseline.lowBranchDb[BmwOutputChannel.RIGHT.ordinal][i]) > 0.5)
        assertEquals(
            baseline.lowBranchDb[BmwOutputChannel.LEFT.ordinal][i],
            changed.lowBranchDb[BmwOutputChannel.LEFT.ordinal][i],
            1e-6,
        )
    }

    @Test
    fun bothCrossoversBypassedDoublesPreSplitSignalMatchingNativeRoutedSum() {
        // Native's processFrame() has no bothBypassed shortcut -- it always feeds Low+Mid+High
        // into sumToStereo() unconditionally (see NativeBmwDspProcessor.cpp's "Always use the
        // routed sum here" comment). Under default routing, Low and Mid each independently carry
        // the full pre-split signal (RoutingMatrix's default coefficients route the same channel
        // to both), so bypassing both crossover filters means the sum is genuinely 2x pre, not
        // 1x -- with High left at its default muted/silent state contributing nothing on top.
        val result = compute(baseValues().also {
            it[NativeBmwDspValues.INDEX_LPF_PASS] = 1f
            it[NativeBmwDspValues.INDEX_HPF_PASS] = 1f
            it[NativeBmwDspValues.INDEX_TILT_ENABLED] = 0f
        })
        assertTrue(result.bothCrossoversBypassed)
        for (i in result.sumDb[0].indices) {
            assertEquals(result.preSplitDb[0][i] + 20.0 * kotlin.math.log10(2.0), result.sumDb[0][i], 1e-6)
        }
    }

    @Test
    fun bothCrossoversBypassedWithHighActiveIncludesHighBranchInSum() {
        // Regression for the finding that bothBypassed's old sumAcc.setFrom(pre) shortcut
        // silently dropped High's entire contribution from the response graph whenever both
        // legacy crossovers were bypassed, even with High fully active -- a materially different
        // displayed sum than the audible native output, which always includes High.
        val result = compute(baseValues().also {
            it[NativeBmwDspValues.INDEX_LPF_PASS] = 1f
            it[NativeBmwDspValues.INDEX_HPF_PASS] = 1f
            it[NativeBmwDspValues.INDEX_TILT_ENABLED] = 0f
            it[NativeBmwDspValues.INDEX_HIGH_XO_PASS] = 0f
            it[NativeBmwDspValues.highOutputIndex(NativeBmwDspValues.OUTPUT_HIGH_LEFT, NativeBmwDspValues.FIELD_MUTE)] = 0f
            it[NativeBmwDspValues.highOutputIndex(NativeBmwDspValues.OUTPUT_HIGH_RIGHT, NativeBmwDspValues.FIELD_MUTE)] = 0f
            it[NativeBmwDspValues.INDEX_HIGH_GAIN_L] = 6f
            it[NativeBmwDspValues.INDEX_HIGH_GAIN_R] = 6f
        })
        assertTrue(result.bothCrossoversBypassed)
        // 10 kHz is well above the default 3 kHz LR4 High crossover corner, so the HPF itself
        // contributes negligible attenuation here -- isolating the assertion to "did High's
        // branch get summed in at all" rather than also depending on the filter's exact shape.
        val i = nearestIndex(10_000.0)
        assertTrue(
            "expected High's contribution to raise the sum above plain 2x pre",
            result.sumDb[0][i] > result.preSplitDb[0][i] + 20.0 * kotlin.math.log10(2.0) + 0.5,
        )
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
    fun leftTaggedFullRangeBandLandsOnPhysicalLeftOutput() {
        val baseline = compute(baseValues())
        val filtered = compute(
            baseValues(),
            peqWith(full = listOf(band(1_000.0, -12.0, channel = ParametricEqChannel.LEFT))),
        )
        val i = nearestIndex(1_000.0)

        // A LEFT-tagged band matches BmwOutputChannel.LEFT directly (no swap) -- see
        // BmwSignalChain.bandAppliesTo and its BmwOutputChannel doc comment.
        assertTrue(abs(filtered.sumDb[BmwOutputChannel.LEFT.ordinal][i] - baseline.sumDb[BmwOutputChannel.LEFT.ordinal][i]) > 6.0)
        assertEquals(0.0, filtered.sumDb[BmwOutputChannel.RIGHT.ordinal][i] - baseline.sumDb[BmwOutputChannel.RIGHT.ordinal][i], 1e-6)
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

        // OUTPUT_LOW_RIGHT maps directly (unswapped) to physical RIGHT -- see
        // BmwSignalChain.kt's BmwOutputChannel doc comment.
        for (i in baseline.lowBranchDb[BmwOutputChannel.RIGHT.ordinal].indices) {
            assertEquals(
                baseline.lowBranchDb[BmwOutputChannel.RIGHT.ordinal][i],
                inverted.lowBranchDb[BmwOutputChannel.RIGHT.ordinal][i],
                1e-6,
            )
            assertEquals(
                baseline.sumDb[BmwOutputChannel.LEFT.ordinal][i],
                inverted.sumDb[BmwOutputChannel.LEFT.ordinal][i],
                1e-6,
            )
        }
        val region = nearestIndex(50.0)..nearestIndex(500.0)
        val maxDelta = region.maxOf {
            abs(inverted.sumDb[BmwOutputChannel.RIGHT.ordinal][it] - baseline.sumDb[BmwOutputChannel.RIGHT.ordinal][it])
        }
        assertTrue("expected LowRight inversion to change physical RIGHT sum, max delta was $maxDelta", maxDelta > 0.5)
    }

    @Test
    fun lowOutputAllPassLeavesMagnitudeUnityButShiftsPhaseOnItsOwnPhysicalSideOnly() {
        // Raw output ordinal 1 = LowRight (LowLeft=0, LowRight=1, MidLeft=2, MidRight=3), which
        // maps directly (unswapped) to physical RIGHT -- see BmwSignalChain.kt's BmwOutputChannel
        // doc comment.
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
            baseline.lowBranchDb[BmwOutputChannel.RIGHT.ordinal][i],
            withAllPass.lowBranchDb[BmwOutputChannel.RIGHT.ordinal][i],
            1e-4,
        )
        assertEquals(
            baseline.lowBranchDb[BmwOutputChannel.LEFT.ordinal][i],
            withAllPass.lowBranchDb[BmwOutputChannel.LEFT.ordinal][i],
            1e-6,
        )
        assertEquals(
            baseline.midBranchDb[BmwOutputChannel.RIGHT.ordinal][i],
            withAllPass.midBranchDb[BmwOutputChannel.RIGHT.ordinal][i],
            1e-6,
        )
        val delta = withAllPass.sumDb[BmwOutputChannel.RIGHT.ordinal][i] - baseline.sumDb[BmwOutputChannel.RIGHT.ordinal][i]
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

    @Test
    fun firstOrderCrossoverGraphsMatchAnalyticalResponse() {
        val values = baseValues()
        values[NativeBmwDspValues.INDEX_MID_GAIN_L] = 0f
        values[NativeBmwDspValues.INDEX_MID_GAIN_R] = 0f
        for (output in 0..3) {
            setOutput(values, output, NativeBmwDspValues.FIELD_CROSSOVER_TYPE, NativeBmwDspValues.CROSSOVER_TYPE_BW1)
            setOutput(values, output, NativeBmwDspValues.FIELD_CROSSOVER_FREQ, 200f)
            setOutput(values, output, NativeBmwDspValues.FIELD_SUBSONIC_ENABLED, 0f)
        }
        val result = compute(values)
        for (channel in 0..1) {
            for (i in curves.frequencies.indices) {
                val ratio = kotlin.math.tan(Math.PI * curves.frequencies[i] / SAMPLE_RATE) /
                    kotlin.math.tan(Math.PI * 200.0 / SAMPLE_RATE)
                val low = -10.0 * kotlin.math.log10(1.0 + ratio * ratio)
                val high = 20.0 * kotlin.math.log10(ratio) + low
                assertEquals(low, result.lowBranchDb[channel][i] - result.preSplitDb[channel][i], 1e-5)
                assertEquals(high, result.midBranchDb[channel][i] - result.preSplitDb[channel][i], 1e-5)
            }
        }
    }

    @Test
    fun bw4CrossoverGraphsMatchButterworthFourthOrderResponse() {
        val values = baseValues()
        values[NativeBmwDspValues.INDEX_MID_GAIN_L] = 0f
        values[NativeBmwDspValues.INDEX_MID_GAIN_R] = 0f
        for (output in 0..3) {
            setOutput(values, output, NativeBmwDspValues.FIELD_CROSSOVER_TYPE, NativeBmwDspValues.CROSSOVER_TYPE_BW4)
            setOutput(values, output, NativeBmwDspValues.FIELD_CROSSOVER_FREQ, 200f)
            setOutput(values, output, NativeBmwDspValues.FIELD_SUBSONIC_ENABLED, 0f)
        }
        val result = compute(values)
        for (channel in 0..1) {
            for (i in curves.frequencies.indices) {
                // |H_LP|^2 = 1 / (1 + (w/wc)^8); the digital bilinear warp uses tan(pi f / fs).
                val ratio = kotlin.math.tan(Math.PI * curves.frequencies[i] / SAMPLE_RATE) /
                    kotlin.math.tan(Math.PI * 200.0 / SAMPLE_RATE)
                val low = -10.0 * kotlin.math.log10(1.0 + Math.pow(ratio, 8.0))
                val high = 20.0 * kotlin.math.log10(Math.pow(ratio, 4.0)) + low
                // Float biquads bottom out around -110 dB, so only compare above -90 dB.
                if (low > -90.0) {
                    assertEquals(low, result.lowBranchDb[channel][i] - result.preSplitDb[channel][i], 1e-4)
                }
                if (high > -90.0) {
                    assertEquals(high, result.midBranchDb[channel][i] - result.preSplitDb[channel][i], 1e-4)
                }
            }
        }
    }

    @Test
    fun midUpperCrossoverTurnsMidIntoABandpass() {
        // Disabled (the DEFAULTS/baseValues() state) is covered implicitly by every other test in
        // this file still passing unmodified -- they all build on baseValues(), where the upper
        // corner is off, so Mid stays HPF-only exactly as before this feature existed.
        val values = baseValues()
        values[NativeBmwDspValues.INDEX_MID_GAIN_L] = 0f
        values[NativeBmwDspValues.INDEX_MID_GAIN_R] = 0f
        val lowerFreq = 150.0
        val upperFreq = 3000.0
        for (output in intArrayOf(NativeBmwDspValues.OUTPUT_MID_LEFT, NativeBmwDspValues.OUTPUT_MID_RIGHT)) {
            setOutput(values, output, NativeBmwDspValues.FIELD_CROSSOVER_TYPE, NativeBmwDspValues.CROSSOVER_TYPE_LR4)
            setOutput(values, output, NativeBmwDspValues.FIELD_CROSSOVER_FREQ, lowerFreq.toFloat())
            values[NativeBmwDspValues.midUpperXoIndex(output, NativeBmwDspValues.MID_UPPER_XO_FIELD_ENABLED)] = 1f
            values[NativeBmwDspValues.midUpperXoIndex(output, NativeBmwDspValues.MID_UPPER_XO_FIELD_FREQ)] = upperFreq.toFloat()
        }
        val result = compute(values)
        for (channel in 0..1) {
            for (i in curves.frequencies.indices) {
                // Cascaded LTI stages multiply in linear magnitude, i.e. add in dB: the bandpass's
                // response is the independently-warped HPF (at lowerFreq) and LPF (at upperFreq)
                // LR4 contributions summed. |H_LP,LR4|^2 = 1/(1+ratio^4)^2 (-6 dB at the corner,
                // not BW4's -3 dB); the complementary HPF term differs by the general
                // 10*log10(ratio^(2*order)) relationship also used by the BW4 test above.
                val hpRatio = kotlin.math.tan(Math.PI * curves.frequencies[i] / SAMPLE_RATE) /
                    kotlin.math.tan(Math.PI * lowerFreq / SAMPLE_RATE)
                val hpLow = -20.0 * kotlin.math.log10(1.0 + Math.pow(hpRatio, 4.0))
                val hp = 20.0 * kotlin.math.log10(Math.pow(hpRatio, 4.0)) + hpLow

                val lpRatio = kotlin.math.tan(Math.PI * curves.frequencies[i] / SAMPLE_RATE) /
                    kotlin.math.tan(Math.PI * upperFreq / SAMPLE_RATE)
                val lp = -20.0 * kotlin.math.log10(1.0 + Math.pow(lpRatio, 4.0))

                val expected = hp + lp
                // Float biquads bottom out around -110 dB, so only compare above -90 dB.
                if (expected > -90.0) {
                    assertEquals(expected, result.midBranchDb[channel][i] - result.preSplitDb[channel][i], 1e-3)
                }
            }
        }
    }

    @Test
    fun threeWayToggleDefaultsOffAndOnMakesHighAudible() {
        val defaults = baseValues()
        assertTrue(!ThreeWayCrossover.isEnabled(defaults))

        val on = defaults.copyOf().also { v -> ThreeWayCrossover.updates(v, true).forEach { (i, x) -> v[i] = x } }
        assertTrue(ThreeWayCrossover.isEnabled(on))
        // Mid's upper lowpass and High's highpass must come up at the same corner and slope.
        val corner = on[ThreeWayCrossover.cornerIndex]
        ThreeWayCrossover.cornerMirrors.forEach { assertEquals(corner, on[it], 0f) }
        val midType = on[NativeBmwDspValues.outputIndex(NativeBmwDspValues.OUTPUT_MID_LEFT, NativeBmwDspValues.FIELD_CROSSOVER_TYPE)]
        ThreeWayCrossover.typeMirrors.forEach { assertEquals(midType, on[it], 0f) }

        val result = compute(on)
        // 10 kHz is well above the 3 kHz default corner: High must be near unity, not silent.
        val i = nearestIndex(10_000.0)
        assertTrue(
            "expected High audible at 10 kHz once 3-way is on",
            result.highBranchDb[0][i] - result.preSplitDb[0][i] > -3.0,
        )
    }

    @Test
    fun threeWayToggleOffIsBitIdenticalToTwoWay() {
        val defaults = baseValues()
        val on = defaults.copyOf().also { v -> ThreeWayCrossover.updates(v, true).forEach { (i, x) -> v[i] = x } }
        val off = on.copyOf().also { v -> ThreeWayCrossover.updates(v, false).forEach { (i, x) -> v[i] = x } }
        assertTrue(!ThreeWayCrossover.isEnabled(off))

        val twoWay = compute(defaults)
        val toggledOff = compute(off)
        for (channel in 0..1) {
            for (i in twoWay.sumDb[channel].indices) {
                assertEquals(twoWay.sumDb[channel][i], toggledOff.sumDb[channel][i], 0.0)
                assertEquals(twoWay.midBranchDb[channel][i], toggledOff.midBranchDb[channel][i], 0.0)
            }
        }
    }

    companion object {
        private const val POINT_COUNT = 192
        private const val SAMPLE_RATE = 48_000.0
        private const val FLOOR_DB = -120.0
    }
}
