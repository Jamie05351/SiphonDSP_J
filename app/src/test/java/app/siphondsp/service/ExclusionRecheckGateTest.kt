package app.siphondsp.service

import org.junit.Assert.assertEquals
import org.junit.Test

class ExclusionRecheckGateTest {

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

    private data class Check(val at: Long, val reasons: Set<String>, val packages: Set<String>)

    private fun gate(scheduler: FakeScheduler, checks: MutableList<Check>) = ExclusionRecheckGate(
        debounceMs = 2_000,
        maxWaitMs = 10_000,
        now = { scheduler.now },
        schedule = scheduler::schedule,
        check = { reasons, packages -> checks += Check(scheduler.now, reasons, packages) },
    )

    @Test
    fun aSingleRequestChecksAfterTheDebounce() {
        val scheduler = FakeScheduler()
        val checks = mutableListOf<Check>()
        gate(scheduler, checks).request("policy", listOf("com.dabble"))
        scheduler.advanceTo(1_999)
        assertEquals(0, checks.size)
        scheduler.advanceTo(2_000)
        assertEquals(listOf(Check(2_000, setOf("policy"), setOf("com.dabble"))), checks)
    }

    @Test
    fun aBurstChecksOnceWithEveryReasonAndPackage() {
        val scheduler = FakeScheduler()
        val checks = mutableListOf<Check>()
        val g = gate(scheduler, checks)
        // Dabble stopping and starting sessions every 300 ms for 1.5 s, plus a blocklist edit.
        for (i in 0..5) {
            scheduler.advanceTo(i * 300L)
            g.request("policy", listOf(if (i % 2 == 0) "com.dabble" else "com.other"))
        }
        g.request("blocklist")
        scheduler.advanceTo(60_000)
        assertEquals(1, checks.size)
        assertEquals(1_500L + 2_000L, checks[0].at)
        assertEquals(setOf("policy", "blocklist"), checks[0].reasons)
        assertEquals(setOf("com.dabble", "com.other"), checks[0].packages)
    }

    @Test
    fun continuousChurnIsStillCheckedWithinTheCap() {
        val scheduler = FakeScheduler()
        val checks = mutableListOf<Check>()
        val g = gate(scheduler, checks)
        // A request every second for 25 s: the debounce alone would never fire.
        for (i in 0..25) {
            scheduler.advanceTo(i * 1_000L)
            g.request("policy")
        }
        scheduler.advanceTo(60_000)
        assertEquals(listOf(10_000L, 20_000L, 27_000L), checks.map { it.at })
    }

    @Test
    fun aRequestAfterACheckStartsANewBurst() {
        val scheduler = FakeScheduler()
        val checks = mutableListOf<Check>()
        val g = gate(scheduler, checks)
        g.request("a", listOf("p1"))
        scheduler.advanceTo(5_000)
        g.request("b", listOf("p2"))
        scheduler.advanceTo(20_000)
        assertEquals(
            listOf(Check(2_000, setOf("a"), setOf("p1")), Check(7_000, setOf("b"), setOf("p2"))),
            checks,
        )
    }

    @Test
    fun cancelDropsThePendingBurst() {
        val scheduler = FakeScheduler()
        val checks = mutableListOf<Check>()
        val g = gate(scheduler, checks)
        g.request("policy")
        scheduler.advanceTo(1_000)
        g.cancel()
        scheduler.advanceTo(60_000)
        assertEquals(0, checks.size)
    }
}
