package app.siphondsp.service

import app.siphondsp.service.AudioPipelineHealth.State
import org.junit.Assert.assertEquals
import org.junit.Test

class AudioPipelineHealthTest {
    private fun flowing() = AudioPipelineHealth.Snapshot(
        now = 20_000, workerAlive = true, stopping = false,
        recorderInitialized = true, recorderRecording = true,
        trackInitialized = true, trackPlaying = true,
        startedAt = 0, flowExpectedSince = 0,
        lastRead = 19_900, lastProcess = 19_901, lastWrite = 19_902,
    )

    private fun assertState(state: State, snapshot: AudioPipelineHealth.Snapshot) {
        assertEquals(state, AudioPipelineHealth.evaluate(snapshot).state)
    }

    @Test fun startupWaitsForObjectsAndFirstCompleteBuffer() {
        val startup = flowing().copy(now = 100, lastRead = -1, lastProcess = -1, lastWrite = -1)
        assertState(State.STARTING, startup.copy(recorderInitialized = false, trackInitialized = false))
        assertState(State.STARTING, startup)
        assertState(State.STARTING, startup.copy(lastRead = 90, lastProcess = 91))
        assertState(State.HEALTHY, startup.copy(lastRead = 90, lastProcess = 91, lastWrite = 92))
    }

    @Test fun livingWorkerIsNotEvidenceOfFlow() {
        assertState(State.STALLED, flowing().copy(lastRead = 1_000))
        assertState(State.STALLED, flowing().copy(lastProcess = 1_000))
        assertState(State.STALLED, flowing().copy(lastWrite = 1_000))
        assertState(State.STALLED, flowing().copy(lastRead = -1, lastProcess = -1, lastWrite = -1))
    }

    @Test fun startupGraceHasABoundary() {
        val startup = flowing().copy(lastRead = -1, lastProcess = -1, lastWrite = -1)
        assertState(State.STARTING, startup.copy(now = 9_999))
        assertState(State.STALLED, startup.copy(now = 10_000))
    }

    @Test fun silenceDoesNotRequireANonzeroAmplitude() {
        // Timestamps record usable samples, including zeros. There is no amplitude gate.
        assertState(State.HEALTHY, flowing())
    }

    @Test fun missingCaptureWhileIdleDoesNotTriggerRecovery() {
        assertState(State.IDLE_EXPECTED, flowing().copy(
            now = 1_000_000, idleExpected = true, lastRead = -1, lastProcess = -1, lastWrite = -1,
        ))
        assertState(State.IDLE_EXPECTED, flowing().copy(
            now = 1_000_000, idleExpected = true, suspended = true,
            recorderRecording = false, trackPlaying = false,
        ))
    }

    @Test fun resumeGetsGraceButCannotStayStartingForever() {
        val resumed = flowing().copy(now = 100_000, flowExpectedSince = 100_000)
        assertState(State.STARTING, resumed)
        assertState(State.STARTING, resumed.copy(suspended = true))
        assertState(State.STALLED, resumed.copy(now = 110_000))
        assertState(State.STALLED, resumed.copy(now = 110_000, suspended = true))
        assertState(State.HEALTHY, resumed.copy(now = 100_100,
            lastRead = 100_010, lastProcess = 100_020, lastWrite = 100_030))
    }

    @Test fun recreationIsNotAnotherRecoveryRequest() {
        val pending = flowing().copy(recreationPending = true, recreationSince = 19_000)
        assertState(State.RECREATING, pending)
        assertState(State.RECREATING, pending.copy(recreationPending = false, recreationInProgress = true))
        assertState(State.FAILED, pending.copy(now = 34_000))
        assertState(State.FAILED, pending.copy(now = 34_000, idleExpected = true))
    }

    @Test fun generatorRequiresProcessingAndOutputButNotCapture() {
        val generator = flowing().copy(generatorActive = true, idleExpected = true,
            recorderRecording = false, lastRead = -1)
        assertState(State.HEALTHY, generator)
        assertState(State.STALLED, generator.copy(lastProcess = 0))
        assertState(State.STALLED, generator.copy(lastWrite = 0))
        assertState(State.STALLED, generator.copy(trackPlaying = false))
    }

    @Test fun negativeReadsTriggerRecoveryEvenDuringIdle() {
        assertState(State.HEALTHY, flowing().copy(readFailures = 2))
        assertState(State.STALLED, flowing().copy(readFailures = 3))
        assertState(State.STALLED, flowing().copy(readFailures = 3, idleExpected = true))
    }

    @Test fun repeatedWritesWithoutProgressTriggerRecovery() {
        assertState(State.HEALTHY, flowing().copy(writeFailures = 2))
        assertState(State.STALLED, flowing().copy(writeFailures = 3))
    }

    @Test fun stoppedOrUninitializedObjectsAreUnhealthyOutsideGrace() {
        assertState(State.STALLED, flowing().copy(recorderInitialized = false))
        assertState(State.STALLED, flowing().copy(trackInitialized = false))
        assertState(State.STALLED, flowing().copy(recorderRecording = false))
        assertState(State.STALLED, flowing().copy(trackPlaying = false))
    }

    @Test fun recoveryRequiresFreshFlowFromReplacementObjects() {
        val recovered = flowing().copy(startedAt = 20_000)
        assertState(State.STARTING, recovered)
        assertState(State.STALLED, recovered.copy(now = 30_000))
        assertState(State.HEALTHY, recovered.copy(now = 20_100,
            lastRead = 20_010, lastProcess = 20_020, lastWrite = 20_030))
    }

    @Test fun workerExitIsDetectedEvenWhileIdle() {
        assertState(State.FAILED, flowing().copy(workerAlive = false))
        assertState(State.FAILED, flowing().copy(workerAlive = false, idleExpected = true))
    }

    @Test fun disposalAlwaysWinsOverRecoveryAndIdle() {
        assertState(State.STOPPING, flowing().copy(stopping = true, writeFailures = 3))
        assertState(State.STOPPING, flowing().copy(stopping = true, workerAlive = false))
        assertState(State.STOPPING, flowing().copy(stopping = true, recreationPending = true, recreationSince = 0))
        assertState(State.STOPPING, flowing().copy(stopping = true, idleExpected = true))
    }
}
