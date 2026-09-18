package app.siphondsp.compose.state

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.siphondsp.model.BmwDspRepository
import app.siphondsp.utils.extensions.ContextExtensions.toast
import org.koin.compose.koinInject

/**
 * Per-recomposition, read-only snapshot of [BmwDspRepository]'s shared config, plus [preview]/
 * [commit] writers that delegate straight to it. Deliberately thin -- [BmwDspRepository] is the
 * only place the config actually lives, so every screen observing it sees the same value and a
 * commit on one screen can never be silently overwritten by a stale copy held by another.
 */
class BmwDspState internal constructor(
    private val appContext: Context,
    private val repo: BmwDspRepository,
    val values: FloatArray,
) {
    fun get(index: Int): Float = values.getOrElse(index) { 0f }

    fun isOn(index: Int): Boolean = get(index) >= 0.5f

    /** Live update: snapshot + broadcast, no disk. Use during a drag. */
    fun preview(index: Int, value: Float, mirrors: IntArray = EmptyMirrors) {
        repo.preview(index, value, mirrors)
    }

    /** Commit only after persistence succeeds; the repository resets itself to disk state and
     *  this toasts on failure. */
    fun commit(index: Int, value: Float, mirrors: IntArray = EmptyMirrors): Boolean {
        val ok = repo.commit(index, value, mirrors)
        if (!ok) {
            appContext.toast("BMW DSP settings could not be saved; previous settings restored")
        }
        return ok
    }

    private companion object {
        val EmptyMirrors = IntArray(0)
    }
}

/**
 * Reads the shared [BmwDspRepository] and returns a fresh [BmwDspState] snapshot whenever it
 * changes -- from this screen's own edits, another screen's, a restored backup, or the native
 * engine's own broadcast. Also forces a disk resync on resume, so a measurement-generator toggle
 * or other un-persisted [BmwDspState.preview] left running while this composition was stopped
 * doesn't strand the UI showing a value that was never actually saved.
 */
@Composable
fun rememberBmwDspState(): BmwDspState {
    val context = LocalContext.current
    val repo = koinInject<BmwDspRepository>()
    val values by repo.values.collectAsStateWithLifecycle()
    val state = remember(context, repo, values) {
        BmwDspState(context.applicationContext, repo, values)
    }

    LifecycleResumeEffect(repo) {
        repo.refreshFromDisk()
        onPauseOrDispose { }
    }

    return state
}
