package app.siphondsp.dsp.bandalign

import app.siphondsp.dsp.BmwOutputChannel
import app.siphondsp.model.NativeBmwDspValues
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sqrt

class BandAlignSolverTest {

    private val sqrt2 = sqrt(2.0)

    /** Linkwitz-Riley 4th-order low-pass / high-pass at [fc]: in phase, and summing flat, when aligned. */
    private fun lr4Low(f: Double, fc: Double): Complex {
        val s = Complex(0.0, f / fc)
        val bw = Complex.ONE / (s * s + Complex(s.re * sqrt2, s.im * sqrt2) + Complex.ONE)
        return bw * bw
    }

    private fun lr4High(f: Double, fc: Double): Complex {
        val s = Complex(0.0, f / fc)
        val bw = (s * s) / (s * s + Complex(s.re * sqrt2, s.im * sqrt2) + Complex.ONE)
        return bw * bw
    }

    private fun delay(f: Double, seconds: Double) = Complex.polar(1.0, -2 * PI * f * seconds)

    /** An REW-style text export of [response], with [delayMs] taken out of the phase and noted. */
    private fun rewText(name: String, delayMs: Double, smoothing: String, response: (Double) -> Complex): String = buildString {
        appendLine("* Measurement data measured by REW V5.40")
        if (delayMs != 0.0) appendLine("* Note: ; Delay ${"%.4f".format(delayMs)} ms (-1.5 m) using estimated IR delay")
        appendLine("* Measurement: $name")
        appendLine("* Smoothing: $smoothing")
        appendLine("* Freq(Hz)\tSPL(dB)\tPhase(degrees)")
        var f = 20.0
        while (f <= 20_000.0) {
            val removed = response(f) * Complex.polar(1.0, 2 * PI * f * (delayMs / 1000.0))
            val db = 10 * log10(removed.power())
            val deg = atan2(removed.im, removed.re) * 180 / PI
            appendLine("%.6f\t%.3f\t%.4f".format(f, db, deg))
            f *= 2.0.pow(1.0 / 96)
        }
    }

    private fun measurement(name: String, delayMs: Double = 0.0, smoothing: String = "1/48 octave", response: (Double) -> Complex) =
        RewMeasurement.parse(name, rewText(name, delayMs, smoothing, response)).getOrThrow()

    /** One seat's Low and Mid with the Low arriving [lowLateS] after where it sums flat, optionally inverted. */
    private fun seat(name: String, lowLateS: Double, lowInverted: Boolean = false, withBoth: Boolean = false, lowNoteErrorMs: Double = 0.0): BandAlignSolver.SeatMeasurements {
        val common = 0.004
        val sign = if (lowInverted) -1.0 else 1.0
        val low = { f: Double -> lr4Low(f, 140.0) * delay(f, common + lowLateS) * Complex(sign * 0.1, 0.0) }
        val mid = { f: Double -> lr4High(f, 140.0) * delay(f, common) * Complex(0.1, 0.0) }
        // REW's note for the Low file can be off; the phase it writes is then off by the same.
        val lowFile = measurement("$name low", delayMs = -4.0 + lowNoteErrorMs) { f -> low(f) * delay(f, -lowNoteErrorMs / 1000.0) }
        return BandAlignSolver.SeatMeasurements(
            name,
            lowFile,
            measurement("$name mid", delayMs = -4.0, response = mid),
            if (withBoth) measurement("$name both", delayMs = -4.0) { f -> low(f) + mid(f) } else null,
        )
    }

    private val band = 56.0..350.0

    @Test
    fun itFindsTheLowDelayThatBringsLowBackInStepWithMid() {
        // Low set to 2.20 ms and arriving 1.80 ms late: 0.40 ms lines it up.
        val r = BandAlignSolver().solveSide(
            BmwOutputChannel.LEFT, listOf(seat("Driver", 0.0018)),
            BandAlignSolver.Setting(2.2, false), band,
        )
        val p = r.proposal!!
        assertEquals(0.40, p.delayMs, 0.02)
        assertFalse(p.inverted)
        assertTrue("improves the sum: ${r.scoreDb}", r.scoreDb > 0.5)
    }

    @Test
    fun anInvertedLowIsPutBackTheRightWayRound() {
        val r = BandAlignSolver().solveSide(
            BmwOutputChannel.RIGHT, listOf(seat("Driver", 0.0, lowInverted = true)),
            BandAlignSolver.Setting(0.5, false), band,
        )
        val p = r.proposal!!
        assertTrue(p.inverted)
        assertEquals(0.5, p.delayMs, 0.02)
    }

    @Test
    fun aSideThatAlreadyAddsUpGetsNoProposal() {
        val r = BandAlignSolver().solveSide(
            BmwOutputChannel.LEFT, listOf(seat("Driver", 0.0), seat("Passenger", 0.0)),
            BandAlignSolver.Setting(0.8, false), band,
        )
        assertNull(r.proposal)
        assertTrue(BandAlignSolver.applyUpdates(NativeBmwDspValues.DEFAULTS.copyOf(), listOf(r))!!.isEmpty())
    }

    @Test
    fun twoSeatsAreSolvedTogether() {
        // The seats disagree (1.4 and 2.0 ms late, so 1.4 and 0.8 ms alone); the answer serves both.
        val r = BandAlignSolver().solveSide(
            BmwOutputChannel.LEFT, listOf(seat("Driver", 0.0014), seat("Passenger", 0.0020)),
            BandAlignSolver.Setting(2.8, false), band,
        )
        val d = r.proposal!!.delayMs
        assertTrue("between the single-seat answers: $d", d in 0.8..1.4)
        assertEquals(2, r.seats.size)
    }

    @Test
    fun aSettingThatLeavesEitherSeatWorseIsRejected() {
        // The driver's seat already adds up; only the passenger's is out. Any move helps the
        // passenger at the driver's expense, so nothing is proposed.
        val r = BandAlignSolver().solveSide(
            BmwOutputChannel.LEFT, listOf(seat("Driver", 0.0), seat("Passenger", 0.0018)),
            BandAlignSolver.Setting(2.2, false), band,
        )
        assertNull(r.proposal)
    }

    @Test
    fun theLowPlusMidMeasurementCorrectsAMistimedLowFile() {
        // The Low file's delay note is 0.5 ms off, which would move the answer by 0.5 ms; the
        // both-bands measurement shows the truth.
        val r = BandAlignSolver().solveSide(
            BmwOutputChannel.LEFT, listOf(seat("Driver", 0.0018, withBoth = true, lowNoteErrorMs = 0.5)),
            BandAlignSolver.Setting(2.2, false), band,
        )
        val s = r.seats.single()
        assertTrue("fit ${s.fitRmsDb}", s.fitRmsDb!! < 0.3)
        assertEquals(0.40, r.proposal!!.delayMs, 0.03)
    }

    @Test
    fun aBestAtTheEndOfTheRangeIsFlagged() {
        // Low set to 0 ms but arriving 3.0 ms early: it wants 3.0 ms, and the range stops at 2.8.
        val r = BandAlignSolver().solveSide(
            BmwOutputChannel.LEFT, listOf(seat("Driver", -0.0030)),
            BandAlignSolver.Setting(0.0, false), band,
        )
        assertEquals(BandAlignSolver.DELAY_MAX_MS, r.proposal!!.delayMs, 1e-9)
        assertFalse(r.proposal!!.inverted)
        assertTrue(r.atRangeLimit)
    }

    @Test
    fun aLowWantedEarlierThanZeroIsFoundWhenTheOtherDelaysHaveRoom() {
        // Low set to 0 ms but arriving 1.8 ms late: it wants -1.8 ms (the mirror of the 2.2 ms case).
        val seats = listOf(seat("Driver", 0.0018))
        val r = BandAlignSolver().solveSide(BmwOutputChannel.LEFT, seats, BandAlignSolver.Setting(0.0, false), band, minDelayMs = -2.5)
        assertEquals(-1.8, r.proposal!!.delayMs, 0.02)
        assertFalse(r.proposal!!.inverted)
        assertFalse(r.atRangeLimit)
        // Without room below 0 nothing negative is ever proposed.
        val capped = BandAlignSolver().solveSide(BmwOutputChannel.LEFT, seats, BandAlignSolver.Setting(0.0, false), band)
        assertTrue((capped.proposal?.delayMs ?: 0.0) >= 0.0)
    }

    private fun values(high: Float = 0f, mid: Float = 0f, lowL: Float = 0f, lowR: Float = 0f) = NativeBmwDspValues.DEFAULTS.copyOf().also {
        it[NativeBmwDspValues.INDEX_HIGH_DELAY_L] = high; it[NativeBmwDspValues.INDEX_HIGH_DELAY_R] = high
        it[NativeBmwDspValues.INDEX_MID_DELAY_L] = mid; it[NativeBmwDspValues.INDEX_MID_DELAY_R] = mid
        it[NativeBmwDspValues.INDEX_LOW_DELAY_L] = lowL; it[NativeBmwDspValues.INDEX_LOW_DELAY_R] = lowR
    }

    private fun proposed(side: BmwOutputChannel, delayMs: Double, inverted: Boolean = false) = BandAlignSolver.SideResult(
        side, BandAlignSolver.Setting(0.0, false), BandAlignSolver.Setting(delayMs, inverted), 1.0, band, emptyList(), false,
    )

    @Test
    fun aPositiveProposalWritesOnlyThatSidesLowDelayAndPolarity() {
        val u = BandAlignSolver.applyUpdates(values(), listOf(proposed(BmwOutputChannel.RIGHT, 0.42, inverted = true)))!!
        assertEquals(0.42f, u[NativeBmwDspValues.INDEX_LOW_DELAY_R]!!, 1e-6f)
        assertEquals(1f, u[NativeBmwDspValues.outputIndex(NativeBmwDspValues.OUTPUT_LOW_RIGHT, NativeBmwDspValues.FIELD_INVERT)])
        assertEquals(2, u.size)
    }

    @Test
    fun aNegativeProposalMakesEveryOtherDelayLaterInstead() {
        // Left Low wants -0.5 ms: it goes to 0 and the other five delays all gain 0.5 ms.
        val u = BandAlignSolver.applyUpdates(values(high = 0.3f, mid = 0.2f, lowL = 0.1f, lowR = 0.4f), listOf(proposed(BmwOutputChannel.LEFT, -0.5)))!!
        assertEquals(0f, u[NativeBmwDspValues.INDEX_LOW_DELAY_L]!!, 1e-6f)
        assertEquals(0.9f, u[NativeBmwDspValues.INDEX_LOW_DELAY_R]!!, 1e-6f)
        assertEquals(0.7f, u[NativeBmwDspValues.INDEX_MID_DELAY_L]!!, 1e-6f)
        assertEquals(0.7f, u[NativeBmwDspValues.INDEX_MID_DELAY_R]!!, 1e-6f)
        assertEquals(0.8f, u[NativeBmwDspValues.INDEX_HIGH_DELAY_L]!!, 1e-6f)
        assertEquals(0.8f, u[NativeBmwDspValues.INDEX_HIGH_DELAY_R]!!, 1e-6f)
    }

    @Test
    fun anApplyThatWouldPassTheTopOfTheRangeIsRefused() {
        // Right Low wants 2.6 ms and Left Low -0.5 ms: the offset would put Right Low at 3.1 ms.
        val results = listOf(proposed(BmwOutputChannel.LEFT, -0.5), proposed(BmwOutputChannel.RIGHT, 2.6))
        assertNull(BandAlignSolver.applyUpdates(values(), results))
    }

    @Test
    fun theRoomBelowZeroIsWhatTheOtherDelaysLeaveUnderTheTop() {
        val v = values(high = 1.0f, mid = 2.2f, lowL = 0.5f, lowR = 0.3f)
        assertEquals(-(BandAlignSolver.DELAY_MAX_MS - 2.2), BandAlignSolver.minDelayMs(v, BmwOutputChannel.LEFT), 1e-9)
        assertEquals(0.0, BandAlignSolver.minDelayMs(values(mid = BandAlignSolver.DELAY_MAX_MS.toFloat()), BmwOutputChannel.LEFT), 1e-9)
    }

    @Test
    fun theScoredBandFollowsTheCrossover() {
        val values = NativeBmwDspValues.DEFAULTS.copyOf()
        values[NativeBmwDspValues.outputIndex(NativeBmwDspValues.OUTPUT_LOW_LEFT, NativeBmwDspValues.FIELD_CROSSOVER_FREQ)] = 150f
        values[NativeBmwDspValues.outputIndex(NativeBmwDspValues.OUTPUT_MID_LEFT, NativeBmwDspValues.FIELD_CROSSOVER_FREQ)] = 125f
        val b = BandAlignSolver.scoreBand(values, BmwOutputChannel.LEFT)
        val centre = sqrt(150.0 * 125.0)
        assertEquals(centre / 2.5, b.start, 1e-9)
        assertEquals(centre * 2.5, b.endInclusive, 1e-9)
    }

    @Test
    fun variableSmoothingCountsAsTooCoarse() {
        fun smoothed(label: String) = measurement("L", smoothing = label) { f -> delay(f, 0.004) }.heavilySmoothed
        assertTrue(smoothed("Psychoacoustic"))
        assertTrue(smoothed("ERB"))
        assertTrue(smoothed("1/6 octave"))
        assertFalse(smoothed("1/48 octave"))
        assertFalse(smoothed("1/12 octave"))
        assertFalse(smoothed("None"))
    }

    @Test
    fun theParserPutsTheRemovedDelayBack() {
        val m = measurement("L", delayMs = -3.0) { f -> Complex.polar(0.1, -2 * PI * f * 0.003) }
        val h = m.at(100.0)!!
        val expected = Complex.polar(0.1, -2 * PI * 100.0 * 0.003)
        assertEquals(expected.re, h.re, 2e-3)
        assertEquals(expected.im, h.im, 2e-3)
    }
}
