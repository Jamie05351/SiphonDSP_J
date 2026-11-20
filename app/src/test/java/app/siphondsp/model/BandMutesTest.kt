package app.siphondsp.model

import app.siphondsp.model.BandMutes.Band
import app.siphondsp.model.BandMutes.Side
import app.siphondsp.model.NativeBmwDspValues.FIELD_MUTE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class BandMutesTest {
    private fun apply(values: FloatArray, updates: Map<Int, Float>) = values.also { v -> updates.forEach { (i, x) -> v[i] = x } }

    @Test
    fun everySideRoundTripsForEveryBand() {
        for (band in Band.entries) for (side in Side.entries) {
            val values = apply(NativeBmwDspValues.DEFAULTS.copyOf(), BandMutes.updates(band, side))
            assertEquals("$band $side", side, BandMutes.side(band) { values[it] })
        }
    }

    @Test
    fun oneSideMuteLeavesTheOtherSideAndOtherBandsAlone() {
        val values = NativeBmwDspValues.DEFAULTS.copyOf()
        // Defaults seed High muted on both sides; start from everything playing.
        for (band in Band.entries) apply(values, BandMutes.updates(band, Side.NONE))
        apply(values, BandMutes.updates(Band.MID, Side.LEFT))
        val (midL, midR) = BandMutes.muteIndices(Band.MID)
        assertEquals(1f, values[midL])
        assertEquals(0f, values[midR])
        assertEquals(Side.NONE, BandMutes.side(Band.LOW) { values[it] })
        assertEquals(Side.NONE, BandMutes.side(Band.HIGH) { values[it] })
    }

    @Test
    fun indicesAreTheEngineOutputMuteFields() {
        assertEquals(NativeBmwDspValues.outputIndex(NativeBmwDspValues.OUTPUT_LOW_LEFT, FIELD_MUTE) to
            NativeBmwDspValues.outputIndex(NativeBmwDspValues.OUTPUT_LOW_RIGHT, FIELD_MUTE), BandMutes.muteIndices(Band.LOW))
        assertEquals(NativeBmwDspValues.highOutputIndex(NativeBmwDspValues.OUTPUT_HIGH_LEFT, FIELD_MUTE) to
            NativeBmwDspValues.highOutputIndex(NativeBmwDspValues.OUTPUT_HIGH_RIGHT, FIELD_MUTE), BandMutes.muteIndices(Band.HIGH))
    }

    @Test
    fun legacyWholeBandSlotOnlySetWhenBothSidesMuted() {
        assertEquals(1f, BandMutes.updates(Band.LOW, Side.BOTH)[NativeBmwDspValues.INDEX_LOW_MUTE])
        assertEquals(0f, BandMutes.updates(Band.LOW, Side.LEFT)[NativeBmwDspValues.INDEX_LOW_MUTE])
        assertEquals(0f, BandMutes.updates(Band.MID, Side.RIGHT)[NativeBmwDspValues.INDEX_MID_MUTE])
        assertFalse(BandMutes.updates(Band.HIGH, Side.BOTH).keys.any { it == NativeBmwDspValues.INDEX_LOW_MUTE || it == NativeBmwDspValues.INDEX_MID_MUTE })
        assertNull(BandMutes.updates(Band.HIGH, Side.LEFT)[NativeBmwDspValues.INDEX_MID_MUTE])
    }
}
