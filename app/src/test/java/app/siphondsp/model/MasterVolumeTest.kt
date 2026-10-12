package app.siphondsp.model

import app.siphondsp.receiver.MasterVolumeReceiver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A non-finite volume must never be stored: native configure() would reject the whole config. */
class MasterVolumeTest {
    @Test
    fun clampKeepsTheRangeAndRefusesNonFiniteValues() {
        assertEquals(-24f, MasterVolume.clamp(-24f))
        assertEquals(0f, MasterVolume.clamp(6f))
        assertEquals(-80f, MasterVolume.clamp(-200f))
        assertNull(MasterVolume.clamp(Float.NaN))
        assertNull(MasterVolume.clamp(Float.POSITIVE_INFINITY))
        assertNull(MasterVolume.clamp(Float.NEGATIVE_INFINITY))
    }

    @Test
    fun theReceiverOnlyAcceptsAFiniteDbExtra() {
        assertEquals(-30f, MasterVolumeReceiver.parseDb("-30"))
        assertEquals(-30f, MasterVolumeReceiver.parseDb(" -30 "))
        assertEquals(-12.5f, MasterVolumeReceiver.parseDb(-12.5f))
        assertNull(MasterVolumeReceiver.parseDb("NaN"))
        assertNull(MasterVolumeReceiver.parseDb(Float.NaN))
        assertNull(MasterVolumeReceiver.parseDb("Infinity"))
        assertNull(MasterVolumeReceiver.parseDb("-Infinity"))
        assertNull(MasterVolumeReceiver.parseDb("loud"))
        assertNull(MasterVolumeReceiver.parseDb(null))
    }

    @Test
    fun theLabelNeverCrashesOnABadValue() {
        assertEquals("DSP volume: ?", MasterVolume.label(MasterVolume.State(Float.NaN, false)))
        assertEquals("DSP volume: -24 dB", MasterVolume.label(MasterVolume.State(-24f, false)))
        assertEquals("DSP volume: muted (0 dB)", MasterVolume.label(MasterVolume.State(0f, true)))
    }
}
