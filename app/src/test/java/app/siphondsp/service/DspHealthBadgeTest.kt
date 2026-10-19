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

    @Test
    fun anExcludedAppPlayingWarnsEvenWhenThePipelineLooksIdle() {
        // The case that read as "nothing playing": the only player is excluded, so there is no
        // session to process and the pipeline itself is idle.
        val badge = DspHealthBadge.evaluate(
            snapshot("IDLE_EXPECTED", "no capture session").copy(
                excludedPlaying = listOf("com.spotify.music"), excludedApps = listOf("com.spotify.music"),
            ),
        )
        assertEquals(DspHealthBadge.Level.WARN, badge.level)
        assertEquals("Excluded app", badge.label)
        assertTrue(badge.detail.contains("com.spotify.music"))
        assertTrue(badge.detail.contains("Excluded apps"))
    }

    @Test
    fun anExcludedAppThatIsNotPlayingChangesNothing() {
        val badge = DspHealthBadge.evaluate(snapshot("HEALTHY").copy(excludedApps = listOf("com.example.player")))
        assertEquals(DspHealthBadge.Level.OK, badge.level)
    }

    @Test
    fun aRealFaultOrProcessedAudioKeepsItsOwnBadgeWithTheExclusionNoted() {
        val stalled = DspHealthBadge.evaluate(
            snapshot("STALLED").copy(excludedPlaying = listOf("com.spotify.music"), playbackSessions = 1),
        )
        assertEquals(DspHealthBadge.Level.BAD, stalled.level)
        assertTrue(stalled.detail.contains("com.spotify.music"))
        val processing = DspHealthBadge.evaluate(
            snapshot("HEALTHY").copy(
                excludedPlaying = listOf("com.spotify.music"), excludedApps = listOf("com.spotify.music"),
                playbackSessions = 1,
            ),
        )
        assertEquals("DSP ok", processing.label)
        assertTrue(processing.detail.contains("Excluded apps"))
    }

    @Test
    fun anAutoExcludedAppIsExplainedNotSentToTheList() {
        val badge = DspHealthBadge.evaluate(
            snapshot("IDLE_EXPECTED").copy(excludedPlaying = listOf("com.example.drm")),
        )
        assertEquals("Excluded app", badge.label)
        assertTrue(badge.detail.contains("blocks audio capture"))
        assertTrue(!badge.detail.contains("Remove it from"))
    }
}
