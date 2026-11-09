package app.siphondsp.utils

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Records every [AppEvent.NativeBmwDspUpdated] sent while it is open. Collects unconfined, so an
 * event is recorded inside the send call: a test sees it straight away, with no looper to idle.
 */
class DspUpdateRecorder : AutoCloseable {
    val received = mutableListOf<AppEvent.NativeBmwDspUpdated>()

    /** Runs inside the send call, for each update, after it is recorded. */
    var onUpdate: (AppEvent.NativeBmwDspUpdated) -> Unit = {}

    private val scope = CoroutineScope(Dispatchers.Unconfined)

    init {
        scope.launch {
            AppEvents.of<AppEvent.NativeBmwDspUpdated>().collect {
                received += it
                onUpdate(it)
            }
        }
    }

    override fun close() = scope.cancel()
}
