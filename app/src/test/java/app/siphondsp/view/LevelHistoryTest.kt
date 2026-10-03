package app.siphondsp.view

import org.junit.Assert.assertEquals
import org.junit.Test

class LevelHistoryTest {

    @Test
    fun freshHistoryReadsAsSilence() {
        val history = LevelHistory(4)
        assertEquals(0, history.size)
        for (age in 0 until 4) {
            assertEquals(LevelReadout.FLOOR_DB, history.leftRmsDb(age), 0f)
            assertEquals(LevelReadout.FLOOR_DB, history.rightPeakDb(age), 0f)
        }
    }

    @Test
    fun ageZeroIsTheNewestFrameAndEachFieldKeepsItsChannel() {
        val history = LevelHistory(4)
        history.push(-20f, -10f, -30f, -15f)
        history.push(-21f, -11f, -31f, -16f)
        assertEquals(2, history.size)
        assertEquals(-21f, history.leftRmsDb(0), 0f)
        assertEquals(-11f, history.leftPeakDb(0), 0f)
        assertEquals(-31f, history.rightRmsDb(0), 0f)
        assertEquals(-16f, history.rightPeakDb(0), 0f)
        assertEquals(-20f, history.leftRmsDb(1), 0f)
        assertEquals(LevelReadout.FLOOR_DB, history.leftRmsDb(2), 0f)
    }

    @Test
    fun onceFullTheOldestFrameDropsOff() {
        val history = LevelHistory(3)
        for (i in 1..5) history.push(-i.toFloat(), 0f, 0f, 0f)
        assertEquals(3, history.size)
        assertEquals(-5f, history.leftRmsDb(0), 0f)
        assertEquals(-4f, history.leftRmsDb(1), 0f)
        assertEquals(-3f, history.leftRmsDb(2), 0f)
        // Past the capacity, and negative ages, read as the floor rather than wrapping round.
        assertEquals(LevelReadout.FLOOR_DB, history.leftRmsDb(3), 0f)
        assertEquals(LevelReadout.FLOOR_DB, history.leftRmsDb(-1), 0f)
    }

    @Test
    fun clearGoesBackToSilence() {
        val history = LevelHistory(3)
        history.push(-6f, -3f, -6f, -3f)
        history.clear()
        assertEquals(0, history.size)
        assertEquals(LevelReadout.FLOOR_DB, history.leftPeakDb(0), 0f)
        history.push(-9f, -4f, -9f, -4f)
        assertEquals(-9f, history.leftRmsDb(0), 0f)
        assertEquals(LevelReadout.FLOOR_DB, history.leftRmsDb(1), 0f)
    }
}
