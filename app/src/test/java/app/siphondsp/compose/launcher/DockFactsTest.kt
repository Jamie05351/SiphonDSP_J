package app.siphondsp.compose.launcher

import app.siphondsp.compose.home.HomeStage
import app.siphondsp.model.BmwPeqState
import app.siphondsp.model.NativeBmwDspValues
import app.siphondsp.model.ParametricEqBand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DockFactsTest {
    private val values = NativeBmwDspValues.DEFAULTS.copyOf().apply {
        this[NativeBmwDspValues.INDEX_LOW_CROSSOVER_FREQ] = 80f
        this[NativeBmwDspValues.INDEX_MID_CROSSOVER_FREQ] = 80f
        this[app.siphondsp.model.ThreeWayCrossover.midLowpassIndex] = 3200f
        this[app.siphondsp.model.ThreeWayCrossover.highHighpassIndex] = 3200f
        this[NativeBmwDspValues.INDEX_TILT_ENABLED] = 0f
        this[NativeBmwDspValues.INDEX_MASTER_VOLUME_DB] = -18f
        this[NativeBmwDspValues.INDEX_LOW_DELAY_L] = 1.2f
        this[NativeBmwDspValues.INDEX_LOW_DELAY_R] = 0.4f
    }
    private val peq = BmwPeqState.empty().copy(preampDb = -8f).also {
        it.fullRangeBands.add(ParametricEqBand(100.0, 3.0, 1.0))
        it.lowBandBands.add(ParametricEqBand(60.0, -2.0, 1.0))
    }

    @Test
    fun eachStageSaysWhatItIsSetTo() {
        assertEquals(listOf("filters" to "2", "preamp" to "−8.0 dB"), dockFacts(HomeStage.PEQ, values, peq, null, 3))
        assertEquals("1.20 ms", dockFacts(HomeStage.ALIGNMENT, values, peq, null, 3).first().second)
        assertEquals(
            listOf("low|mid" to "80 Hz", "mid|high" to "3.2k Hz", "tilt" to "off"),
            dockFacts(HomeStage.XOVERS, values, peq, null, 3),
        )
        val output = dockFacts(HomeStage.OUTPUT, values, peq, -1.5, 3)
        assertEquals("−1.5 dBFS", output[0].second)
        assertEquals("−18.0 dB", output[1].second)
    }

    @Test
    fun aSplitWhoseTwoFiltersDifferShowsBoth() {
        val v = values.copyOf().apply {
            this[NativeBmwDspValues.INDEX_MID_CROSSOVER_FREQ] = 100f
            this[app.siphondsp.model.ThreeWayCrossover.highHighpassIndex] = 3500f
        }
        val facts = dockFacts(HomeStage.XOVERS, v, peq, null, 3)
        assertEquals("80 / 100 Hz", facts[0].second)
        assertEquals("3.2k / 3.5k Hz", facts[1].second)
    }

    @Test
    fun twoWayHidesMidHighAndBypassSaysSo() {
        assertTrue(dockFacts(HomeStage.XOVERS, values, peq, null, 2).none { it.first == "mid|high" })
        assertEquals("bypassed", dockFacts(HomeStage.XOVERS, values, peq, null, 0).first().second)
        assertEquals("—", dockFacts(HomeStage.OUTPUT, values, peq, null, 2)[0].second)
    }
}
