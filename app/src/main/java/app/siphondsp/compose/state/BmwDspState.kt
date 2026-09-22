package app.siphondsp.compose.state

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.siphondsp.model.NativeBmwDspValues
import app.siphondsp.utils.Constants
import app.siphondsp.utils.extensions.ContextExtensions.registerLocalReceiver
import app.siphondsp.utils.extensions.ContextExtensions.unregisterLocalReceiver
import app.siphondsp.utils.extensions.ContextExtensions.toast

/**
 * v1 Compose state layer for the native BMW DSP config (`NativeBmwDspValues`, a `FloatArray`
 * indexed by `INDEX_*`). Composition-scoped -- one instance per [rememberBmwDspState] call site
 * (currently one: [app.siphondsp.compose.screens.TonalityTiltScreen]). Replaces the View
 * system's `Fragment.rebuild()` pattern of loading the array into a local, mutating it in place,
 * and calling `save` + `broadcast` on every change.
 *
 * Deliberately NOT an app-wide repository or a ViewModel yet -- see COMPOSE_MIGRATION_ROADMAP.md
 * section 3a / section 13. This establishes the read / preview / commit / observe shape so the
 * next few leaf-screen ports reuse it verbatim; once 2-3 screens are on it, promote to a shared
 * `BmwDspRepository` with a single receiver + writer.
 *
 * Write model, matching the View path's behaviour:
 * - [preview] updates the in-memory snapshot and broadcasts (so the audio engine follows a drag
 *   live) but does NOT touch disk.
 * - [commit] does [preview] plus a disk save -- call it on slider release and on a toggle.
 */
class BmwDspState internal constructor(private val appContext: Context) {

    var values: FloatArray by mutableStateOf(NativeBmwDspValues.load(appContext))
        private set

    /** Set while a local drag/edit is in flight so the inbound broadcast (including our own
     *  [preview] echo) doesn't stomp the value the user is actively dragging. */
    internal var editing: Boolean = false

    fun get(index: Int): Float = values.getOrElse(index) { 0f }

    fun isOn(index: Int): Boolean = get(index) >= 0.5f

    /** Live update: snapshot + broadcast, no disk. Use during a drag. */
    fun preview(index: Int, value: Float, mirrors: IntArray = EmptyMirrors) {
        val next = values.copyOf()
        next[index] = value
        for (m in mirrors) next[m] = value
        values = next
        NativeBmwDspValues.broadcast(appContext, next)
    }

    /**
     * Commit only after persistence succeeds; undo any live preview on failure.
     *
     * Builds the saved array from this composition's own [values] directly rather than a fresh
     * disk read: [NativeBmwDspValues.load] re-runs every one-time migration on each call, and
     * doing that on every single commit (confirmed by bisecting PR #387, which introduced a
     * disk-reload here) made committed crossover type/frequency changes unreliable -- a later
     * commit's fresh reload could re-derive a value that didn't match what was just set. This
     * reintroduces the narrower, pre-existing risk #387 was trying to close (two separate
     * BmwDspState instances committing at the same moment can race), which is a real but rare
     * edge case, unlike this bug, which broke ordinary single-screen editing outright.
     */
    fun commit(index: Int, value: Float, mirrors: IntArray = EmptyMirrors): Boolean {
        val next = values.copyOf()
        next[index] = value
        for (m in mirrors) next[m] = value
        if (!NativeBmwDspValues.save(appContext, next)) {
            refreshFromDisk()
            appContext.toast("BMW DSP settings could not be saved; previous settings restored")
            return false
        }
        values = next
        NativeBmwDspValues.broadcast(appContext, next)
        return true
    }

    /**
     * [commit] for several indices with different values, persisted and broadcast as one update
     * -- for switches that must flip a group of fields together (e.g. the Crossovers page's 3-way
     * toggle) without the engine ever seeing a half-applied intermediate state.
     */
    fun commitAll(updates: Map<Int, Float>): Boolean {
        val next = values.copyOf()
        for ((index, value) in updates) next[index] = value
        if (!NativeBmwDspValues.save(appContext, next)) {
            refreshFromDisk()
            appContext.toast("BMW DSP settings could not be saved; previous settings restored")
            return false
        }
        values = next
        NativeBmwDspValues.broadcast(appContext, next)
        return true
    }

    /** Reloads the persisted values and re-broadcasts them so the native engine drops any
     *  un-persisted [preview] it applied before this composition was paused (e.g. a measurement
     *  generator type/timing-ref toggle left running) -- otherwise the UI would resync to disk
     *  while the engine kept running the previewed value. */
    internal fun refreshFromDisk() {
        val loaded = NativeBmwDspValues.load(appContext)
        values = loaded
        NativeBmwDspValues.broadcast(appContext, loaded)
    }

    internal fun onExternalUpdate(incoming: FloatArray) {
        if (editing) return
        values = incoming
    }

    private companion object {
        val EmptyMirrors = IntArray(0)
    }
}

/**
 * Remembers a [BmwDspState] for the calling composable, keeps it fresh against the
 * `ACTION_NATIVE_BMW_DSP_UPDATED` local broadcast that every DSP edit (from any screen, a
 * restored preset/profile/backup, etc.) sends, and reloads from disk on resume (covering edits
 * made while this composition was stopped, when the receiver may have been disposed).
 */
@Composable
fun rememberBmwDspState(): BmwDspState {
    val context = LocalContext.current
    val state = remember(context) { BmwDspState(context.applicationContext) }

    DisposableEffect(state) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                intent.getFloatArrayExtra(Constants.EXTRA_NATIVE_BMW_DSP_VALUES)
                    ?.let(state::onExternalUpdate)
            }
        }
        context.registerLocalReceiver(receiver, IntentFilter(Constants.ACTION_NATIVE_BMW_DSP_UPDATED))
        onDispose { context.unregisterLocalReceiver(receiver) }
    }

    LifecycleResumeEffect(state) {
        state.refreshFromDisk()
        onPauseOrDispose { }
    }

    return state
}
