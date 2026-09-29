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
    fun driverTargetUsesTheDriverSeatPath() {
        all.forEach { id ->
            val cm = defaultDriverCm(id)
            assertEquals(cm, SpeakerGeometryMath.targetDistanceCm(AlignTarget.DRIVER, id, ::defaultDriverCm), 0f)
        }
    }

    @Test
    fun multiAveragesTheDriverPathWithItsMirror() {
        // Left tweeter: 130 cm from the driver seat, and the passenger seat mirrors the 78 cm right one.
        val leftTweeter = DriverId(SpeakerKind.TWEETER, left = true)
        assertEquals(104f, SpeakerGeometryMath.targetDistanceCm(AlignTarget.MULTI, leftTweeter, ::defaultDriverCm), 1e-4f)
    }

    @Test
    fun multiGivesLeftAndRightOfABandTheSameAlignment() {
        val distances = all.map { SpeakerGeometryMath.targetDistanceCm(AlignTarget.MULTI, it, ::defaultDriverCm) }
        SpeakerKind.entries.forEach { kind ->
            val l = SpeakerGeometryMath.targetDistanceCm(AlignTarget.MULTI, DriverId(kind, true), ::defaultDriverCm)
            val r = SpeakerGeometryMath.targetDistanceCm(AlignTarget.MULTI, DriverId(kind, false), ::defaultDriverCm)
            assertEquals(
                SpeakerGeometryMath.alignDelayMs(l, distances),
                SpeakerGeometryMath.alignDelayMs(r, distances),
                1e-4f,
            )
        }
    }

    @Test
    fun multiAlignmentsFitTheDelayRange() {
        val distances = all.map { SpeakerGeometryMath.targetDistanceCm(AlignTarget.MULTI, it, ::defaultDriverCm) }
        distances.forEach { cm -> assertTrue(SpeakerGeometryMath.alignDelayMs(cm, distances) in 0f..2.8f) }
    }

    private fun defaultDriverCm(id: DriverId) = SpeakerGeometryMath.defaultDistanceCm(ListeningSeat.DRIVER, id)

    @Test
    fun emptyInputIsSafe() {
        assertEquals(0f, SpeakerGeometryMath.alignDelayMs(100f, emptyList()), 0f)
    }
}
