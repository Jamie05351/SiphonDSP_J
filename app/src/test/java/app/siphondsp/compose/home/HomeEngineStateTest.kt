package app.siphondsp.compose.home

import app.siphondsp.model.DspStage
import app.siphondsp.model.NativeBmwDspValues
import app.siphondsp.model.debug.EngineStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeEngineStateTest {

    private fun values() = FloatArray(NativeBmwDspValues.SIZE)

    @Test
    fun allOffReadsAsOff() {
        // Every stage off; the master volume is a level, not a stage, and reads as stored (0 dB).
        assertEquals(HomeEngineState.OFF.copy(masterVolumeDb = 0f), HomeEngineState.from(values()))
    }

    @Test
    fun masterVolumeAndMuteReadThrough() {
        val v = values()
        v[NativeBmwDspValues.INDEX_MASTER_VOLUME_DB] = -24f
        v[NativeBmwDspValues.INDEX_MASTER_MUTE] = 1f
        val state = HomeEngineState.from(v)
        assertEquals(-24f, state.masterVolumeDb, 0f)
        assertTrue(state.masterMuted)
    }

    @Test
    fun aShortArrayReadsAsOff() {
        assertEquals(HomeEngineState.OFF, HomeEngineState.from(FloatArray(10)))
    }

    @Test
    fun allPassIsOnWithAnySectionOnAnyOutput() {
        val v = values()
        assertFalse(HomeEngineState.from(v).allPassOn)
        v[NativeBmwDspValues.highAllPassIndex(NativeBmwDspValues.OUTPUT_HIGH_LEFT, 1, 0)] = 1f
        v[NativeBmwDspValues.highAllPassIndex(NativeBmwDspValues.OUTPUT_HIGH_LEFT, 0, 0)] = 1f
        assertTrue(HomeEngineState.from(v).allPassOn)
        assertEquals(1, HomeEngineState.from(v).allPassOutputs)
        v[NativeBmwDspValues.highAllPassIndex(NativeBmwDspValues.OUTPUT_HIGH_RIGHT, 0, 0)] = 1f
        assertEquals(2, HomeEngineState.from(v).allPassOutputs)
    }

    @Test
    fun chipsCarryTheirSettingsOnlyWhileOn() {
        val v = values()
        v[NativeBmwDspValues.INDEX_TILT_AMOUNT] = 1.5f
        v[NativeBmwDspValues.INDEX_MASTER_LIMITER_THRESHOLD] = -1f
        v[NativeBmwDspValues.mbcBandIndex(0, NativeBmwDspValues.MBC_FIELD_ENABLED)] = 1f
        v[NativeBmwDspValues.mbcBandIndex(2, NativeBmwDspValues.MBC_FIELD_ENABLED)] = 1f
        val off = HomeEngineState.from(v)
        assertNull(off.tiltDb)
        assertNull(off.limiterDb)
        assertEquals(0, off.mbcBands)
        assertEquals(0f, off.ceilingDb)

        v[NativeBmwDspValues.INDEX_TILT_ENABLED] = 1f
        v[NativeBmwDspValues.INDEX_MASTER_LIMITER_ENABLED] = 1f
        v[NativeBmwDspValues.INDEX_MBC_ENABLED] = 1f
        val on = HomeEngineState.from(v)
        assertEquals(2.25f, on.tiltDb)  // the real end gain: 1.5 x the stored 1.5
        assertEquals(-1f, on.limiterDb)
        assertEquals(2, on.mbcBands)
        assertEquals(-1f, on.ceilingDb)
    }

    @Test
    fun formatDbUsesARealMinusAndAnOptionalPlus() {
        assertEquals("−1.0", formatDb(-1f))
        assertEquals("+1.5", formatDb(1.5f, signed = true))
        assertEquals("0.0", formatDb(0f, signed = true))
    }

    @Test
    fun cellsComeFromTheEngineNotTheSettings() {
        // Settings say tilt on; the engine says it isn't running (e.g. measurement neutral).
        val v = values()
        v[NativeBmwDspValues.INDEX_TILT_ENABLED] = 1f
        v[NativeBmwDspValues.INDEX_MASTER_VOLUME_DB] = -12f
        val status = EngineStatus.parse(
            floatArrayOf(1f, DspStage.MASTER_LIMITER.mask.toFloat(), 1f, 0f, 0f, 2f, -1.5f, 0f, 0f, -12f, 0f, 0f, 48000f, 1f),
        )
        val state = HomeEngineState.from(status, v)
        assertTrue(state.known)
        assertNull(state.tiltDb)
        assertEquals(-1.5f, state.limiterDb!!, 0f)
        assertEquals(0, state.crossoverWays)
        assertTrue(state.neutral)
        assertEquals(-12f, state.masterVolumeDb, 0f)
    }

    @Test
    fun noEngineReadsAsUnknown() {
        val v = values()
        v[NativeBmwDspValues.INDEX_TILT_ENABLED] = 1f
        val state = HomeEngineState.from(null, v)
        assertFalse(state.known)
        assertNull(state.tiltDb)
    }
}
