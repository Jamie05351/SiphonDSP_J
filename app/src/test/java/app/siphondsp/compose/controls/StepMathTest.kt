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
    fun frequenciesStepOnRoundNumbers() {
        // Even 1 Hz steps in the bass, whatever the control's own step.
        assertEquals(61f, StepMath.next(60f, 20f..1000f, 1f, +1, musical = true), 1e-4f)
        assertEquals(59f, StepMath.next(60f, 20f..1000f, 1f, -1, musical = true), 1e-4f)
        // Band edges: up from 100 uses the next band's 5 Hz, down from 100 the 1 Hz band below.
        assertEquals(105f, StepMath.next(100f, 20f..1000f, 1f, +1, musical = true), 1e-4f)
        assertEquals(99f, StepMath.next(100f, 20f..1000f, 1f, -1, musical = true), 1e-4f)
        assertEquals(310f, StepMath.next(300f, 20f..20000f, 1f, +1, musical = true), 1e-4f)
        assertEquals(1050f, StepMath.next(1000f, 20f..20000f, 1f, +1, musical = true), 1e-4f)
        assertEquals(3100f, StepMath.next(3000f, 20f..20000f, 1f, +1, musical = true), 1e-4f)
        assertEquals(10250f, StepMath.next(10000f, 20f..20000f, 1f, +1, musical = true), 1e-4f)
        assertEquals(9900f, StepMath.next(10000f, 20f..20000f, 1f, -1, musical = true), 1e-4f)
    }

    @Test
    fun anOffGridFrequencySnapsToTheLadder() {
        assertEquals(66.0, StepMath.nextFrequency(65.7, +1), 1e-9)
        assertEquals(65.0, StepMath.nextFrequency(65.7, -1), 1e-9)
        assertEquals(1150.0, StepMath.nextFrequency(1122.5, +1), 1e-9)
        assertEquals(1100.0, StepMath.nextFrequency(1122.5, -1), 1e-9)
    }

    @Test
    fun frequenciesStayInRange() {
        assertEquals(20f, StepMath.next(20f, 20f..60f, 1f, -1, musical = true), 1e-4f)
        assertEquals(60f, StepMath.next(60f, 20f..60f, 1f, +1, musical = true), 1e-4f)
    }

    @Test
    fun multipleStepsAtOnce() {
        assertEquals(-4f, StepMath.next(-6f, -12f..0f, 0.5f, +1, musical = false, times = 4), 1e-4f)
        // Two ladder steps up from 1000 Hz (50 Hz band).
        assertEquals(1100f, StepMath.next(1000f, 1000f..8000f, 10f, +1, musical = true, times = 2), 1e-4f)
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
