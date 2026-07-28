package app.siphondsp.view

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PeakHoldMeterTest {
    @Test
    fun peakAndRmsTrackTheLatestInstantValues() {
        val meter = PeakHoldMeter(floorDb = -80f)

        meter.update(instantPeakDb = -10f, instantRmsDb = -20f, nowMs = 0L)

        assertEquals(-10f, meter.peakDb, 0f)
        assertEquals(-20f, meter.rmsDb, 0f)
    }

    @Test
    fun holdTracksTheHighestPeakSeen() {
        val meter = PeakHoldMeter(holdMs = 1000L, floorDb = -80f)

        meter.update(instantPeakDb = -30f, instantRmsDb = -30f, nowMs = 0L)
        meter.update(instantPeakDb = -10f, instantRmsDb = -15f, nowMs = 10L)
        meter.update(instantPeakDb = -25f, instantRmsDb = -25f, nowMs = 20L)

        // Hold must stay at the highest peak (-10dB) even though the instant peak dropped back.
        assertEquals(-10f, meter.holdDb, 0f)
        assertEquals(-25f, meter.peakDb, 0f)
    }

    @Test
    fun holdDoesNotDecayBeforeHoldDurationElapses() {
        val meter = PeakHoldMeter(holdMs = 1000L, decayDbPerSecond = 24f, floorDb = -80f)

        meter.update(instantPeakDb = -10f, instantRmsDb = -10f, nowMs = 0L)
        meter.update(instantPeakDb = -40f, instantRmsDb = -40f, nowMs = 500L)

        assertEquals(-10f, meter.holdDb, 0f)
    }

    @Test
    fun holdDecaysAtConfiguredRateAfterHoldDurationElapses() {
        val meter = PeakHoldMeter(holdMs = 1000L, decayDbPerSecond = 24f, floorDb = -80f)

        meter.update(instantPeakDb = -10f, instantRmsDb = -10f, nowMs = 0L)
        // 1000ms hold + 500ms of decay at 24dB/s => expect ~12dB of decay from -10 to -22.
        meter.update(instantPeakDb = -60f, instantRmsDb = -60f, nowMs = 1500L)

        assertEquals(-22f, meter.holdDb, 0.5f)
    }

    @Test
    fun holdNeverDecaysBelowTheCurrentInstantPeak() {
        val meter = PeakHoldMeter(holdMs = 1000L, decayDbPerSecond = 24f, floorDb = -80f)

        meter.update(instantPeakDb = -10f, instantRmsDb = -10f, nowMs = 0L)
        // A huge elapsed time would decay well past -20, but the instant peak (-20) is a floor.
        meter.update(instantPeakDb = -20f, instantRmsDb = -20f, nowMs = 100_000L)

        assertTrue(meter.holdDb >= -20f)
    }

    @Test
    fun resetReturnsAllValuesToFloor() {
        val meter = PeakHoldMeter(floorDb = -80f)
        meter.update(instantPeakDb = -5f, instantRmsDb = -8f, nowMs = 0L)

        meter.reset()

        assertEquals(-80f, meter.peakDb, 0f)
        assertEquals(-80f, meter.rmsDb, 0f)
        assertEquals(-80f, meter.holdDb, 0f)
    }

    @Test
    fun fractionForMapsFloorAndCeilingToZeroAndOne() {
        assertEquals(0f, PeakHoldMeter.fractionFor(-50f, floorDb = -50f, ceilingDb = 0f), 1e-6f)
        assertEquals(1f, PeakHoldMeter.fractionFor(0f, floorDb = -50f, ceilingDb = 0f), 1e-6f)
        assertEquals(0.5f, PeakHoldMeter.fractionFor(-25f, floorDb = -50f, ceilingDb = 0f), 1e-6f)
    }

    @Test
    fun fractionForClampsOutOfRangeInput() {
        assertEquals(0f, PeakHoldMeter.fractionFor(-100f, floorDb = -50f, ceilingDb = 0f), 1e-6f)
        assertEquals(1f, PeakHoldMeter.fractionFor(10f, floorDb = -50f, ceilingDb = 0f), 1e-6f)
    }
}
