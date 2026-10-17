package app.siphondsp.service

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Hands the latest submitted value to [apply] at most once per [minIntervalMs], on whatever
 * thread [schedule] runs its runnables on. Values submitted in between are dropped in favour of
 * the newest one, and the newest value is always applied eventually (trailing edge), so the last
 * position of a slider drag is never lost.
 *
 * Exists for the native DSP config: a slider drag broadcasts the whole config on every touch
 * event, and applying each one meant a JNI call, a full native re-parse and a wait on the lock
 * the audio thread holds for a whole buffer -- all on the main thread. Coalescing on a
 * background thread keeps the main thread free and caps the native rebuild rate.
 *
 * [submit] is safe from any thread. [now] and [schedule] are injected so the timing can be
 * unit-tested without a Looper.
 */
class CoalescingPusher<T : Any>(
    private val minIntervalMs: Long,
    private val now: () -> Long,
    private val schedule: (delayMs: Long, task: Runnable) -> Unit,
    private val apply: (T) -> Unit,
) {
    private val pending = AtomicReference<T?>(null)
    private val scheduled = AtomicBoolean(false)

    // Only read/written on the schedule thread.
    private var lastApplyAt = Long.MIN_VALUE / 2

    fun submit(value: T) {
        pending.set(value)
        if (scheduled.compareAndSet(false, true)) {
            schedule(0L, drain)
        }
    }

    private val drain = Runnable {
        val wait = lastApplyAt + minIntervalMs - now()
        if (wait > 0) {
            schedule(wait, applyLatest)
        } else {
            applyLatest.run()
        }
    }

    private val applyLatest = Runnable {
        // Clear the flag before taking the value: a submit() landing in between then schedules
        // a fresh drain instead of being left behind with nothing scheduled to apply it.
        scheduled.set(false)
        val value = pending.getAndSet(null) ?: return@Runnable
        lastApplyAt = now()
        apply(value)
    }

    companion object {
        /** ~30 Hz: smooth enough to follow a drag by ear, far below the touch-event rate. */
        const val DSP_CONFIG_INTERVAL_MS = 33L
    }
}
