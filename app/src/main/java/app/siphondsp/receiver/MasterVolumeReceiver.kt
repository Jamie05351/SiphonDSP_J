package app.siphondsp.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
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
 * Registered at runtime by [app.siphondsp.MainApplication] for as long as the app's process runs,
 * not in the manifest: the KSW head unit's firmware never delivered this broadcast to a manifest
 * receiver, even with the app open. The DSP service runs in this same process, so whenever the DSP
 * is processing audio this is listening; a press while the app isn't running at all does nothing.
 */
class MasterVolumeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        context ?: return
        intent ?: return
        Timber.i("MasterVolumeReceiver: ${intent.extras?.keySet()}")
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
        /** A finite dB from a broadcast extra (number or string), else null -- NaN and infinity included. */
        internal fun parseDb(raw: Any?): Float? = raw?.toString()?.trim()?.toFloatOrNull()?.takeIf { it.isFinite() }

        const val ACTION = "app.siphondsp.action.MASTER_VOLUME"
        const val EXTRA_STEP = "step"
        const val EXTRA_DB = "db"
        const val EXTRA_MUTE = "mute"
    }
}
