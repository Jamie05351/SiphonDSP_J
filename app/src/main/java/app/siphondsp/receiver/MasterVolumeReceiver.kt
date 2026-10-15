package app.siphondsp.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.SystemClock
import app.siphondsp.model.MasterVolume
import timber.log.Timber

/**
 * The DSP master volume, driven from outside the app -- the steering-wheel +/- remapped in KSW
 * Toolkit to Tasker tasks that send this broadcast. Exported without a permission on purpose:
 * Tasker cannot hold a custom permission, and the worst any sender can do is change the volume
 * within -80..0 dB (it can never raise the level above full).
 *
 * Action `app.siphondsp.action.MASTER_VOLUME`, package `app.siphondsp` (`.debug` for debug
 * builds), target Broadcast Receiver. One of these extras:
 *  - `step`  (int)    +1 / -1 moves the volume by [MasterVolume.STEP_DB] per step
 *  - `db`    (float)  sets it outright, e.g. -30
 *  - `mute`  (string) `toggle`, `on` or `off`
 *
 * Two copies listen: the manifest one, and a [live] one [MainApplication] registers at runtime for
 * as long as the app's process runs. Some head-unit firmware (KSW) never delivers broadcasts to
 * manifest receivers, even with the app open; the live one still gets them.
 *
 * When both get the same broadcast, the live one gets it first (its filter has a higher priority)
 * and the manifest one stands aside, so a step is applied once. Each live receipt pairs with at
 * most one manifest receipt, and only within [PairWindowMs]: a broadcast that cold-starts the
 * process reaches only the manifest copy and must still be applied, and on firmware that never
 * delivers to the manifest copy, old live receipts expire rather than swallowing later presses.
 */
class MasterVolumeReceiver(private val live: Boolean = false) : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        context ?: return
        intent ?: return
        if (live) liveApplied() else if (pairedWithLive()) return
        Timber.i("MasterVolumeReceiver (${if (live) "live" else "manifest"}): ${intent.extras?.keySet()}")
        val state = when {
            intent.hasExtra(EXTRA_STEP) -> MasterVolume.step(context, readInt(intent, EXTRA_STEP))
            intent.hasExtra(EXTRA_DB) -> MasterVolume.set(context, parseDb(intent.extras?.get(EXTRA_DB)) ?: run {
                Timber.w("MasterVolumeReceiver: ignoring a db extra that is not a finite number")
                return
            })
            intent.hasExtra(EXTRA_MUTE) -> MasterVolume.setMuted(
                context,
                when (intent.getStringExtra(EXTRA_MUTE)?.lowercase()) {
                    "on", "true", "1" -> true
                    "off", "false", "0" -> false
                    else -> null
                },
            )
            else -> {
                Timber.w("MasterVolumeReceiver: no step / db / mute extra")
                return
            }
        }
        if (state == null) {
            Timber.e("MasterVolumeReceiver: could not save the volume")
            return
        }
        MasterVolume.show(context, state)
    }

    // Tasker sends extras as strings unless told otherwise; accept both.
    private fun readInt(intent: Intent, key: String): Int =
        intent.extras?.get(key)?.toString()?.trim()?.toFloatOrNull()?.toInt() ?: 0

    companion object {
        /** When the [live] copy applied broadcasts the manifest copy hasn't seen yet, oldest first. */
        private val liveUnmatched = ArrayDeque<Long>()

        /** How long a live receipt waits for its manifest twin, which comes within milliseconds. */
        private const val PairWindowMs = 500L

        @Synchronized
        private fun liveApplied() {
            dropExpired(SystemClock.elapsedRealtime())
            liveUnmatched.addLast(SystemClock.elapsedRealtime())
        }

        /** Whether the live copy already applied this broadcast; if so, uses up that receipt. */
        @Synchronized
        private fun pairedWithLive(): Boolean {
            dropExpired(SystemClock.elapsedRealtime())
            return liveUnmatched.removeFirstOrNull() != null
        }

        private fun dropExpired(now: Long) {
            while (liveUnmatched.firstOrNull()?.let { now - it > PairWindowMs } == true) liveUnmatched.removeFirst()
        }

        /** The live copy's filter: ahead of the manifest copy (priority 0) for the same broadcast. */
        fun liveFilter() = IntentFilter(ACTION).apply { priority = 1 }

        /** A finite dB from a broadcast extra (number or string), else null -- NaN and infinity included. */
        internal fun parseDb(raw: Any?): Float? = raw?.toString()?.trim()?.toFloatOrNull()?.takeIf { it.isFinite() }

        const val ACTION = "app.siphondsp.action.MASTER_VOLUME"
        const val EXTRA_STEP = "step"
        const val EXTRA_DB = "db"
        const val EXTRA_MUTE = "mute"
    }
}
