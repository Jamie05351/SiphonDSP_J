package app.siphondsp.service

import org.junit.Assert.assertEquals
import org.junit.Test

class AudioThreadStatsTest {

    private fun stats() = AudioThreadStats().apply { reset() }

    @Test
    fun processTimesLandInBudgetQuarters() {
        val stats = stats()
        // 960 frames at 48 kHz: a 20 ms budget.
        stats.recordProcess(1_000_000, 960, 48000)   // 5%
        stats.recordProcess(7_000_000, 960, 48000)   // 35%
        stats.recordProcess(19_000_000, 960, 48000)  // 95%
        stats.recordProcess(25_000_000, 960, 48000)  // 125%: over
        stats.recordProcess(80_000_000, 960, 48000)  // 400%: still just over
        assertEquals(listOf(1L, 1L, 0L, 1L, 2L), stats.budgetBucketCounts)
        assertEquals(20_000.0, stats.budgetMicros, 0.0)
        assertEquals(80_000.0, stats.processMaxMicros, 0.0)
        assertEquals(80_000.0, stats.processRecentMaxMicros, 0.0)
        assertEquals(26_400.0, stats.processMeanMicros, 1e-6)
    }

    @Test
    fun readSizesGiveMeanSpreadAndShortReads() {
        val stats = stats()
        listOf(1920, 1920, 1920, 960).forEach { stats.recordRead(it, 1920) }
        assertEquals(4L, stats.readCallCount)
        assertEquals(1L, stats.shortReadCount)
        assertEquals(960, stats.readMinSamples)
        assertEquals(1920, stats.readMaxSamples)
        assertEquals(1680.0, stats.readMean, 1e-9)
        assertEquals(Math.sqrt(172_800.0), stats.readStdDev, 1e-6)
    }

    @Test
    fun underrunsCountAcrossRecreatedTracksAndAgeOutOfTheMinute() {
        val stats = stats()
        var now = 0L
        stats.sampleUnderruns(0, now)
        repeat(30) { now += 2000; stats.sampleUnderruns(0, now) }
        now += 2000; stats.sampleUnderruns(3, now)
        assertEquals(3L to 3L, stats.underruns(now))
        // A recreated track starts counting from 0 again: its whole count is new.
        now += 2000; stats.sampleUnderruns(1, now)
        assertEquals(4L to 4L, stats.underruns(now))
        repeat(35) { now += 2000; stats.sampleUnderruns(1, now) }
        assertEquals(4L to 0L, stats.underruns(now))
    }

    @Test
    fun resetStartsOver() {
        val stats = stats()
        stats.recordProcess(5_000_000, 960, 48000)
        stats.recordRead(1920, 1920)
        stats.sampleUnderruns(2, 0)
        stats.reset()
        assertEquals(0L, stats.processCallCount)
        assertEquals(0L, stats.readCallCount)
        assertEquals(0, stats.readMinSamples)
        assertEquals(0L to 0L, stats.underruns(0))
    }
}
