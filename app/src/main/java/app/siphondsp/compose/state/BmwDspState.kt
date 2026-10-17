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
 * The one in-memory copy of the native BMW DSP config (`NativeBmwDspValues`, a `FloatArray`
 * indexed by `INDEX_*`) for a settings store -- in the app, exactly one. Every [BmwDspState]
 * (one per [rememberBmwDspState] call site, ~two dozen screens) reads and writes through it, so:
 * - a preview or commit on one screen is immediately what every other screen shows, without a
 *   broadcast round trip;
 * - commits are serialised on the shared array, so two screens committing at once can no longer
 *   each save their own stale copy over the other's change;
 * - the store is loaded once, not once per screen and again on every resume.
 *
 * Write model:
 * - [preview] updates the shared snapshot and broadcasts (so the audio engine follows a drag
 *   live) but does NOT touch disk.
 * - [commit] persists, then publishes and broadcasts -- call it on slider release and on a toggle.
 */
internal class BmwDspRepository private constructor(private val appContext: Context) {

    var values: FloatArray by mutableStateOf(NativeBmwDspValues.load(appContext))
        private set

    private val lock = Any()

    // True while the engine may be running a previewed value that was never persisted.
    private var previewPending = false

    // How many BmwDspState holders currently have a drag/edit in flight.
    private var editors = 0

    fun preview(index: Int, value: Float, mirrors: IntArray) = synchronized(lock) {
        val next = values.copyOf()
        next[index] = value
        for (m in mirrors) next[m] = value
        values = next
        previewPending = true
        NativeBmwDspValues.broadcast(appContext, next)
    }

    fun commit(updates: Map<Int, Float>): Boolean = synchronized(lock) {
        val next = values.copyOf()
        for ((index, value) in updates) next[index] = value
        if (!NativeBmwDspValues.save(appContext, next)) {
            refreshFromDiskLocked()
            appContext.toast("BMW DSP settings could not be saved; previous settings restored")
            return false
        }
        values = next
        previewPending = false
        NativeBmwDspValues.broadcast(appContext, next)
        true
    }

    fun refreshFromDisk() = synchronized(lock) { refreshFromDiskLocked() }

    // Reloads the persisted values. Re-broadcasts only when the engine may be off them -- an
    // un-persisted preview still applied (e.g. a measurement generator toggle left running) or a
    // disk copy that changed underneath -- rather than on every screen's every resume.
    private fun refreshFromDiskLocked() {
        val loaded = NativeBmwDspValues.load(appContext)
        val differs = !loaded.contentEquals(values)
        if (differs) values = loaded
        if (differs || previewPending) {
            previewPending = false
            NativeBmwDspValues.broadcast(appContext, loaded)
        }
    }

    fun onExternalUpdate(incoming: FloatArray) = synchronized(lock) {
        // A drag in flight keeps its own value; the broadcast echo of our own preview is the same
        // array anyway.
        if (editors == 0) values = incoming
    }

    fun setEditing(editing: Boolean) = synchronized(lock) {
        editors = (editors + if (editing) 1 else -1).coerceAtLeast(0)
    }

    companion object {
        private val instances = HashMap<String, BmwDspRepository>()

        /** The repository for [context]'s settings store (keyed by its directory). */
        fun forContext(context: Context): BmwDspRepository = synchronized(instances) {
            instances.getOrPut(context.noBackupFilesDir.absolutePath) {
                BmwDspRepository(context.applicationContext.let { app ->
                    // Keep the caller's context when it overrides the store directory (tests do),
                    // otherwise the application context.
                    if (app.noBackupFilesDir == context.noBackupFilesDir) app else context
                })
            }
        }
    }
}

/**
 * A screen's handle on the shared [BmwDspRepository]: the same read / preview / commit / observe
 * shape the screens were written against, plus the screen's own "a drag is in flight" flag.
 */
class BmwDspState internal constructor(context: Context) {

    private val repository = BmwDspRepository.forContext(context)

    val values: FloatArray get() = repository.values

    /** Set while a local drag/edit is in flight so the inbound broadcast (including our own
     *  [preview] echo) doesn't stomp the value the user is actively dragging. */
    internal var editing: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            repository.setEditing(value)
        }

    fun get(index: Int): Float = values.getOrElse(index) { 0f }

    fun isOn(index: Int): Boolean = get(index) >= 0.5f

    /** Live update: snapshot + broadcast, no disk. Use during a drag. */
    fun preview(index: Int, value: Float, mirrors: IntArray = EmptyMirrors) =
        repository.preview(index, value, mirrors)

    /** Commit only after persistence succeeds; undo any live preview on failure. */
    fun commit(index: Int, value: Float, mirrors: IntArray = EmptyMirrors): Boolean {
        val updates = HashMap<Int, Float>(1 + mirrors.size)
        updates[index] = value
        for (m in mirrors) updates[m] = value
        return repository.commit(updates)
    }

    /**
     * [commit] for several indices with different values, persisted and broadcast as one update
     * -- for switches that must flip a group of fields together (e.g. the Crossovers page's 3-way
     * toggle) without the engine ever seeing a half-applied intermediate state.
     */
    fun commitAll(updates: Map<Int, Float>): Boolean = repository.commit(updates)

    /** Reloads the persisted values, re-broadcasting when the engine may be running a value that
     *  was never persisted (see [BmwDspRepository.refreshFromDisk]). */
    internal fun refreshFromDisk() = repository.refreshFromDisk()

    internal fun onExternalUpdate(incoming: FloatArray) {
        if (editing) return
        repository.onExternalUpdate(incoming)
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
        onDispose {
            context.unregisterLocalReceiver(receiver)
            // A screen leaving mid-drag must not leave the shared repository ignoring updates.
            state.editing = false
        }
    }

    LifecycleResumeEffect(state) {
        state.refreshFromDisk()
        onPauseOrDispose { }
    }

    return state
}
