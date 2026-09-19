package app.siphondsp.service

import app.siphondsp.model.debug.RootlessPipelineRuntimeSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DspHealthBadgeTest {
    private fun snapshot(state: String?, reason: String? = "because") = RootlessPipelineRuntimeSnapshot(
        recorderStateInitialized = true,
        recorderRecording = true,
        trackStateInitialized = true,
        trackPlaying = true,
        recreateRequested = false,
        recreationInProgress = false,
        measurementGeneratorActive = false,
        processorDisposing = false,
        serviceDisposing = false,
        pipelineHealthState = state,
        pipelineHealthReason = reason,
    )

    @Test
    fun engineNotRunningIsAWarningNotSilence() {
        val badge = DspHealthBadge.evaluate(null)
        assertEquals(DspHealthBadge.Level.WARN, badge.level)
        assertEquals("DSP off", badge.label)
    }

    @Test
    fun healthyIsOk() {
        assertEquals(DspHealthBadge.Level.OK, DspHealthBadge.evaluate(snapshot("HEALTHY")).level)
    }

    @Test
    fun idleIsNeutral() {
        val badge = DspHealthBadge.evaluate(snapshot("IDLE_EXPECTED", "no capture session"))
        assertEquals(DspHealthBadge.Level.IDLE, badge.level)
        assertTrue(badge.detail.contains("no capture session"))
    }

    @Test
    fun startingAndRecoveringAreWarnings() {
        assertEquals(DspHealthBadge.Level.WARN, DspHealthBadge.evaluate(snapshot("STARTING")).level)
        assertEquals(DspHealthBadge.Level.WARN, DspHealthBadge.evaluate(snapshot("RECREATING")).level)
        assertEquals(DspHealthBadge.Level.WARN, DspHealthBadge.evaluate(snapshot(null)).level)
    }

    @Test
    fun stalledAndFailedAreNoAudioWithTheReason() {
        for (state in listOf("STALLED", "FAILED")) {
            val badge = DspHealthBadge.evaluate(snapshot(state, "read flow stale"))
            assertEquals(DspHealthBadge.Level.BAD, badge.level)
            assertEquals("NO AUDIO", badge.label)
            assertTrue(badge.detail.contains("read flow stale"))
        }
    }
}
