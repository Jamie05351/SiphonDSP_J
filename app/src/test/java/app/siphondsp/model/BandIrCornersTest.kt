package app.siphondsp.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BandIrCornersTest {
    private fun manifest(lowMid: Double, midHigh: Double) =
        BandIrManifest(48000, 32, 16, lowMid, midHigh, listOf("low.wav", "mid.wav", "high.wav"))

    private fun corner(values: FloatArray, index: Int) = values[index]

    @Test fun defaultConfigWithMidUpperOffUsesHighHighpassAndNeverNaN() {
        val values = NativeBmwDspValues.DEFAULTS.copyOf()
        assertEquals(0f, values[NativeBmwDspValues.midUpperXoIndex(NativeBmwDspValues.OUTPUT_MID_LEFT, NativeBmwDspValues.MID_UPPER_XO_FIELD_ENABLED)])
        val corners = BandIrManifest.appCorners { values[it] }
        assertEquals(4, corners.size)
        assertTrue(corners.all { it.first.isFinite() && it.second.isFinite() })
        val highHp = corner(values, NativeBmwDspValues.highOutputIndex(NativeBmwDspValues.OUTPUT_HIGH_LEFT, NativeBmwDspValues.FIELD_CROSSOVER_FREQ))
        assertEquals(highHp, corners[0].second)
    }

    @Test fun packageDesignedForTheAppsCornersMatches() {
        val values = NativeBmwDspValues.DEFAULTS.copyOf()
        val lowMid = 120f
        val midHigh = 2800f
        for (side in 0..1) {
            values[NativeBmwDspValues.outputIndex(NativeBmwDspValues.OUTPUT_LOW_LEFT + side, NativeBmwDspValues.FIELD_CROSSOVER_FREQ)] = lowMid
            values[NativeBmwDspValues.outputIndex(NativeBmwDspValues.OUTPUT_MID_LEFT + side, NativeBmwDspValues.FIELD_CROSSOVER_FREQ)] = lowMid
            values[NativeBmwDspValues.highOutputIndex(NativeBmwDspValues.OUTPUT_HIGH_LEFT + side, NativeBmwDspValues.FIELD_CROSSOVER_FREQ)] = midHigh
        }
        val package_ = manifest(lowMid.toDouble(), midHigh.toDouble())
        assertTrue(BandIrManifest.appCorners { values[it] }.all { package_.matchesCrossovers(it.first, it.second) })
    }

    @Test fun midUpperCornerIsUsedWhenEnabled() {
        val values = NativeBmwDspValues.DEFAULTS.copyOf()
        val mid = NativeBmwDspValues.OUTPUT_MID_LEFT
        values[NativeBmwDspValues.midUpperXoIndex(mid, NativeBmwDspValues.MID_UPPER_XO_FIELD_ENABLED)] = 1f
        values[NativeBmwDspValues.midUpperXoIndex(mid, NativeBmwDspValues.MID_UPPER_XO_FIELD_FREQ)] = 3300f
        assertEquals(3300f, BandIrManifest.appCorners { values[it] }[0].second)
    }
}
