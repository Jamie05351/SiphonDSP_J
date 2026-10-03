package app.siphondsp.view

import app.siphondsp.model.NativeBmwDspValues
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeStageStatusTest {

    private fun values() = FloatArray(NativeBmwDspValues.SIZE)

    private fun band(band: Int) = NativeBmwDspValues.mbcBandIndex(band, NativeBmwDspValues.MBC_FIELD_ENABLED)

    /** Switches on all-pass [section] of a legacy (Low / Mid) [output] at [hz]. */
    private fun FloatArray.allPass(output: Int, section: Int, hz: Float) {
        val base = NativeBmwDspValues.INDEX_ALL_PASS +
            (output * NativeBmwDspValues.ALL_PASS_SECTIONS_PER_OUTPUT + section) * NativeBmwDspValues.ALL_PASS_SECTION_WIDTH
        this[base] = 1f
        this[base + 2] = hz
    }

    @Test
    fun allOffReadsAsOff() {
        assertEquals(HomeStageStatus.OFF, HomeStageStatus.from(values()))
    }

    @Test
    fun aBandIsLitOnlyWhileTheMbcItselfIsOn() {
        val v = values()
        v[band(1)] = 1f
        v[band(3)] = 1f
        assertEquals(listOf(false, false, false, false), HomeStageStatus.from(v).mbcBands)
        v[NativeBmwDspValues.INDEX_MBC_ENABLED] = 1f
        assertEquals(listOf(false, true, false, true), HomeStageStatus.from(v).mbcBands)
    }

    @Test
    fun eachOutputListsTheFrequenciesOfItsSwitchedOnSections() {
        val v = values()
        v.allPass(NativeBmwDspValues.OUTPUT_LOW_RIGHT, 0, 80f)
        v.allPass(NativeBmwDspValues.OUTPUT_MID_LEFT, 0, 250f)
        v.allPass(NativeBmwDspValues.OUTPUT_MID_LEFT, 1, 2500f)
        // A switched-off section keeps its frequency, but isn't shown.
        v[NativeBmwDspValues.INDEX_ALL_PASS + 2] = 150f
        val high = NativeBmwDspValues.highAllPassIndex(NativeBmwDspValues.OUTPUT_HIGH_RIGHT, 1, 0)
        v[high] = 1f
        v[high + 2] = 4000f

        val allPass = HomeStageStatus.from(v).allPass
        assertEquals(listOf("LO L", "LO R", "MID L", "MID R", "HI L", "HI R"), allPass.map { it.label })
        assertEquals(
            listOf(emptyList(), listOf(80f), listOf(250f, 2500f), emptyList(), emptyList(), listOf(4000f)),
            allPass.map { it.frequenciesHz },
        )
        assertEquals(listOf(false, true, true, false, false, true), allPass.map { it.on })
    }

    @Test
    fun aShortArrayReadsAsOff() {
        assertTrue(HomeStageStatus.from(FloatArray(10)) == HomeStageStatus.OFF)
    }
}
