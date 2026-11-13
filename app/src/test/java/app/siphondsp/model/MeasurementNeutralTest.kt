package app.siphondsp.model

import app.siphondsp.model.debug.EngineStatus
import app.siphondsp.model.debug.SelfTestReport
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MeasurementNeutralTest {

    private fun tuned() = NativeBmwDspValues.DEFAULTS.copyOf().also {
        it[NativeBmwDspValues.INDEX_TILT_ENABLED] = 1f
        it[NativeBmwDspValues.INDEX_LOW_GAIN_L] = -3f
        it[NativeBmwDspValues.INDEX_MID_DELAY_R] = 1f
        it[NativeBmwDspValues.INDEX_MBC_ENABLED] = 1f
        it[NativeBmwDspValues.INDEX_MASTER_LIMITER_ENABLED] = 1f
        it[NativeBmwDspValues.INDEX_HEADROOM] = -6f
        it[NativeBmwDspValues.INDEX_MASTER_VOLUME_DB] = -20f
    }

    @Test
    fun offLeavesTheConfigAsItIs() {
        val values = tuned()
        assertArrayEquals(values, MeasurementNeutral.effective(values), 0f)
        for (stage in DspStage.entries) assertTrue(MeasurementNeutral.runs(values, stage))
    }

    @Test
    fun onBypassesEveryNeutralStageButNeverTheVolume() {
        val values = tuned().also { it[NativeBmwDspValues.INDEX_MEASUREMENT_NEUTRAL] = 1f }
        val e = MeasurementNeutral.effective(values)
        assertEquals(1f, e[NativeBmwDspValues.INDEX_CROSSOVER_BYPASS], 0f)
        assertEquals(0f, e[NativeBmwDspValues.INDEX_TILT_ENABLED], 0f)
        assertEquals(0f, e[NativeBmwDspValues.INDEX_LOW_GAIN_L], 0f)
        assertEquals(0f, e[NativeBmwDspValues.INDEX_MID_DELAY_R], 0f)
        assertEquals(0f, e[NativeBmwDspValues.INDEX_MBC_ENABLED], 0f)
        assertEquals(0f, e[NativeBmwDspValues.INDEX_MASTER_LIMITER_ENABLED], 0f)
        assertEquals(0f, e[NativeBmwDspValues.INDEX_HEADROOM], 0f)
        assertEquals(-20f, e[NativeBmwDspValues.INDEX_MASTER_VOLUME_DB], 0f)
        assertFalse(MeasurementNeutral.runs(values, DspStage.PRE_EQ))
        assertFalse(MeasurementNeutral.runs(values, DspStage.DC_BLOCKER))
        assertTrue(MeasurementNeutral.crossoverBypassed(values))
        // The saved settings themselves are untouched.
        assertEquals(1f, values[NativeBmwDspValues.INDEX_TILT_ENABLED], 0f)
    }

    @Test
    fun anAllowedStageKeepsItsOwnSettings() {
        val values = tuned().also { it[NativeBmwDspValues.INDEX_MEASUREMENT_NEUTRAL] = 1f }
        for ((i, v) in MeasurementNeutral.allowUpdate(values, DspStage.TILT, true)) values[i] = v
        for ((i, v) in MeasurementNeutral.allowUpdate(values, DspStage.BAND_GAIN, true)) values[i] = v
        val e = MeasurementNeutral.effective(values)
        assertEquals(1f, e[NativeBmwDspValues.INDEX_TILT_ENABLED], 0f)
        assertEquals(-3f, e[NativeBmwDspValues.INDEX_LOW_GAIN_L], 0f)
        assertEquals(0f, e[NativeBmwDspValues.INDEX_MBC_ENABLED], 0f)
        for ((i, v) in MeasurementNeutral.allowUpdate(values, DspStage.TILT, false)) values[i] = v
        assertFalse(MeasurementNeutral.isAllowed(values, DspStage.TILT))
        assertTrue(MeasurementNeutral.isAllowed(values, DspStage.BAND_GAIN))
        // Switching neutral off (or on again) starts from nothing allowed.
        assertEquals(0f, MeasurementNeutral.updates(false).getValue(NativeBmwDspValues.INDEX_NEUTRAL_ALLOW), 0f)
        assertEquals(1f, MeasurementNeutral.updates(true).getValue(NativeBmwDspValues.INDEX_MEASUREMENT_NEUTRAL), 0f)
    }

    @Test
    fun stageBitsAreDistinctAndTheNeutralSetIsTheLowBits() {
        assertEquals(DspStage.entries.size, DspStage.entries.map { it.bit }.toSet().size)
        assertTrue(DspStage.NEUTRAL.all { it.bit < DspStage.NEUTRAL_COUNT })
        assertTrue(DspStage.entries.filter { !it.neutralised }.all { it.bit >= DspStage.NEUTRAL_COUNT })
    }

    @Test
    fun engineStatusParsesTheNativeLayout() {
        assertNull(EngineStatus.parse(null))
        assertNull(EngineStatus.parse(FloatArray(EngineStatus.SIZE)))  // [0] = 0: nothing installed
        val raw = floatArrayOf(
            1f, (DspStage.TILT.mask or DspStage.MASTER_LIMITER.mask).toFloat(), 1f, DspStage.TILT.mask.toFloat(),
            0f, 2f, -1f, 3f, 1f, -20f, 0f, -6f, 48000f, 7f,
        )
        val s = EngineStatus.parse(raw)!!
        assertEquals(setOf(DspStage.TILT, DspStage.MASTER_LIMITER), s.activeStages)
        assertTrue(s.measurementNeutral)
        assertEquals(setOf(DspStage.TILT), s.neutralAllowed)
        assertEquals(0, s.crossoverWays)
        assertEquals(3, s.mbcBands)
        assertEquals(-20f, s.masterVolumeDb, 0f)
        assertEquals(7L, s.revision)
    }

    @Test
    fun selfTestHeadlineSaysVerifiedOrNamesTheStages() {
        fun raw(verified: Boolean, altering: Int) = doubleArrayOf(
            1.0, if (verified) 1.0 else 0.0, 48000.0, 0.0, if (verified) 0.0 else 3.2, if (verified) 0.0 else 12.0,
            -400.0, 20.0, 20000.0, 0.0, altering.toDouble(), 0.0, 0.0, 1.0, -20.0, 850.0,
        )
        val ok = SelfTestReport.parse(raw(true, 0))!!
        assertEquals("Neutral verified: flat ±0.01 dB, 0°, 0 samples", ok.headline)
        val bad = SelfTestReport.parse(raw(false, DspStage.TILT.mask or DspStage.HEADROOM.mask))!!
        assertEquals("Neutral: Tilt, Headroom still altering the signal", bad.headline)
        assertTrue(bad.detail.contains("Master volume -20.0 dB excluded"))
        assertNull(SelfTestReport.parse(doubleArrayOf(0.0)))
    }
}
