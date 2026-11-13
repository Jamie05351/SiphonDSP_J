package app.siphondsp.service

import android.content.Context
import android.os.Build
import android.os.PerformanceHintManager
import android.os.Process
import androidx.annotation.RequiresApi
import app.siphondsp.interop.NativeDsp
import timber.log.Timber

/**
 * Keeps the audio worker thread fast enough for its deadline:
 * - **Fast cores.** The thread is pinned to the device's fastest CPU cluster
 *   ([NativeDsp.pinCurrentThreadToFastCores]), so it never lands on a slow core the governor holds
 *   at a low clock. With no faster cluster, the fast cores outside what the platform allows the
 *   app, or the kernel refusing, its affinity stays as it was (automatic fallback).
 * - **Performance hint** (Android 12+, where the device supports it). A [PerformanceHintManager]
 *   session for the thread with a target of [TARGET_SHARE] of the buffer's real-time budget, told
 *   each buffer's actual engine time, so the CPU governor raises clocks before the deadline is
 *   close rather than after it is missed.
 *
 * Created and used by the worker thread only; [Status] is what Native DSP Truth shows.
 */
internal class AudioThreadTuning private constructor(private val hint: Hint?) : AutoCloseable {

    /** The worker: one buffer of engine work took [nanos]. */
    fun reportWork(nanos: Long) {
        hint?.report(nanos)
    }

    override fun close() {
        hint?.close()
        status = status.copy(hint = if (hint != null) "closed" else status.hint)
    }

    data class Status(
        val tid: Int = 0,
        /** One of the PIN_* results. */
        val pinResult: Int = -1,
        val allowedCpus: String = "",
        val pinnedCpus: String = "",
        val fastCpus: String = "",
        val fastMaxMhz: Long = 0,
        /** "active", "unsupported: ...", "failed: ...", "closed". */
        val hint: String = "not started",
        val hintTargetMs: Double = 0.0,
    ) {
        val pinSummary: String
            get() = when (pinResult) {
                PIN_PINNED -> "pinned to cpu $pinnedCpus (fast cluster, $fastMaxMhz MHz max)"
                PIN_NO_FASTER_CLUSTER -> "all cores equal: left on cpu $allowedCpus"
                PIN_FAST_NOT_ALLOWED -> "fast cores ($fastCpus) not allowed for the app: left on cpu $allowedCpus"
                PIN_REFUSED -> "kernel refused: left on cpu $allowedCpus"
                PIN_UNREADABLE -> "CPU clusters unreadable: left as it was"
                else -> "not started"
            }
    }

    /** Only ever created on Android 12+ (see [createHint]). */
    private class Hint(private val session: PerformanceHintManager.Session) {
        fun report(nanos: Long) {
            if (nanos <= 0 || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
            try {
                session.reportActualWorkDuration(nanos)
            } catch (e: RuntimeException) {
                // A session the system has dropped: stop reporting rather than throw on every buffer.
                Timber.w(e, "Performance hint report failed")
            }
        }

        fun close() {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
            try {
                session.close()
            } catch (_: RuntimeException) {
            }
        }
    }

    companion object {
        const val PIN_PINNED = 0
        const val PIN_NO_FASTER_CLUSTER = 1
        const val PIN_FAST_NOT_ALLOWED = 2
        const val PIN_REFUSED = 3
        const val PIN_UNREADABLE = 4

        /** The hint session's target: this share of the buffer's real-time budget. */
        const val TARGET_SHARE = 0.5

        /** The latest worker's placement and hint state, for Native DSP Truth. */
        @Volatile
        var status = Status()
            private set

        /**
         * Call on the worker thread itself, before its first buffer: pins it and opens the hint
         * session for buffers of [bufferFrames] frames at [sampleRate].
         */
        fun start(context: Context, bufferFrames: Int, sampleRate: Int): AudioThreadTuning {
            val tid = Process.myTid()
            var next = Status(tid = tid)
            val pin = try {
                NativeDsp.pinCurrentThreadToFastCores()
            } catch (e: UnsatisfiedLinkError) {
                Timber.w(e, "Fast-core pinning unavailable")
                null
            }
            if (pin != null && pin.size >= 5) {
                next = next.copy(
                    pinResult = pin[0].toInt(),
                    allowedCpus = cpuList(pin[1]),
                    pinnedCpus = cpuList(pin[2]),
                    fastCpus = cpuList(pin[3]),
                    fastMaxMhz = pin[4] / 1000,
                )
            }
            val targetNanos = (bufferFrames.toLong() * 1_000_000_000L / sampleRate.coerceAtLeast(1) * TARGET_SHARE).toLong()
            var hint: Hint? = null
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                next = next.copy(hint = "unsupported: Android ${Build.VERSION.RELEASE} (needs 12)")
            } else if (targetNanos <= 0) {
                next = next.copy(hint = "failed: no buffer budget")
            } else {
                val (created, state) = createHint(context, tid, targetNanos)
                hint = created
                next = next.copy(hint = state, hintTargetMs = targetNanos / 1e6)
            }
            Timber.i("Audio thread tuning: tid=%d %s; hint %s", tid, next.pinSummary, next.hint)
            status = next
            return AudioThreadTuning(hint)
        }

        @RequiresApi(Build.VERSION_CODES.S)
        private fun createHint(context: Context, tid: Int, targetNanos: Long): Pair<Hint?, String> = try {
            val manager = context.getSystemService(PerformanceHintManager::class.java)
            when {
                manager == null -> null to "unsupported: no PerformanceHintManager"
                manager.preferredUpdateRateNanos < 0 -> null to "unsupported by this device"
                else -> {
                    val session = manager.createHintSession(intArrayOf(tid), targetNanos)
                    if (session == null) null to "unsupported by this device" else Hint(session) to "active"
                }
            }
        } catch (e: RuntimeException) {
            Timber.w(e, "Performance hint session failed")
            null to "failed: ${e.javaClass.simpleName}"
        }

        private fun cpuList(mask: Long): String {
            val cpus = (0 until 64).filter { mask and (1L shl it) != 0L }
            if (cpus.isEmpty()) return "?"
            // Runs as "4-7", singles as "3".
            val parts = mutableListOf<String>()
            var start = cpus[0]
            var prev = start
            for (c in cpus.drop(1) + Int.MIN_VALUE) {
                if (c == prev + 1) {
                    prev = c
                    continue
                }
                parts += if (start == prev) "$start" else "$start-$prev"
                start = c
                prev = c
            }
            return parts.joinToString(",")
        }
    }
}
