package app.siphondsp.dsp.autoalign

import app.siphondsp.dsp.BmwOutputChannel
import app.siphondsp.model.BmwPeqState
import app.siphondsp.model.NativeBmwDspValues
import app.siphondsp.model.ParametricEqBandList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sqrt

class AutoAlignSolverTest {

    private fun peq() = BmwPeqState(true, 0f, ParametricEqBandList(), ParametricEqBandList(), ParametricEqBandList(), ParametricEqBandList())

    /** A 2nd-order analog all-pass at [f]. */
    private fun allPass(f: Double, fc: Double, q: Double): Complex {
        val s = Complex(0.0, f / fc)
        val s2 = s * s
        val num = s2 + Complex(-s.re / q, -s.im / q) + Complex.ONE
        val den = s2 + Complex(s.re / q, s.im / q) + Complex.ONE
        return num / den
    }

    /** An REW-style text export of [response], with [delayMs] taken out of the phase and noted. */
    private fun rewText(name: String, delayMs: Double = 0.0, response: (Double) -> Complex): String = buildString {
        appendLine("* Measurement data measured by REW V5.40")
        if (delayMs != 0.0) appendLine("* Note: ; Delay ${"%.4f".format(delayMs)} ms (-1.5 m) using estimated IR delay")
        appendLine("* Measurement: $name")
        appendLine("* Smoothing: 1/48 octave")
        appendLine("* Freq(Hz)\tSPL(dB)\tPhase(degrees)")
        var f = 20.0
        while (f <= 20_000.0) {
            val h = response(f)
            // What REW writes: the phase with the delay taken out (the note's delay is negative).
            val removed = h * Complex.polar(1.0, 2 * PI * f * (delayMs / 1000.0))
            val db = 10 * log10(removed.power())
            var deg = atan2(removed.im, removed.re) * 180 / PI
            if (deg > 180) deg -= 360
            appendLine("%.6f\t%.3f\t%.4f".format(f, db, deg))
            f *= 2.0.pow(1.0 / 96)
        }
    }

    private fun measurement(name: String, delayMs: Double = 0.0, response: (Double) -> Complex) =
        RewMeasurement.parse(name, rewText(name, delayMs, response)).getOrThrow()

    private fun flat(delayS: Double) = { f: Double -> Complex.polar(0.1, -2 * PI * f * delayS) }

    @Test
    fun theParserPutsTheRemovedDelayBack() {
        val m = measurement("L", delayMs = -3.0, response = flat(0.003))
        // With the delay restored, the phase at 100 Hz is that of a 3 ms delay again.
        val h = m.at(100.0)!!
        val expected = Complex.polar(0.1, -2 * PI * 100.0 * 0.003)
        assertEquals(expected.re, h.re, 2e-3)
        assertEquals(expected.im, h.im, 2e-3)
    }

    @Test
    fun itFindsTheAllPassThatUndoesTheSidesBeingOutOfStep() {
        // The right speakers arrive through the inverse of an all-pass at 170 Hz: a seat where
        // the two sides cancel around there. Measured with the DSP on, the fix belongs on the
        // app's right side and is that all-pass.
        val left = measurement("L", response = flat(0.004))
        val right = measurement("R") { f -> flat(0.004)(f) / allPass(f, 170.0, 0.5) }
        val result = AutoAlignSolver().solve(
            listOf(AutoAlignSolver.Seat("Driver", left, right, null)),
            AutoAlignSolver.MeasuredWith.DSP_ON, NativeBmwDspValues.DEFAULTS.copyOf(), peq(),
        )
        val p = result.proposal!!
        assertEquals(BmwOutputChannel.RIGHT, p.side)
        assertEquals(170.0, p.frequencyHz, 170.0 * 0.06)
        assertEquals(0.5, p.q, 0.15)
        assertTrue("improves the seat: ${result.scoreDb}", result.scoreDb > 1.0)
        // Section 2 of the right outputs: on, 2nd order, the corner and Q.
        val lowBase = NativeBmwDspValues.INDEX_ALL_PASS +
            (NativeBmwDspValues.OUTPUT_LOW_RIGHT * NativeBmwDspValues.ALL_PASS_SECTIONS_PER_OUTPUT + 1) * NativeBmwDspValues.ALL_PASS_SECTION_WIDTH
        assertEquals(1f, result.updates[lowBase])
        assertEquals(2f, result.updates[lowBase + 1])
        assertEquals(p.frequencyHz.toFloat(), result.updates[lowBase + 2])
    }

    @Test
    fun withTheDspOffTheReversedHarnessPutsTheFixOnTheOtherSide() {
        // Same seat measured with SiphonDSP off: the file labelled "R" played through the
        // left-hand speakers (the app's swap that corrects the harness wasn't running), so the
        // all-pass that lines it up goes on the app's LEFT outputs.
        val left = measurement("L", response = flat(0.004))
        val right = measurement("R") { f -> flat(0.004)(f) / allPass(f, 170.0, 0.5) }
        val result = AutoAlignSolver().solve(
            listOf(AutoAlignSolver.Seat("Driver", left, right, null)),
            AutoAlignSolver.MeasuredWith.DSP_OFF, NativeBmwDspValues.DEFAULTS.copyOf(), peq(),
        )
        assertEquals(BmwOutputChannel.LEFT, result.proposal!!.side)
    }

    @Test
    fun itModelsTheChainAsItWillRunEvenWithTheProcessorSwitchedOff() {
        // Measured with SiphonDSP off, it's often still off when Auto Align runs; the proposal
        // must come out the same as with it on, not as "nothing to change".
        val values = NativeBmwDspValues.DEFAULTS.copyOf().also { it[NativeBmwDspValues.INDEX_ENABLED] = 0f }
        val left = measurement("L", response = flat(0.004))
        val right = measurement("R") { f -> flat(0.004)(f) / allPass(f, 170.0, 0.5) }
        val result = AutoAlignSolver().solve(
            listOf(AutoAlignSolver.Seat("Driver", left, right, null)),
            AutoAlignSolver.MeasuredWith.DSP_OFF, values, peq(),
        )
        assertEquals(BmwOutputChannel.LEFT, result.proposal!!.side)
        assertTrue(result.scoreDb > 1.0)
    }

    @Test
    fun aSeatThatAlreadyAddsUpGetsNoProposal() {
        val left = measurement("L", response = flat(0.004))
        val right = measurement("R", response = flat(0.004))
        val result = AutoAlignSolver().solve(
            listOf(AutoAlignSolver.Seat("Driver", left, right, null)),
            AutoAlignSolver.MeasuredWith.DSP_ON, NativeBmwDspValues.DEFAULTS.copyOf(), peq(),
        )
        assertNull(result.proposal)
        assertTrue(result.updates.isEmpty())
    }

    @Test
    fun theBothChannelsMeasurementRecoversALeftRightOffsetTheDelayNotesMissed() {
        // The left file's delay note is 1.2 ms off; the both-channels measurement shows the truth.
        // (The right side carries some phase of its own, as real speakers do; with two pure delays
        // the magnitude fits two mirror-image offsets equally -- see the next test.)
        val trueLeft = flat(0.0042)
        val right = { f: Double -> flat(0.0050)(f) * allPass(f, 120.0, 0.7) }
        val left = measurement("L", delayMs = -3.0, response = { f -> trueLeft(f) * Complex.polar(1.0, 2 * PI * f * 0.0012) })
        val r = measurement("R", response = right)
        val both = measurement("L+R") { f -> trueLeft(f) + right(f) }
        val result = AutoAlignSolver().solve(
            listOf(AutoAlignSolver.Seat("Driver", left, r, both)),
            AutoAlignSolver.MeasuredWith.DSP_ON, NativeBmwDspValues.DEFAULTS.copyOf(), peq(),
        )
        val seat = result.seats.single()
        assertEquals(1.2, seat.shiftMs, 0.05)
        assertTrue("fit ${seat.fitRmsDb}", seat.fitRmsDb!! < 0.3)
        assertTrue(!seat.timingAmbiguous)
    }

    @Test
    fun anOffsetTheFitCantTellFromItsMirrorIsntApplied() {
        // Two pure delays: 1.2 ms and its mirror about the true offset fit the summed magnitude
        // exactly as well, so the shift's direction isn't known -- REW's timing is kept and flagged.
        val trueLeft = flat(0.0042)
        val right = flat(0.0050)
        val left = measurement("L", delayMs = -3.0, response = { f -> trueLeft(f) * Complex.polar(1.0, 2 * PI * f * 0.0012) })
        val result = AutoAlignSolver().solve(
            listOf(AutoAlignSolver.Seat("Driver", left, measurement("R", response = right), measurement("L+R") { f -> trueLeft(f) + right(f) })),
            AutoAlignSolver.MeasuredWith.DSP_ON, NativeBmwDspValues.DEFAULTS.copyOf(), peq(),
        )
        val seat = result.seats.single()
        assertTrue(seat.timingAmbiguous)
        assertEquals(0.0, seat.shiftMs, 1e-9)
    }

    @Test
    fun replacingASectionInUseIsFlagged() {
        val values = NativeBmwDspValues.DEFAULTS.copyOf()
        val base = AutoAlignSolver.sectionTwoIndices(BmwOutputChannel.RIGHT).first()
        values[base] = 1f
        values[base + 1] = 1f
        values[base + 2] = 900f
        values[base + 3] = 0.7f
        val left = measurement("L", response = flat(0.004))
        val right = measurement("R") { f -> flat(0.004)(f) / allPass(f, 170.0, 0.5) }
        val result = AutoAlignSolver().solve(
            listOf(AutoAlignSolver.Seat("Driver", left, right, null)),
            AutoAlignSolver.MeasuredWith.DSP_ON, values, peq(),
        )
        assertEquals(BmwOutputChannel.RIGHT, result.proposal!!.side)
        assertTrue(result.replacesExisting)
        assertTrue(sqrt(result.scoreDb * result.scoreDb) > 0.5)
    }
}
