package app.siphondsp.model

import app.siphondsp.model.NativeBmwDspValues.VIRTUAL_FEED_AP_ENABLED
import app.siphondsp.model.NativeBmwDspValues.VIRTUAL_FEED_DELAY
import app.siphondsp.model.NativeBmwDspValues.VIRTUAL_SIDE_LEFT
import app.siphondsp.model.NativeBmwDspValues.VIRTUAL_SIDE_RIGHT
import app.siphondsp.model.NativeBmwDspValues.virtualFeedIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VirtualCentrePresetTest {

    private fun applied(preset: VirtualCentrePreset, left: Float, right: Float): FloatArray =
        NativeBmwDspValues.DEFAULTS.copyOf().also { values ->
            VirtualCentrePreset.updates(preset, left, right).forEach { (i, v) -> values[i] = v }
        }

    @Test
    fun bothSeatsSpreadsWithAllPassAndNoDelay() {
        val v = applied(VirtualCentrePreset.BOTH_SEATS, 138f, 92f)
        for (side in listOf(VIRTUAL_SIDE_LEFT, VIRTUAL_SIDE_RIGHT)) {
            assertEquals(1f, v[virtualFeedIndex(side, VIRTUAL_FEED_AP_ENABLED)], 0f)
            assertEquals(0f, v[virtualFeedIndex(side, VIRTUAL_FEED_DELAY)], 0f)
        }
    }

    @Test
    fun driverDelaysTheNearerSideByThePathGap() {
        // Driver seat 138 cm from the Left mid, 92 cm from the Right: Right is nearer and waits
        // (138 - 92) / 34.3 = 1.34 ms; Left gets 0. No all-pass.
        val v = applied(VirtualCentrePreset.DRIVER, 138f, 92f)
        assertEquals(0f, v[virtualFeedIndex(VIRTUAL_SIDE_LEFT, VIRTUAL_FEED_DELAY)], 0f)
        assertEquals(1.34f, v[virtualFeedIndex(VIRTUAL_SIDE_RIGHT, VIRTUAL_FEED_DELAY)], 1e-4f)
        assertEquals(0f, v[virtualFeedIndex(VIRTUAL_SIDE_LEFT, VIRTUAL_FEED_AP_ENABLED)], 0f)
    }

    @Test
    fun driverDelayIsCappedAtTheFeedRange() {
        val (left, right) = VirtualCentrePreset.driverDelaysMs(10f, 1000f)
        assertEquals(NativeBmwDspValues.STAGE_DELAY_MAX_MS, left, 0f)
        assertEquals(0f, right, 0f)
    }

    @Test
    fun detectRecognisesEachPresetAndFallsBackToCustom() {
        for (preset in listOf(VirtualCentrePreset.BOTH_SEATS, VirtualCentrePreset.DRIVER)) {
            val v = applied(preset, 138f, 92f)
            assertEquals(preset, VirtualCentrePreset.detect({ v[it] }, 138f, 92f))
        }
        val tweaked = applied(VirtualCentrePreset.BOTH_SEATS, 138f, 92f)
        tweaked[NativeBmwDspValues.INDEX_VIRTUAL_CENTRE_LEVEL] = -3f
        assertEquals(VirtualCentrePreset.CUSTOM, VirtualCentrePreset.detect({ tweaked[it] }, 138f, 92f))
    }

    @Test
    fun customWritesNothing() {
        assertTrue(VirtualCentrePreset.updates(VirtualCentrePreset.CUSTOM, 138f, 92f).isEmpty())
    }
}
