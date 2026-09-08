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

    /** Persisted update: [preview] + disk save. Use on slider release and on toggles. */
    fun commit(index: Int, value: Float, mirrors: IntArray = EmptyMirrors) {
        preview(index, value, mirrors)
        NativeBmwDspValues.save(appContext, values)
    }

    internal fun refreshFromDisk() {
        values = NativeBmwDspValues.load(appContext)
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
