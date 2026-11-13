package app.siphondsp.model.debug

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StageProfileTest {

    private fun raw(buffers: Long, frames: Long, wallUs: Long, cpuUs: Long, stageUs: (Int) -> Long): DoubleArray {
        val n = StageProfile.STAGE_NAMES.size
        val out = DoubleArray(StageProfile.SIZE)
        out[0] = 1.0
        out[1] = buffers.toDouble()
        out[2] = frames.toDouble()
        out[3] = wallUs * 1000.0
        out[4] = cpuUs * 1000.0
        out[5] = 90_000_000.0
        out[6] = 80_000_000.0
        out[7] = 6.0
        for (i in 0 until n) {
            out[8 + 2 * i] = stageUs(i) * 1000.0
            out[9 + 2 * i] = stageUs(i) * 1000.0 / buffers.coerceAtLeast(1)
        }
        return out
    }

    @Test
    fun layoutMatchesTheNativeStageCount() {
        // NativeBmwDsp::StageProfiler: 8 header values, then [total, max] per stage.
        assertEquals(20, StageProfile.STAGE_NAMES.size)
        assertEquals(8 + 2 * 20, StageProfile.SIZE)
        assertNull(StageProfile.parse(DoubleArray(10)))
    }

    @Test
    fun summaryIsTheWindowBetweenTwoReadingsMostExpensiveFirst() {
        val mbc = StageProfile.STAGE_NAMES.indexOf("MBC")
        val earlier = StageProfile.parse(raw(10, 38_400, 300_000, 280_000) { if (it == mbc) 100_000 else 1_000 })!!
        val now = StageProfile.parse(raw(20, 76_800, 700_000, 600_000) { if (it == mbc) 300_000 else 2_000 })!!
        val s = now.summarize(earlier, 48_000.0)!!
        assertEquals(10, s.buffers)
        assertEquals(80_000.0, s.budgetUs, 1e-6)  // 3840 frames at 48 kHz
        assertEquals(40_000.0, s.avgWallUs, 1e-6)
        assertEquals(32_000.0, s.avgCpuUs, 1e-6)
        assertEquals(8_000.0, s.avgOffCpuUs, 1e-6)
        assertEquals(50.0, s.ofBudgetPercent(s.avgWallUs), 1e-9)
        assertEquals("MBC", s.stages.first().name)
        assertEquals(20_000.0, s.stages.first().avgUs, 1e-6)
        assertEquals(100.0, s.stages.sumOf { it.sharePercent }, 1e-6)
        assertEquals(6, s.lastCpu)
    }

    @Test
    fun aResetOrMissingEarlierReadingFallsBackToTotals() {
        val now = StageProfile.parse(raw(4, 15_360, 100_000, 90_000) { 1_000 })!!
        val later = StageProfile.parse(raw(50, 192_000, 2_000_000, 1_000_000) { 10_000 })!!
        assertEquals(4, now.summarize(later, 48_000.0)!!.buffers)  // counts went backwards: restarted
        assertEquals(4, now.summarize(null, 48_000.0)!!.buffers)
        assertTrue(now.summarize(null, 0.0)!!.budgetUs == 0.0)
    }
}
