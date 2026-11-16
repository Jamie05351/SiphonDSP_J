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
