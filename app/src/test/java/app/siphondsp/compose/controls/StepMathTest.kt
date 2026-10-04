package app.siphondsp.compose.controls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StepMathTest {

    @Test
    fun linearStepsByTheControlsStep() {
        assertEquals(-5.5f, StepMath.next(-6f, -12f..0f, 0.5f, +1, musical = false), 1e-4f)
        assertEquals(-6.5f, StepMath.next(-6f, -12f..0f, 0.5f, -1, musical = false), 1e-4f)
    }

    @Test
    fun linearStepsStopAtTheEnds() {
        assertEquals(0f, StepMath.next(0f, -12f..0f, 0.5f, +1, musical = false), 1e-4f)
        assertEquals(-12f, StepMath.next(-12f, -12f..0f, 0.5f, -1, musical = false), 1e-4f)
    }

    @Test
    fun frequenciesStepMusically() {
        // A semitone up from 80 Hz is ~84.8, on a 1 Hz grid 85.
        assertEquals(85f, StepMath.next(80f, 20f..1000f, 1f, +1, musical = true), 1e-4f)
        // The same ratio near the top of a range: 8000 -> ~8476, on a 10 Hz grid 8480.
        assertEquals(8480f, StepMath.next(8000f, 1000f..8000f + 1000f, 10f, +1, musical = true), 1e-4f)
        assertEquals(76f, StepMath.next(80f, 20f..1000f, 1f, -1, musical = true), 1e-4f)
    }

    @Test
    fun aCoarseGridStillMovesOneStep() {
        // 50 Hz steps at 100 Hz: a semitone (~106) snaps back to 100, so it takes one grid step.
        assertEquals(150f, StepMath.next(100f, 50f..2000f, 50f, +1, musical = true), 1e-4f)
        // 1 Hz grid at 20 Hz: a semitone down (~18.9) is below the range, so it stays at 20.
        assertEquals(20f, StepMath.next(20f, 20f..60f, 1f, -1, musical = true), 1e-4f)
        assertEquals(21f, StepMath.next(20f, 20f..60f, 1f, +1, musical = true), 1e-4f)
    }

    @Test
    fun multipleStepsAtOnce() {
        assertEquals(-4f, StepMath.next(-6f, -12f..0f, 0.5f, +1, musical = false, times = 4), 1e-4f)
        // Two semitones up from 1000 Hz is ~1122.5, on a 10 Hz grid 1120.
        assertEquals(1120f, StepMath.next(1000f, 1000f..8000f, 10f, +1, musical = true, times = 2), 1e-4f)
    }

    @Test
    fun holdingSpeedsUp() {
        assertTrue(StepMath.holdInterval(0L) > StepMath.holdInterval(1_500L))
        assertTrue(StepMath.holdInterval(1_500L) > StepMath.holdInterval(3_000L))
        // A fine step over a wide range moves several steps per repeat at full speed.
        val fine = 0f..100f
        assertEquals(1, StepMath.holdMultiplier(500L, fine, 0.01f, musical = false))
        assertTrue(StepMath.holdMultiplier(3_000L, fine, 0.01f, musical = false) > 50)
        // A short range never jumps more than one step.
        assertEquals(1, StepMath.holdMultiplier(3_000L, 20f..60f, 1f, musical = false))
    }

    @Test
    fun onlyFrequenciesAreMusical() {
        assertTrue(StepMath.isMusical("Hz"))
        assertTrue(!StepMath.isMusical("dB"))
        assertTrue(!StepMath.isMusical("ms"))
        assertTrue(!StepMath.isMusical(""))
    }
}
