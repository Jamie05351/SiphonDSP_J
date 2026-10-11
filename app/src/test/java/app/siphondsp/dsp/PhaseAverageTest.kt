package app.siphondsp.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs

class PhaseAverageTest {
    private fun rad(deg: Double) = deg * PI / 180.0
    private fun deg(rad: Double) = rad * 180.0 / PI

    @Test
    fun phasesStraddlingTheWrapAverageToTheWrapNotZero() {
        // The arithmetic mean of +179 and -179 is 0 -- the opposite direction.
        assertEquals(180.0, abs(deg(averagePhaseRad(rad(179.0), rad(-179.0)))), 1e-9)
        assertEquals(-175.0, deg(averagePhaseRad(rad(-170.0), rad(180.0))), 1e-9)
    }

    @Test
    fun ordinaryPhasesAverageAsUsual() {
        assertEquals(20.0, deg(averagePhaseRad(rad(10.0), rad(30.0))), 1e-9)
        assertEquals(-45.0, deg(averagePhaseRad(rad(-90.0), rad(0.0))), 1e-9)
    }

    @Test
    fun nanIsSkippedAndOppositePhasesHaveNoMean() {
        assertEquals(1.0, averagePhaseRad(Double.NaN, 1.0), 0.0)
        assertEquals(1.0, averagePhaseRad(1.0, Double.NaN), 0.0)
        assertTrue(averagePhaseRad(Double.NaN, Double.NaN).isNaN())
        assertTrue(averagePhaseRad(rad(90.0), rad(-90.0)).isNaN())
    }
}
