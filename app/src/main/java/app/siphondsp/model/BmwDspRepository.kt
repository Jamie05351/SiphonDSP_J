package app.siphondsp.model

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import app.siphondsp.utils.Constants
import app.siphondsp.utils.extensions.ContextExtensions.registerLocalReceiver
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Single process-wide source of truth for the native BMW DSP config
 * (`NativeBmwDspValues`, a `FloatArray` indexed by `INDEX_*`). Replaces the earlier
 * per-composition `BmwDspState` pattern, where every `rememberBmwDspState()` call site held its
 * own independent in-memory copy -- two screens editing around the same time could silently
 * clobber each other's change, since each screen's `commit()` only knew about its own copy.
 *
 * Registered as a Koin singleton (see `MainApplication.kt`), so there is exactly one instance and
 * one [values] `StateFlow` for the whole app to observe. Because this is now the *only* writer,
 * [commit] doesn't need to re-read disk before mutating -- its own in-memory copy is always
 * authoritative, unlike the old per-screen copies.
 */
class BmwDspRepository(private val appContext: Context) {
    private val lock = Any()
    private val _values = MutableStateFlow(NativeBmwDspValues.load(appContext))
    val values: StateFlow<FloatArray> = _values.asStateFlow()

    init {
        // Safety net / consistency, not the primary update path: anything that still calls
        // NativeBmwDspValues.broadcast() directly (native-side callbacks, legacy code not yet
        // routed through this repository) is picked up here too. Self-broadcasts from commit()/
        // preview()/restoreFrom() below just reassign the same content to a new array instance --
        // harmless.
        appContext.registerLocalReceiver(
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    intent.getFloatArrayExtra(Constants.EXTRA_NATIVE_BMW_DSP_VALUES)?.let {
                        _values.value = it
                    }
                }
            },
            IntentFilter(Constants.ACTION_NATIVE_BMW_DSP_UPDATED),
        )
    }

    /** Live update: snapshot + broadcast, no disk. Use during a drag. */
    fun preview(index: Int, value: Float, mirrors: IntArray) {
        val next = _values.value.copyOf()
        next[index] = value
        for (m in mirrors) next[m] = value
        _values.value = next
        NativeBmwDspValues.broadcast(appContext, next)
    }

    /** Commit only after persistence succeeds; resets to actual disk state on failure (discarding
     *  any optimistic [preview] this index may have shown mid-drag). */
    fun commit(index: Int, value: Float, mirrors: IntArray): Boolean = synchronized(lock) {
        val next = _values.value.copyOf()
        next[index] = value
        for (m in mirrors) next[m] = value
        if (!NativeBmwDspValues.save(appContext, next)) {
            refreshFromDiskLocked()
            return@synchronized false
        }
        _values.value = next
        NativeBmwDspValues.broadcast(appContext, next)
        true
    }

    /** Replace the whole config (already migrated by the caller) -- the backup-restore path. */
    fun restoreFrom(values: FloatArray): Boolean = synchronized(lock) {
        if (!NativeBmwDspValues.save(appContext, values)) return@synchronized false
        _values.value = values
        NativeBmwDspValues.broadcast(appContext, values)
        true
    }

    /** Reloads the persisted values and re-broadcasts them so the native engine drops any
     *  un-persisted [preview] it applied (e.g. a measurement generator type/timing-ref toggle left
     *  running) -- otherwise the UI would resync to disk while the engine kept running the
     *  previewed value. Called on screen resume and on a failed [commit]. */
    fun refreshFromDisk() = synchronized(lock) { refreshFromDiskLocked() }

    private fun refreshFromDiskLocked() {
        val loaded = NativeBmwDspValues.load(appContext)
        _values.value = loaded
        NativeBmwDspValues.broadcast(appContext, loaded)
    }
}
