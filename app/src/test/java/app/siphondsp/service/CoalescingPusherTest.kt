package app.siphondsp.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CoalescingPusherTest {

    /** Single-threaded fake of a Handler: tasks run in time order when the clock advances. */
    private class FakeScheduler {
        var now = 0L
        private val queue = mutableListOf<Pair<Long, Runnable>>()

        fun schedule(delayMs: Long, task: Runnable) {
            queue += (now + delayMs) to task
        }

        fun advanceTo(time: Long) {
            while (true) {
                val next = queue.filter { it.first <= time }.minByOrNull { it.first } ?: break
                queue.remove(next)
                now = maxOf(now, next.first)
                next.second.run()
            }
            now = time
        }
    }

    private fun pusher(scheduler: FakeScheduler, applied: MutableList<Pair<Long, Int>>) =
        CoalescingPusher<Int>(
            minIntervalMs = 33,
            now = { scheduler.now },
            schedule = scheduler::schedule,
            apply = { applied += scheduler.now to it },
        )

    @Test
    fun `first value applies immediately`() {
        val scheduler = FakeScheduler()
        val applied = mutableListOf<Pair<Long, Int>>()
        val p = pusher(scheduler, applied)
        p.submit(1)
        scheduler.advanceTo(0)
        assertEquals(listOf(0L to 1), applied)
    }

    @Test
    fun `a burst collapses to the latest value, at most once per interval`() {
        val scheduler = FakeScheduler()
        val applied = mutableListOf<Pair<Long, Int>>()
        val p = pusher(scheduler, applied)
        // A drag: one event every 5 ms for 100 ms.
        for (i in 0..20) {
            scheduler.advanceTo(i * 5L)
            p.submit(i)
        }
        scheduler.advanceTo(1_000)
        // Never two applies closer than the interval.
        applied.zipWithNext().forEach { (a, b) -> assertTrue(b.first - a.first >= 33) }
        // Far fewer applies than events, and the final drag position always lands.
        assertTrue("applied ${applied.size} times", applied.size <= 5)
        assertEquals(20, applied.last().second)
    }

    @Test
    fun `a value submitted after a quiet period applies at once`() {
        val scheduler = FakeScheduler()
        val applied = mutableListOf<Pair<Long, Int>>()
        val p = pusher(scheduler, applied)
        p.submit(1)
        scheduler.advanceTo(500)
        p.submit(2)
        scheduler.advanceTo(500)
        assertEquals(listOf(0L to 1, 500L to 2), applied)
    }
}
