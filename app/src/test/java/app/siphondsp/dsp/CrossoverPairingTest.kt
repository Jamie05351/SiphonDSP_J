package app.siphondsp.dsp

import app.siphondsp.model.BmwPeqState
import app.siphondsp.model.NativeBmwDspValues
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The BW2/BW4 pair auto-correction, as the graph models it (native: pairCrossovers, covered by
 * native-tests/crossover_test.cpp). The graph must show the corrected, flat sum.
 */
class CrossoverPairingTest {
    private val calculator = BmwResponseCalculator(pointCount = 192)
    private val curves = BmwResponseCurves(192)

    private fun twoWay(type: Float): FloatArray = NativeBmwDspValues.DEFAULTS.copyOf().also {
        it[NativeBmwDspValues.INDEX_OUTPUT_SCHEMA_VERSION] = NativeBmwDspValues.OUTPUT_SCHEMA_VERSION
        it[NativeBmwDspValues.INDEX_TILT_ENABLED] = 0f
        it[NativeBmwDspValues.INDEX_MID_GAIN_L] = 0f
        it[NativeBmwDspValues.INDEX_MID_GAIN_R] = 0f
        for (output in 0 until 4) {
            it[NativeBmwDspValues.outputIndex(output, NativeBmwDspValues.FIELD_CROSSOVER_FREQ)] = 150f
            it[NativeBmwDspValues.outputIndex(output, NativeBmwDspValues.FIELD_CROSSOVER_TYPE)] = type
            it[NativeBmwDspValues.outputIndex(output, NativeBmwDspValues.FIELD_SUBSONIC_ENABLED)] = 0f
        }
    }

    /** Worst |sum - pre-split| between 40 Hz and 600 Hz on the left channel, in dB. */
    private fun worstSumDeviationDb(values: FloatArray): Double {
        calculator.invalidateAll()
        calculator.configureAxis(48_000.0, 20.0, 20_000.0)
        calculator.compute(values, BmwPeqState.empty(), curves)
        var worst = 0.0
        for (i in curves.frequencies.indices) {
            val f = curves.frequencies[i]
            if (f < 40.0 || f > 600.0) continue
            worst = maxOf(worst, abs(curves.sumDb[0][i] - curves.preSplitDb[0][i]))
        }
        return worst
    }

    @Test
    fun bw2AndBw4PairsSumFlatInTheGraph() {
        assertTrue(worstSumDeviationDb(twoWay(NativeBmwDspValues.CROSSOVER_TYPE_BW2)) < 0.6)
        assertTrue(worstSumDeviationDb(twoWay(NativeBmwDspValues.CROSSOVER_TYPE_BW4)) < 0.6)
        assertTrue(worstSumDeviationDb(twoWay(NativeBmwDspValues.CROSSOVER_TYPE_LR4)) < 0.1)
    }

    @Test
    fun pairingFollowsTheSlopesOnBothSidesOfTheBoundary() {
        val bw2 = CrossoverPairing.of(twoWay(NativeBmwDspValues.CROSSOVER_TYPE_BW2), left = true)
        assertTrue(bw2.lowMidCorrected)
        assertTrue(bw2.midInverted)
        assertEquals(1.0 / CrossoverPairing.BW2_SPREAD, bw2.lowLowpass, 1e-6)
        assertEquals(CrossoverPairing.BW2_SPREAD.toDouble(), bw2.midHighpass, 1e-6)

        val bw4 = CrossoverPairing.of(twoWay(NativeBmwDspValues.CROSSOVER_TYPE_BW4), left = true)
        assertTrue(bw4.lowMidCorrected)
        assertFalse(bw4.midInverted)

        // Mismatched slopes, or a bypassed crossover, aren't a pair.
        val mixed = twoWay(NativeBmwDspValues.CROSSOVER_TYPE_BW2).also {
            it[NativeBmwDspValues.outputIndex(NativeBmwDspValues.OUTPUT_MID_LEFT, NativeBmwDspValues.FIELD_CROSSOVER_TYPE)] =
                NativeBmwDspValues.CROSSOVER_TYPE_LR4
        }
        assertFalse(CrossoverPairing.of(mixed, left = true).lowMidCorrected)
        val bypassed = twoWay(NativeBmwDspValues.CROSSOVER_TYPE_BW2).also {
            it[NativeBmwDspValues.INDEX_HPF_PASS] = 1f
        }
        assertFalse(CrossoverPairing.of(bypassed, left = true).lowMidCorrected)
        // A deliberate gap (Low 80 Hz, Mid 200 Hz) isn't one boundary: left alone.
        val gap = twoWay(NativeBmwDspValues.CROSSOVER_TYPE_BW2).also {
            for (output in 0 until 2) it[NativeBmwDspValues.outputIndex(output, NativeBmwDspValues.FIELD_CROSSOVER_FREQ)] = 80f
            for (output in 2 until 4) it[NativeBmwDspValues.outputIndex(output, NativeBmwDspValues.FIELD_CROSSOVER_FREQ)] = 200f
        }
        assertFalse(CrossoverPairing.of(gap, left = true).lowMidCorrected)
        assertFalse(CrossoverPairing.of(gap, left = true).midInverted)
        assertFalse(CrossoverPairing.of(twoWay(NativeBmwDspValues.CROSSOVER_TYPE_LR4), left = true).lowMidCorrected)
    }
}
