package app.siphondsp.session.shared

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** All queue state belongs to [scope]'s single-thread dispatcher (the main dispatcher). */
internal class CoalescingDumpRequests(
    private val scope: CoroutineScope,
    private val dump: suspend () -> Unit,
) {
    private var job: Job? = null
    private var rerun = false

    fun request() {
        scope.launch {
            rerun = true
            if (job?.isActive == true) return@launch
            job = scope.launch {
                while (rerun) {
                    rerun = false
                    dump()
                }
            }
        }
    }
}
