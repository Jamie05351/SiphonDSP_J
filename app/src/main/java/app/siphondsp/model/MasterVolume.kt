package app.siphondsp.model

import android.annotation.SuppressLint
import android.content.Context
import android.widget.Toast
import kotlin.math.roundToInt

/**
 * The DSP master volume (`INDEX_MASTER_VOLUME_DB` / `INDEX_MASTER_MUTE`): the car's volume when the
 * steering-wheel +/- are remapped (KSW Toolkit -> Tasker -> [app.siphondsp.receiver.MasterVolumeReceiver])
 * and the factory volume is left at one fixed level. Every change goes through the same
 * load -> save -> broadcast path as any other DSP edit, so the engine and open screens follow it.
 */
object MasterVolume {
    const val STEP_DB = 1f
    /** What an unreadable stored volume falls back to: quiet, never full level. */
    const val SAFE_FALLBACK_DB = -40f

    data class State(val db: Float, val muted: Boolean)

    fun read(context: Context): State {
        val v = NativeBmwDspValues.load(context)
        return State(v[NativeBmwDspValues.INDEX_MASTER_VOLUME_DB], v[NativeBmwDspValues.INDEX_MASTER_MUTE] >= .5f)
    }

    /** Moves the volume by [steps] of [STEP_DB]. Turning it up also unmutes, like a car's volume knob. */
    fun step(context: Context, steps: Int): State? = update(context) { s ->
        State(clamp(s.db + steps * STEP_DB) ?: return@update null, if (steps > 0) false else s.muted)
    }

    /** Null (nothing saved) for a non-finite [db] -- see [clamp]. */
    fun set(context: Context, db: Float): State? = update(context) { s -> State(clamp(db) ?: return@update null, s.muted) }

    /** [muted] null toggles. */
    fun setMuted(context: Context, muted: Boolean?): State? = update(context) { s -> State(s.db, muted ?: !s.muted) }

    fun label(state: State): String =
        if (state.muted) "DSP volume: muted (${formatDb(state.db)})" else "DSP volume: ${formatDb(state.db)}"

    private fun formatDb(db: Float): String {
        if (!db.isFinite()) return "?"
        val whole = db.roundToInt()
        return if (whole == 0) "0 dB" else "$whole dB"
    }

    /**
     * The volume to store, or null for NaN / infinity. coerceIn() passes NaN straight through, and a
     * non-finite value in the saved config makes native configure() reject the WHOLE config -- on
     * every start, since it is persisted -- so it must never get that far.
     */
    internal fun clamp(db: Float): Float? =
        if (db.isFinite()) db.coerceIn(NativeBmwDspValues.MASTER_VOLUME_MIN_DB, NativeBmwDspValues.MASTER_VOLUME_MAX_DB) else null

    @Synchronized
    private fun update(context: Context, change: (State) -> State?): State? {
        val values = NativeBmwDspValues.load(context)
        // A stored value that is somehow not finite reads as a safe, quiet level -- never as full
        // volume -- rather than poisoning every step after it.
        val stored = values[NativeBmwDspValues.INDEX_MASTER_VOLUME_DB].takeIf { it.isFinite() } ?: SAFE_FALLBACK_DB
        val now = State(stored, values[NativeBmwDspValues.INDEX_MASTER_MUTE] >= .5f)
        val next = change(now) ?: return null
        values[NativeBmwDspValues.INDEX_MASTER_VOLUME_DB] = next.db
        values[NativeBmwDspValues.INDEX_MASTER_MUTE] = if (next.muted) 1f else 0f
        if (!NativeBmwDspValues.save(context, values)) return null
        NativeBmwDspValues.broadcast(context, values)
        return next
    }

    // One toast, replaced on every step, so a fast knob turn shows the latest level instead of a queue.
    @SuppressLint("StaticFieldLeak")
    private var toast: Toast? = null

    fun show(context: Context, state: State) {
        toast?.cancel()
        toast = Toast.makeText(context.applicationContext, label(state), Toast.LENGTH_SHORT).also { it.show() }
    }
}
