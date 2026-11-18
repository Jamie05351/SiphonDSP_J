package app.siphondsp.session.shared

import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Test

class CoalescingDumpRequestsTest {
    private class MainQueue : CoroutineDispatcher() {
        private val tasks = java.util.concurrent.ConcurrentLinkedQueue<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { tasks.add(block) }
        fun drain() { while (true) (tasks.poll() ?: return).run() }
    }

    @Test fun workerRequestsDuringADumpAreCoalescedIntoOneFollowUp() {
        val main = MainQueue()
        val scope = CoroutineScope(Job() + main)
        val finishFirst = CompletableDeferred<Unit>()
        var calls = 0
        val requests = CoalescingDumpRequests(scope) {
            calls++
            if (calls == 1) finishFirst.await()
        }
        requests.request()
        assertEquals(0, calls) // no bookkeeping or work on the caller
        main.drain()
        assertEquals(1, calls)
        Thread { repeat(20) { requests.request() } }.apply { start(); join() }
        main.drain()
        assertEquals(1, calls)
        finishFirst.complete(Unit)
        main.drain()
        assertEquals(2, calls)
        scope.cancel()
    }

    @Test fun workerRequestAsTheLastDumpFinishesIsNotLost() {
        val main = MainQueue()
        val scope = CoroutineScope(Job() + main)
        var calls = 0
        lateinit var requests: CoalescingDumpRequests
        requests = CoalescingDumpRequests(scope) {
            calls++
            if (calls == 1) {
                // The worker requests while the dump job is still active, just before its
                // last rerun check. The request must be consumed even after this job exits.
                Thread { requests.request() }.apply { start(); join() }
            }
        }
        requests.request()
        main.drain()
        assertEquals(2, calls)
        requests.request()
        main.drain()
        assertEquals(3, calls)
        scope.cancel()
    }

    @Test fun aCancelledManagerDoesNotTakeNewDumps() {
        val main = MainQueue()
        val scope = CoroutineScope(Job() + main)
        var calls = 0
        val requests = CoalescingDumpRequests(scope) { calls++ }
        scope.cancel()
        requests.request()
        main.drain()
        assertEquals(0, calls)
    }
}
