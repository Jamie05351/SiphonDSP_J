package app.siphondsp.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.pow

class BaselineDelayStripperTest {
    private val delaySec = 0.003

    private fun measured(frequencies: DoubleArray, magnitude: DoubleArray = DoubleArray(frequencies.size)) =
        MeasuredResponse(
            frequencies,
            magnitude,
            DoubleArray(frequencies.size) {
                Math.toDegrees(BaselineDelayStripper.wrap(-2.0 * PI * frequencies[it] * delaySec))
            },
        )

    private fun uniform() = measured(DoubleArray(800) { 20.0 + 25.0 * it })

    private fun assertDelayRemoved(baseline: BaselineChannel) {
        assertEquals(delaySec, baseline.removedDelaySec, 1e-8)
        baseline.excessPhaseRad.forEach { assertEquals(0.0, it, 1e-5) }
    }

    @Test
    fun rejectsUniformGridWithMultipleDelayAliases() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            BaselineDelayStripper.strip(uniform())
        }
        assertTrue(error.message.orEmpty().contains("delayOverrideSec"))
    }

    @Test
    fun rejectsSearchWidthEqualToOneAliasPeriod() {
        assertThrows(IllegalArgumentException::class.java) {
            BaselineDelayStripper.strip(uniform(), minDelaySec = 0.0, maxDelaySec = 1.0 / 25.0)
        }
    }

    @Test
    fun explicitDelayOverrideRemovesDelayWithoutConstantPhaseOffset() {
        assertDelayRemoved(BaselineDelayStripper.strip(uniform(), delayOverrideSec = delaySec))
    }

    @Test
    fun narrowSearchPriorSelectsTheIntendedDelay() {
        assertDelayRemoved(BaselineDelayStripper.strip(uniform(), minDelaySec = 0.0, maxDelaySec = 0.010))
    }

    @Test
    fun checksOnlyPairsUsedByTheGatedFit() {
        val frequencies = DoubleArray(20) { if (it < 10) 20.0 + 25.0 * it else 300.0 + 31.0 * it }
        val magnitude = DoubleArray(20) { if (it < 10) 0.0 else -60.0 }
        assertThrows(IllegalArgumentException::class.java) {
            BaselineDelayStripper.strip(measured(frequencies, magnitude))
        }
    }

    @Test
    fun logarithmicGridStillEstimatesDelay() {
        val frequencies = DoubleArray(241) { 20.0 * 1000.0.pow(it / 240.0) }
        assertDelayRemoved(BaselineDelayStripper.strip(measured(frequencies)))
    }
}
