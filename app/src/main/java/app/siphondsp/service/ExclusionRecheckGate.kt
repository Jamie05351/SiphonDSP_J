package app.siphondsp.service

/**
 * Debounces requests to re-check the capture exclusions. App sessions starting and stopping
 * arrive in bursts (one app can open and close several in a second), and each used to rebuild
 * the whole capture pipeline, with underruns every time. A burst now runs [check] once: [debounceMs]
 * after the latest request, but never more than [maxWaitMs] after the first, so continuous churn
 * can't postpone it forever. The reasons and packages from every request in the burst are passed
 * along.
 *
 * Not thread-safe: [request] and [cancel] run on the thread [schedule] runs its tasks on (the
 * service's main-thread handler). [now] and [schedule] are injected so the timing can be
 * unit-tested without a Looper.
 */
class ExclusionRecheckGate(
    private val debounceMs: Long,
    private val maxWaitMs: Long,
    private val now: () -> Long,
    private val schedule: (delayMs: Long, task: Runnable) -> Unit,
    private val check: (reasons: Set<String>, packages: Set<String>) -> Unit,
) {
    private var burstStartedAt = -1L
    private var generation = 0
    private val reasons = linkedSetOf<String>()
    private val packages = linkedSetOf<String>()

    fun request(reason: String, triggeringPackages: Collection<String> = emptyList()) {
        val t = now()
        if (burstStartedAt < 0) burstStartedAt = t
        reasons += reason
        packages += triggeringPackages
        val due = minOf(t + debounceMs, burstStartedAt + maxWaitMs)
        // Each request schedules its own task; only the latest one's generation still runs.
        val mine = ++generation
        schedule(maxOf(0L, due - t), Runnable { if (mine == generation) fire() })
    }

    /** Drops a pending burst without checking (service teardown). */
    fun cancel() {
        generation++
        reset()
    }

    private fun fire() {
        val firedReasons = reasons.toSet()
        val firedPackages = packages.toSet()
        reset()
        check(firedReasons, firedPackages)
    }

    private fun reset() {
        burstStartedAt = -1L
        reasons.clear()
        packages.clear()
    }
}
