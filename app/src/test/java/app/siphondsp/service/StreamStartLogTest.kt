package app.siphondsp.service

import app.siphondsp.model.DspStage
import app.siphondsp.model.debug.EngineStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.FloatBuffer
import java.nio.ShortBuffer

class StreamStartLogTest {

    private fun status(
        volumeDb: Float,
        headroomDb: Float,
        convolver: Boolean = false,
        preEq: Boolean = true,
        muted: Boolean = false,
    ) = EngineStatus(
        activeStages = setOfNotNull(DspStage.CONVOLVER.takeIf { convolver }, DspStage.PRE_EQ.takeIf { preEq }),
        measurementNeutral = false,
        neutralAllowed = emptySet(),
        crossoverWays = 2,
        tiltDb = 0f,
        limiterThresholdDb = -1f,
        mbcBands = 0,
        allPassOutputs = 0,
        masterVolumeDb = volumeDb,
        masterMute = muted,
        headroomDb = headroomDb,
        sampleRate = 48_000f,
        revision = 1,
    )

    /** One start of [n] buffers at input [inDb] and output [outDb], engine at -25 / -6 / -2. */
    private fun recordStart(reason: String, n: Int, inDb: Float, outDb: (Int) -> Float, processed: (Int) -> Boolean = { true }) {
        StreamStartLog.begin(reason, 48_000, 7680, true, nowMs = 1_000)
        for (i in 0 until n) {
            assertTrue(StreamStartLog.recording)
            StreamStartLog.record(1_000L + 80L * i, 7680, inDb, outDb(i), processed(i), false)
            if (StreamStartLog.wantsEngine) StreamStartLog.recordEngine(status(-25f, -6f, convolver = true), true, -2f)
        }
    }

    @Test
    fun recordsTheFirstBuffersOfAStartThenStops() {
        recordStart("service start", StreamStartLog.BUFFERS, -10f, { -43f })
        assertFalse(StreamStartLog.recording)
        StreamStartLog.record(5_000, 7680, -10f, 0f, true, false)  // after the window: ignored
        val start = StreamStartLog.snapshot().first()
        assertEquals("service start", start.reason)
        assertEquals(StreamStartLog.BUFFERS, start.buffers.size)
        assertEquals(80, start.buffers[1].tMs)
        assertTrue(start.engineRead)
        assertTrue(start.convolverRunning)
        assertEquals(-33f, start.expectedGainDb, 1e-6f)  // -25 - 6 - 2
        assertTrue(start.buffers.none(start::suspicious))
    }

    @Test
    fun flagsABufferLouderThanTheSettingsAllowAndARawOne() {
        // Buffer 0 comes out at the input's level: 33 dB more than the engine's gains allow.
        recordStart("resume after idle", 3, -10f, { if (it == 0) -10f else -43f })
        var start = StreamStartLog.snapshot().first()
        assertTrue(start.suspicious(start.buffers[0]))
        assertFalse(start.suspicious(start.buffers[1]))
        // Passed through unprocessed with the volume cutting: flagged whatever its level.
        recordStart("track restart", 2, -10f, { -10f }, processed = { it == 1 })
        start = StreamStartLog.snapshot().first()
        assertTrue(start.suspicious(start.buffers[0]))
        assertEquals("track restart", start.reason)
    }

    @Test
    fun keepsTheLastFewStartsNewestFirst() {
        repeat(StreamStartLog.STARTS + 2) { recordStart("start $it", 1, -20f, { -50f }) }
        val starts = StreamStartLog.snapshot()
        assertEquals(StreamStartLog.STARTS, starts.size)
        assertEquals("start ${StreamStartLog.STARTS + 1}", starts.first().reason)
    }

    @Test
    fun peaksInDbfs() {
        assertEquals(-120f, StreamStartLog.peakDb(FloatBuffer.wrap(FloatArray(8)), 8), 0f)
        assertEquals(0f, StreamStartLog.peakDb(FloatBuffer.wrap(floatArrayOf(0.1f, -1f, 0.5f)), 3), 1e-5f)
        assertEquals(-6.02f, StreamStartLog.peakDb(FloatBuffer.wrap(floatArrayOf(0.5f, 0f)), 2), 0.01f)
        assertEquals(-6.02f, StreamStartLog.peakDb(ShortBuffer.wrap(shortArrayOf(16384, -100)), 2), 0.01f)
        // Only the first [count] samples count.
        assertEquals(-120f, StreamStartLog.peakDb(FloatBuffer.wrap(floatArrayOf(0f, 1f)), 1), 0f)
    }

    @Test
    fun flagsAudibleOutputWhileMutedAndNonFiniteOutput() {
        StreamStartLog.begin("muted", 48_000, 7680, true, nowMs = 0)
        StreamStartLog.record(0, 7680, -10f, -60f, true, false)
        StreamStartLog.recordEngine(status(-25f, -6f, muted = true), true, -2f)
        StreamStartLog.record(80, 7680, -10f, -120f, true, false)
        var start = StreamStartLog.snapshot().first()
        assertTrue(start.suspicious(start.buffers[0]))   // audible while muted
        assertFalse(start.suspicious(start.buffers[1]))  // silent while muted
        StreamStartLog.begin("nan", 48_000, 7680, true, nowMs = 0)
        StreamStartLog.record(0, 7680, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, true, false)
        start = StreamStartLog.snapshot().first()
        assertTrue(start.suspicious(start.buffers[0]))
    }

    @Test
    fun aBypassedPreEqDoesNotCountItsPreamp() {
        StreamStartLog.begin("neutral", 48_000, 7680, true, nowMs = 0)
        StreamStartLog.record(0, 7680, -10f, -41f, true, false)
        StreamStartLog.recordEngine(status(-25f, -6f, preEq = false), true, -20f)
        val start = StreamStartLog.snapshot().first()
        assertEquals(-31f, start.expectedGainDb, 1e-6f)  // the -20 dB preamp isn't running
        assertFalse(start.suspicious(start.buffers[0]))
    }

    @Test
    fun anUnreadableStatusStaysUnknownAndIsRetried() {
        // A slot that held a muted start is reused: nothing of it may show.
        repeat(StreamStartLog.STARTS) {
            StreamStartLog.begin("old", 48_000, 7680, true, nowMs = 0)
            StreamStartLog.record(0, 7680, -10f, -60f, true, false)
            StreamStartLog.recordEngine(status(-40f, -6f, muted = true), true, -2f)
        }
        StreamStartLog.begin("new", 48_000, 7680, true, nowMs = 0)
        StreamStartLog.record(0, 7680, -10f, -45f, true, false)
        StreamStartLog.recordEngine(null, true, -2f)
        var start = StreamStartLog.snapshot().first()
        assertFalse(start.engineRead)
        assertFalse(start.muted)
        assertEquals(0f, start.volumeDb, 0f)
        assertTrue(StreamStartLog.wantsEngine)  // tried again after the next buffer
        StreamStartLog.record(80, 7680, -10f, -45f, true, false)
        StreamStartLog.recordEngine(status(-25f, -6f), true, -2f)
        start = StreamStartLog.snapshot().first()
        assertTrue(start.engineRead)
        assertEquals(-25f, start.volumeDb, 0f)
    }
}
