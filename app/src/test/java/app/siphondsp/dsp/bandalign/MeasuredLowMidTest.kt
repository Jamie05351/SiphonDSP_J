package app.siphondsp.dsp.bandalign

import app.siphondsp.dsp.BmwOutputChannel
import app.siphondsp.model.NativeBmwDspValues
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.log10
import kotlin.math.pow

/** The measured Low + Mid sum follows today's delays and polarities exactly. */
class MeasuredLowMidTest {

    private fun rewText(name: String, response: (Double) -> Complex): String = buildString {
        appendLine("* Measurement data measured by REW V5.40")
        appendLine("* Measurement: $name")
        appendLine("* Smoothing: 1/48 octave")
        appendLine("* Freq(Hz)\tSPL(dB)\tPhase(degrees)")
        var f = 20.0
        while (f <= 20_000.0) {
            val c = response(f)
            appendLine("%.6f\t%.3f\t%.4f".format(java.util.Locale.ROOT, f, 10 * log10(c.power()), atan2(c.im, c.re) * 180 / PI))
            f *= 2.0.pow(1.0 / 96)
        }
    }

    private fun flat(name: String) = RewMeasurement.parse(name, rewText(name) { Complex(0.1, 0.0) }).getOrThrow()
    private val f = doubleArrayOf(100.0)
    private val zero = MeasuredLowMid.Timing(0.0, false)

    @Test
    fun unchangedTimingGivesTheMeasuredSum() {
        val r = MeasuredLowMid.sum(flat("Low"), zero, zero, flat("Mid"), zero, zero, f)
        assertEquals(r.thenDb[0], r.nowDb[0], 1e-6)
        assertEquals(20 * log10(0.2), r.nowDb[0], 0.05) // two equal, in-phase halves: +6 dB over one
    }

    @Test
    fun flippingLowPolarityCancelsTwoEqualInPhaseBands() {
        val r = MeasuredLowMid.sum(flat("Low"), zero, MeasuredLowMid.Timing(0.0, true), flat("Mid"), zero, zero, f)
        assertTrue("an inverted copy cancels", r.nowDb[0] < r.thenDb[0] - 40)
    }

    @Test
    fun halfAPeriodOfLowDelayAlsoCancelsAndAFullPeriodRestores() {
        val half = MeasuredLowMid.sum(flat("Low"), zero, MeasuredLowMid.Timing(5.0, false), flat("Mid"), zero, zero, f) // 5 ms at 100 Hz
        val full = MeasuredLowMid.sum(flat("Low"), zero, MeasuredLowMid.Timing(10.0, false), flat("Mid"), zero, zero, f)
        assertTrue(half.nowDb[0] < half.thenDb[0] - 40)
        assertEquals(full.thenDb[0], full.nowDb[0], 0.01)
    }

    @Test
    fun timingIsReadFromEachSidesOwnSlots() {
        val v = NativeBmwDspValues.DEFAULTS.copyOf()
        v[NativeBmwDspValues.INDEX_LOW_DELAY_R] = 1.25f
        v[NativeBmwDspValues.outputIndex(NativeBmwDspValues.OUTPUT_MID_LEFT, NativeBmwDspValues.FIELD_INVERT)] = 1f
        assertEquals(1.25, MeasuredLowMid.lowTiming(v, BmwOutputChannel.RIGHT).delayMs, 1e-6)
        assertTrue(MeasuredLowMid.midTiming(v, BmwOutputChannel.LEFT).inverted)
        assertFalse(MeasuredLowMid.midTiming(v, BmwOutputChannel.RIGHT).inverted)
    }

    @Test
    fun aMovedCrossoverIsFlagged() {
        val then = NativeBmwDspValues.DEFAULTS.copyOf()
        val now = then.copyOf().also { it[NativeBmwDspValues.INDEX_LOW_CROSSOVER_FREQ] = it[NativeBmwDspValues.INDEX_LOW_CROSSOVER_FREQ] + 10f }
        assertFalse(MeasuredLowMid.crossoverChanged(then, then.copyOf()))
        assertTrue(MeasuredLowMid.crossoverChanged(then, now))
    }

    @Test
    fun aChangedSlopeIsFlaggedToo() {
        val then = NativeBmwDspValues.DEFAULTS.copyOf()
        for (output in intArrayOf(NativeBmwDspValues.OUTPUT_LOW_LEFT, NativeBmwDspValues.OUTPUT_MID_RIGHT)) {
            val idx = NativeBmwDspValues.outputIndex(output, NativeBmwDspValues.FIELD_CROSSOVER_TYPE)
            val now = then.copyOf().also { it[idx] = if (it[idx] == 2f) 1f else 2f }
            assertTrue("slope on output $output", MeasuredLowMid.crossoverChanged(then, now))
        }
    }
}
