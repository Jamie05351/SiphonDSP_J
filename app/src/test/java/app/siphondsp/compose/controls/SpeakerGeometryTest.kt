package app.siphondsp.compose.controls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeakerGeometryTest {
    private val all = SpeakerGeometryMath.allDrivers

    @Test
    fun sixDriversTwoPerBand() {
        assertEquals(6, all.size)
        SpeakerKind.entries.forEach { kind -> assertEquals(2, all.count { it.kind == kind }) }
    }

    @Test
    fun farthestDriverGetsNoDelay() {
        val distances = listOf(130f, 78f, 138f, 92f, 125f, 87f)
        assertEquals(0f, SpeakerGeometryMath.alignDelayMs(138f, distances), 0f)
    }

    @Test
    fun closerDriverWaitsForTheDifferenceInTravelTime() {
        val distances = listOf(130f, 78f, 138f, 92f, 125f, 87f)
        // (138 - 78) cm at 34.3 cm/ms
        assertEquals(60f / SoundCmPerMs, SpeakerGeometryMath.alignDelayMs(78f, distances), 1e-4f)
    }

    @Test
    fun defaultAlignmentsFitTheDelayRange() {
        for (seat in ListeningSeat.entries) {
            val distances = all.map { SpeakerGeometryMath.defaultDistanceCm(seat, it) }
            all.forEach { id ->
                val ms = SpeakerGeometryMath.alignDelayMs(SpeakerGeometryMath.defaultDistanceCm(seat, id), distances)
                assertTrue("$seat $id -> $ms ms", ms in 0f..2.8f)
            }
        }
    }

    @Test
    fun passengerSeatMirrorsTheDriverSeat() {
        all.forEach { id ->
            assertEquals(
                SpeakerGeometryMath.defaultDistanceCm(ListeningSeat.DRIVER, id.copy(left = !id.left)),
                SpeakerGeometryMath.defaultDistanceCm(ListeningSeat.PASSENGER, id),
                0f,
            )
        }
    }

    @Test
    fun emptyInputIsSafe() {
        assertEquals(0f, SpeakerGeometryMath.alignDelayMs(100f, emptyList()), 0f)
    }
}
