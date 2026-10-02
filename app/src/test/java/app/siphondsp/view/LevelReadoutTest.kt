package app.siphondsp.view

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LevelReadoutTest {

    @Test
    fun silenceHasNoHeadroom() {
        assertTrue(LevelReadout.SILENT.silent)
        assertNull(LevelReadout.SILENT.headroomDb(0f))
    }

    @Test
    fun headroomIsMeasuredFromTheLouderPeak() {
        val readout = LevelReadout(leftRmsDb = -14f, leftPeakDb = -4f, rightRmsDb = -12f, rightPeakDb = -2.5f)
        assertEquals(2.5f, readout.headroomDb(0f)!!, 1e-4f)
        // With the limiter on at -1 dB, the peak is 1.5 dB under it.
        assertEquals(1.5f, readout.headroomDb(-1f)!!, 1e-4f)
    }

    @Test
    fun headroomNeverGoesNegative() {
        val readout = LevelReadout(-6f, 0f, -6f, -0.5f)
        assertEquals(0f, readout.headroomDb(-1f)!!, 0f)
    }
}
