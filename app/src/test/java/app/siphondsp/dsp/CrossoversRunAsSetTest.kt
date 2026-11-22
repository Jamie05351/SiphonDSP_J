package app.siphondsp.dsp

import app.siphondsp.model.BmwPeqState
import app.siphondsp.model.NativeBmwDspValues
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Crossovers run exactly as set: the graph models each pair at its set corners and the user's own
 * polarity, nothing moved or inverted behind the controls (the BW2/BW4 auto-pair is removed).
 */
class CrossoversRunAsSetTest {
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

    /** Sum minus pre-split on the left channel, in dB, at the frequency nearest [hz]. */
    private fun sumAt(values: FloatArray, hz: Double): Double {
        calculator.invalidateAll()
        calculator.configureAxis(48_000.0, 20.0, 20_000.0)
        calculator.compute(values, BmwPeqState.empty(), curves)
        val i = curves.frequencies.indices.minBy { abs(curves.frequencies[it] - hz) }
        return curves.sumDb[0][i] - curves.preSplitDb[0][i]
    }

    @Test
    fun bw4PairSumsToPlus3DbAtTheCorner() {
        assertEquals(3.0, sumAt(twoWay(NativeBmwDspValues.CROSSOVER_TYPE_BW4), 150.0), 0.1)
    }

    @Test
    fun bw2PairShowsItsNullAtTheCorner() {
        // Same polarity on both bands: a BW2 pair cancels at the corner, as it really does.
        assertTrue(sumAt(twoWay(NativeBmwDspValues.CROSSOVER_TYPE_BW2), 150.0) < -6.0)
    }

    @Test
    fun aLeftoverAutoPairSwitchChangesNothing() {
        val clean = twoWay(NativeBmwDspValues.CROSSOVER_TYPE_BW2)
        val leftover = clean.copyOf().also { it[313] = 1f; it[314] = 1f }
        assertEquals(sumAt(clean, 150.0), sumAt(leftover, 150.0), 0.0)
    }
}
