package app.siphondsp.utils

import app.siphondsp.model.ParametricEqBand
import app.siphondsp.model.ParametricEqChannel
import app.siphondsp.model.ParametricEqFilterType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BiquadResponseGoldenTest {
    @Test
    fun peakingCenterMatchesRequestedBoostAndCut() {
        listOf(6.0, -9.0).forEach { gain ->
            val coefficients = BiquadUtils.computeCoefficients(
                1000.0, gain, 1.0, ParametricEqFilterType.PEAKING, 48_000.0
            )
            assertEquals(gain, BiquadUtils.magnitudeResponse(coefficients, 1000.0, 48_000.0), 0.0001)
        }
    }

    @Test
    fun shelfAsymptotesMatchExpectedSides() {
        val low = BiquadUtils.computeCoefficients(
            200.0, 6.0, 0.707, ParametricEqFilterType.LOW_SHELF, 48_000.0
        )
        val high = BiquadUtils.computeCoefficients(
            4000.0, -6.0, 0.707, ParametricEqFilterType.HIGH_SHELF, 48_000.0
        )
        assertEquals(6.0, BiquadUtils.magnitudeResponse(low, 20.0, 48_000.0), 0.1)
        assertEquals(0.0, BiquadUtils.magnitudeResponse(low, 10_000.0, 48_000.0), 0.1)
        assertEquals(0.0, BiquadUtils.magnitudeResponse(high, 100.0, 48_000.0), 0.1)
        assertEquals(-6.0, BiquadUtils.magnitudeResponse(high, 20_000.0, 48_000.0), 0.2)
    }

    @Test
    fun cascadesAndChannelTargetsRemainIsolated() {
        val bands = listOf(
            ParametricEqBand(1000.0, 6.0, 1.0, channel = ParametricEqChannel.LEFT),
            ParametricEqBand(1000.0, -3.0, 1.0, channel = ParametricEqChannel.LEFT_RIGHT),
        )
        val left = BiquadUtils.computeCombinedResponse(
            bands, 3, 1000.0, 1001.0, 48_000.0, ParametricEqChannel.LEFT
        )
        val right = BiquadUtils.computeCombinedResponse(
            bands, 3, 1000.0, 1001.0, 48_000.0, ParametricEqChannel.RIGHT
        )
        assertEquals(3.0, left.first().second, 0.001)
        assertEquals(-3.0, right.first().second, 0.001)
        assertTrue(
            BiquadUtils.computeCombinedResponse(
                emptyList(), channel = ParametricEqChannel.LEFT
            ).isEmpty()
        )
    }

    @Test
    fun responseGridRespectsSampleRateNyquist() {
        val response = BiquadUtils.computeCombinedResponse(
            listOf(ParametricEqBand(1000.0, 3.0, 1.0)),
            numPoints = 16,
            maxFreq = 20_000.0,
            sampleRate = 22_050.0,
        )
        assertTrue(response.last().first < 11_025.0)
    }
}
