package app.siphondsp.service

/** Monotonic times only. A successful buffer may contain silence; amplitude is not health. */
internal object AudioPipelineHealth {
    const val WATCHDOG_INTERVAL_MS = 2_000L
    const val STARTUP_GRACE_MS = 10_000L
    const val FLOW_TIMEOUT_MS = 8_000L
    const val RECREATION_TIMEOUT_MS = 15_000L
    const val MAX_CONSECUTIVE_FAILURES = 3
    const val IO_RETRY_DELAY_MS = 20L
    const val RECOVERY_WINDOW_MS = 60_000L
    const val MAX_RECOVERIES_PER_WINDOW = 3

    enum class State { STARTING, HEALTHY, IDLE_EXPECTED, RECREATING, STALLED, FAILED, STOPPING }

    data class Snapshot(
        val now: Long,
        val workerAlive: Boolean,
        val stopping: Boolean,
        val recorderInitialized: Boolean,
        val recorderRecording: Boolean,
        val trackInitialized: Boolean,
        val trackPlaying: Boolean,
        val startedAt: Long,
        val flowExpectedSince: Long,
        val lastRead: Long = -1,
        val lastProcess: Long = -1,
        val lastWrite: Long = -1,
        val readFailures: Int = 0,
        val writeFailures: Int = 0,
        val idleExpected: Boolean = false,
        val suspended: Boolean = false,
        val generatorActive: Boolean = false,
        val recreationPending: Boolean = false,
        val recreationInProgress: Boolean = false,
        val recreationSince: Long = -1,
    )

    data class Result(val state: State, val reason: String)

    fun evaluate(s: Snapshot): Result {
        fun result(state: State, reason: String) = Result(state, reason)
        if (s.stopping) return result(State.STOPPING, "service/worker stopping")
        if (!s.workerAlive) return result(State.FAILED, "worker exited")
        if (s.recreationPending || s.recreationInProgress) {
            return if (s.now - s.recreationSince >= RECREATION_TIMEOUT_MS)
                result(State.FAILED, "recreation did not complete")
            else result(State.RECREATING, "recreation requested/in progress")
        }
        if (s.readFailures >= MAX_CONSECUTIVE_FAILURES)
            return result(State.STALLED, "repeated read failures")
        if (s.writeFailures >= MAX_CONSECUTIVE_FAILURES)
            return result(State.STALLED, "repeated write failures")

        val baseline = maxOf(s.startedAt, s.flowExpectedSince)
        val inGrace = s.now - baseline < STARTUP_GRACE_MS
        if (!s.recorderInitialized || !s.trackInitialized) {
            return if (inGrace) result(State.STARTING, "initializing audio objects")
            else result(State.STALLED, "audio object not initialized")
        }
        // No source is a legitimate wait even with suspend-on-idle disabled. Generator mode
        // overrides idle, because it must keep processing/writing without a capture source.
        // A worker still reporting suspended after a session resumes must eventually time out.
        if (s.idleExpected && !s.generatorActive)
            return result(State.IDLE_EXPECTED, "no capture session / intentionally suspended")

        val readReady = s.generatorActive || s.lastRead >= baseline
        val progressed = readReady && s.lastProcess >= baseline && s.lastWrite >= baseline
        val endpointsReady = (s.generatorActive || s.recorderRecording) && s.trackPlaying
        if (inGrace && (!progressed || !endpointsReady))
            return result(State.STARTING, "waiting for first complete audio flow")
        if (!endpointsReady) return result(State.STALLED, "recorder not recording or track not playing")
        if (!s.generatorActive && s.now - maxOf(baseline, s.lastRead) >= FLOW_TIMEOUT_MS)
            return result(State.STALLED, "read flow stale")
        if (s.now - maxOf(baseline, s.lastProcess) >= FLOW_TIMEOUT_MS)
            return result(State.STALLED, "DSP flow stale")
        if (s.now - maxOf(baseline, s.lastWrite) >= FLOW_TIMEOUT_MS)
            return result(State.STALLED, "write flow stale")
        return result(State.HEALTHY, "audio flow progressing")
    }
}
