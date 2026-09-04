package app.siphondsp.view

import app.siphondsp.model.NativeBmwDspValues
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MonoBassCueTest {

    private val maxFreq = 20_000.0

    private fun values(enabled: Float = 1f, blend: Float = 80f, freq: Float = 80f, lpfPass: Float = 0f) =
        FloatArray(64).also {
            it[NativeBmwDspValues.INDEX_MONO_BASS_ENABLED] = enabled
            it[NativeBmwDspValues.INDEX_MONO_BASS_BLEND] = blend
            it[NativeBmwDspValues.INDEX_MONO_BASS_FREQ] = freq
            it[NativeBmwDspValues.INDEX_LPF_PASS] = lpfPass
        }

    @Test
    fun activeOnlyWhenEnabledAndBlendPositiveAndLpfNotBypassed() {
        assertTrue(MonoBassCue.isActive(values()))
        assertFalse(MonoBassCue.isActive(values(enabled = 0f)))
        assertFalse(MonoBassCue.isActive(values(blend = 0f)))
        assertFalse(MonoBassCue.isActive(values(lpfPass = 1f)))
    }

    @Test
    fun frequencyClampsToTwentyHzAndTheAxisMax() {
        assertEquals(80.0, MonoBassCue.frequency(values(freq = 80f), maxFreq), 1e-6)
        assertEquals(20.0, MonoBassCue.frequency(values(freq = 5f), maxFreq), 1e-6)
        assertEquals(maxFreq, MonoBassCue.frequency(values(freq = 99_000f), maxFreq), 1e-6)
    }

    @Test
    fun blendIsFullStrengthAtOrBelowTheCornerAndZeroAboveHalfAnOctave() {
        val v = values(blend = 60f, freq = 100f) // strength = 0.6, corner = 100 Hz
        assertEquals(0.6f, MonoBassCue.blendAt(v, 60.0, maxFreq), 1e-6f)
        assertEquals(0.6f, MonoBassCue.blendAt(v, 100.0, maxFreq), 1e-6f)
        assertEquals(0f, MonoBassCue.blendAt(v, 150.0, maxFreq), 1e-6f)   // corner * 1.5
        assertEquals(0f, MonoBassCue.blendAt(v, 400.0, maxFreq), 1e-6f)
    }

    @Test
    fun blendRampsLinearlyAcrossTheHalfOctaveAboveTheCorner() {
        val v = values(blend = 100f, freq = 100f) // strength 1.0, corner 100, zero at 150
        // Halfway through the 100..150 ramp -> half strength.
        assertEquals(0.5f, MonoBassCue.blendAt(v, 125.0, maxFreq), 1e-5f)
    }

    @Test
    fun blendIsZeroWhenTheCueIsInactive() {
        assertEquals(0f, MonoBassCue.blendAt(values(enabled = 0f), 50.0, maxFreq), 0f)
    }
}
