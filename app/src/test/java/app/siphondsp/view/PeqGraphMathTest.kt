package app.siphondsp.view

import app.siphondsp.model.ParametricEqBand
import app.siphondsp.model.ParametricEqChannel
import app.siphondsp.model.ParametricEqFilterType
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.UUID

class PeqGraphMathTest {
    @Test
    fun logarithmicFrequencyMappingRoundTrips() {
        listOf(20.0, 63.0, 1000.0, 6300.0, 20000.0).forEach { frequency ->
            val fraction = PeqGraphMath.frequencyToFraction(frequency)
            assertEquals(frequency, PeqGraphMath.fractionToFrequency(fraction), frequency * 0.00001)
        }
    }

    @Test
    fun dragChangesOnlyFrequencyAndGain() {
        val uuid = UUID.randomUUID()
        val original = ParametricEqBand(
            1000.0,
            3.0,
            2.4,
            ParametricEqFilterType.HIGH_SHELF,
            ParametricEqChannel.RIGHT,
            uuid,
        )

        val dragged = PeqGraphMath.draggedBand(original, 0.5f, 0.25f)

        assertEquals(PeqGraphMath.fractionToFrequency(0.5f), dragged.frequency, 0.001)
        assertEquals(9.0, dragged.gain, 0.001)
        assertEquals(original.q, dragged.q, 0.0)
        assertEquals(original.filterType, dragged.filterType)
        assertEquals(original.channel, dragged.channel)
        assertEquals(uuid, dragged.uuid)
    }

    @Test
    fun dragClampsToEditableBounds() {
        val original = ParametricEqBand(1000.0, 0.0, 1.41)
        val dragged = PeqGraphMath.draggedBand(original, 2f, -1f, 18_000.0)

        assertEquals(18_000.0, dragged.frequency, 0.001)
        assertEquals(18.0, dragged.gain, 0.001)
    }

    @Test
    fun spectrumDbToGraphGainMapsFloorAndCeilingToAxisEnds() {
        val floorGain = PeqGraphMath.spectrumDbToGraphGain(-80f, floorDb = -80f, ceilingDb = 0f)
        val ceilingGain = PeqGraphMath.spectrumDbToGraphGain(0f, floorDb = -80f, ceilingDb = 0f)

        assertEquals(PeqGraphMath.MIN_GAIN, floorGain, 1e-6)
        assertEquals(PeqGraphMath.MAX_GAIN, ceilingGain, 1e-6)
    }

    @Test
    fun spectrumDbToGraphGainClampsOutOfRangeInput() {
        val belowFloor = PeqGraphMath.spectrumDbToGraphGain(-120f, floorDb = -80f, ceilingDb = 0f)
        val aboveCeiling = PeqGraphMath.spectrumDbToGraphGain(10f, floorDb = -80f, ceilingDb = 0f)

        assertEquals(PeqGraphMath.MIN_GAIN, belowFloor, 1e-6)
        assertEquals(PeqGraphMath.MAX_GAIN, aboveCeiling, 1e-6)
    }
}
