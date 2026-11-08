package app.siphondsp.dsp

import app.siphondsp.model.BmwPeqState
import app.siphondsp.model.NativeBmwDspValues
import app.siphondsp.model.ParametricEqBand
import app.siphondsp.model.ParametricEqBandList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoHeadroomTest {

    private val calculator = AutoHeadroomCalculator()

    private fun bands(vararg band: ParametricEqBand) = ParametricEqBandList().apply { band.forEach(::add) }

    private fun peq(preampDb: Float = 0f, full: ParametricEqBandList = bands()) =
        BmwPeqState(true, preampDb, full, bands(), bands(), bands())

    private fun values(edit: (FloatArray) -> Unit = {}) = NativeBmwDspValues.DEFAULTS.copyOf().also(edit)

    private fun headroom(values: FloatArray = values(), peq: BmwPeqState = peq()) =
        calculator.headroomDb(values, peq, 48_000.0)

    @Test
    fun headroomSitsTheMarginBelowThePeakWithinTheRange() {
        assertEquals(-7.5f, AutoHeadroom.headroomForPeak(6.0), 1e-6f)
        assertEquals(-1.5f, AutoHeadroom.headroomForPeak(0.0), 1e-6f)
        // Never a boost: a response that already peaks well below 0 dBFS gets 0 dB.
        assertEquals(0f, AutoHeadroom.headroomForPeak(-9.0), 0f)
        // No deeper than the headroom range.
        assertEquals(-30f, AutoHeadroom.headroomForPeak(40.0), 0f)
    }

    @Test
    fun thePreampMovesTheHeadroomDecibelForDecibel() {
        val flat = headroom()
        val boosted = headroom(peq = peq(preampDb = 6f))
        assertEquals(flat - 6f, boosted, 1e-3f)
    }

    @Test
    fun theManualHeadroomInTheValuesHasNoInfluence() {
        assertEquals(
            headroom(values { it[NativeBmwDspValues.INDEX_HEADROOM] = 0f }),
            headroom(values { it[NativeBmwDspValues.INDEX_HEADROOM] = -12f }),
            0f,
        )
    }

    @Test
    fun virtualBassAddsItsWorstCaseOnlyWhereItActs() {
        val flat = headroom()
        fun vb(amount: Float, edit: (FloatArray) -> Unit = {}) = values {
            it[NativeBmwDspValues.INDEX_VIRTUAL_BASS_ENABLED] = 1f
            it[NativeBmwDspValues.INDEX_VIRTUAL_BASS_FREQ] = 50f
            it[NativeBmwDspValues.INDEX_VIRTUAL_BASS_AMOUNT] = amount
            edit(it)
        }
        // Off, or on at 0%: no change.
        assertEquals(flat, headroom(vb(0f)), 1e-4f)
        // At 100%, sub kept: the measured worst peak rise, 20 log10(1 + 0.55) = 3.81 dB.
        assertEquals(flat - 3.81f, headroom(vb(100f)), 0.05f)
        // At 50%: 20 log10(1.275) = 2.11 dB.
        assertEquals(flat - 2.11f, headroom(vb(50f)), 0.05f)
        // Remove sub on: what's taken away nearly cancels what's added -- 20 log10(1.12) = 0.98 dB at
        // 100%, 20 log10(1.072) = 0.60 dB at 60%.
        val removing: (FloatArray) -> Unit = { it[NativeBmwDspValues.INDEX_VIRTUAL_BASS_REMOVE_SUB] = 1f }
        assertEquals(flat - 0.98f, headroom(vb(100f, removing)), 0.1f)
        assertEquals(flat - 0.60f, headroom(vb(60f, removing)), 0.1f)
        // A treble boost well above 5x the cut already sets the headroom: Virtual Bass adds nothing.
        val treble = peq(full = bands(ParametricEqBand(4000.0, 9.0, 1.0)))
        assertEquals(headroom(peq = treble), headroom(vb(100f), treble), 0.05f)
    }

    @Test
    fun virtualBassAllowanceCoversHowFarUpItsRangeReaches() {
        // A narrow +9 dB bell at 380 Hz, with a 50 Hz reach. At range 3 the harmonics stop at
        // 150 Hz, far below it, so it sets the headroom alone; at range 8 they reach 400 Hz, so
        // the bell's peak counts with the allowance on top.
        val bell = peq(full = bands(ParametricEqBand(380.0, 9.0, 4.0)))
        fun vb(range: Float) = values {
            it[NativeBmwDspValues.INDEX_VIRTUAL_BASS_ENABLED] = 1f
            it[NativeBmwDspValues.INDEX_VIRTUAL_BASS_FREQ] = 50f
            it[NativeBmwDspValues.INDEX_VIRTUAL_BASS_AMOUNT] = 100f
            it[NativeBmwDspValues.INDEX_VIRTUAL_BASS_RANGE] = range
        }
        assertEquals(headroom(peq = bell), headroom(vb(3f), bell), 0.05f)
        assertEquals(headroom(peq = bell) - 3.81f, headroom(vb(8f), bell), 0.1f)
    }

    @Test
    fun virtualBassAddsNothingWhenTheBassRegionIsQuietAndTheRefinedPeakIsInTheTreble() {
        // -12 dB preamp and a +9 dB bell at 4 kHz: the loudest point is -3 dB, up in the treble,
        // and the bass region sits at -12 dB, so even +6 dB of allowance there (-6 dB) needs no
        // cut. The 4 kHz fine pass has no point below 5x the cut and must not count as a 0 dB
        // bass peak.
        val p = peq(preampDb = -12f, full = bands(ParametricEqBand(4000.0, 9.0, 1.0)))
        val plain = headroom(values { it[NativeBmwDspValues.INDEX_MEASUREMENT_MUTE] = 0f }, p)
        val withVb = headroom(
            values {
                it[NativeBmwDspValues.INDEX_MEASUREMENT_MUTE] = 0f
                it[NativeBmwDspValues.INDEX_VIRTUAL_BASS_ENABLED] = 1f
                it[NativeBmwDspValues.INDEX_VIRTUAL_BASS_FREQ] = 50f
                it[NativeBmwDspValues.INDEX_VIRTUAL_BASS_AMOUNT] = 100f
            },
            p,
        )
        assertEquals(0f, plain, 0.01f)
        assertEquals(plain, withVb, 0.01f)
    }

    @Test
    fun virtualBassAllowanceRidesANarrowBassPeakFoundBetweenGridPoints() {
        // +12 dB at Q 30 near 100 Hz, inside 5x a 50 Hz cut: the refined peak, not the coarse
        // grid's lower reading, gets the 100% allowance.
        val sharp = bands(ParametricEqBand(103.7, 12.0, 30.0))
        val h = headroom(
            values {
                it[NativeBmwDspValues.INDEX_MEASUREMENT_MUTE] = 0f
                it[NativeBmwDspValues.INDEX_VIRTUAL_BASS_ENABLED] = 1f
                it[NativeBmwDspValues.INDEX_VIRTUAL_BASS_FREQ] = 50f
                it[NativeBmwDspValues.INDEX_VIRTUAL_BASS_AMOUNT] = 100f
            },
            peq(full = sharp),
        )
        val ideal = -(12.0 + 20.0 * kotlin.math.log10(1.55) + AutoHeadroom.MARGIN_DB).toFloat()
        assertEquals("headroom vs ideal", ideal, h, 0.05f)
    }

    @Test
    fun aNarrowPreEqPeakIsFoundBetweenGridPoints() {
        // +12 dB at the PEQ's highest Q, off any round frequency: the grid must not step over it.
        val sharp = bands(ParametricEqBand(1234.5, 12.0, 30.0))
        val h = headroom(values { it[NativeBmwDspValues.INDEX_MEASUREMENT_MUTE] = 0f }, peq(full = sharp))
        val ideal = -(12.0 + AutoHeadroom.MARGIN_DB).toFloat()
        assertEquals("headroom vs ideal", ideal, h, 0.02f)
    }

    @Test
    fun aMutedBandsGainDoesNotCount() {
        // Measurement mute 1 keeps Mid only, so Low is silent: boosting it changes nothing.
        val midOnly = values { it[NativeBmwDspValues.INDEX_MEASUREMENT_MUTE] = 1f }
        val midOnlyLoudLow = values {
            it[NativeBmwDspValues.INDEX_MEASUREMENT_MUTE] = 1f
            it[NativeBmwDspValues.INDEX_LOW_GAIN_L] = 12f
            it[NativeBmwDspValues.INDEX_LOW_GAIN_R] = 12f
        }
        assertEquals(headroom(midOnly), headroom(midOnlyLoudLow), 0f)
    }

    @Test
    fun anAudibleBandsGainCounts() {
        val quiet = headroom()
        val loudLow = headroom(values {
            it[NativeBmwDspValues.INDEX_LOW_GAIN_L] = 12f
            it[NativeBmwDspValues.INDEX_LOW_GAIN_R] = 12f
        })
        assertTrue("loud Low $loudLow vs $quiet", loudLow < quiet - 3f)
    }

    @Test
    fun effectiveValuesReplaceOnlyTheHeadroomAndOnlyInAuto() {
        val manual = values { it[NativeBmwDspValues.INDEX_HEADROOM] = -4f }
        assertSame(manual, calculator.effectiveValues(manual, peq(), 48_000.0))

        val auto = values {
            it[NativeBmwDspValues.INDEX_HEADROOM] = -4f
            it[NativeBmwDspValues.INDEX_HEADROOM_AUTO] = 1f
        }
        val effective = calculator.effectiveValues(auto, peq(preampDb = 6f), 48_000.0)
        assertNotSame(auto, effective)
        assertEquals(-4f, auto[NativeBmwDspValues.INDEX_HEADROOM], 0f)  // manual value kept
        assertEquals(headroom(auto, peq(preampDb = 6f)), effective[NativeBmwDspValues.INDEX_HEADROOM], 0f)
        for (i in auto.indices) {
            if (i != NativeBmwDspValues.INDEX_HEADROOM) assertEquals("index $i", auto[i], effective[i], 0f)
        }
    }
}
