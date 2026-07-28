package app.siphondsp.view

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.round

class BmwGraphGestureMathTest {
    @Test
    fun draggedTiltFrequencyChangesOnlyFrequency() {
        val original = BmwGraphGestureMath.TiltValues(frequencyHz = 550f, amountDb = 3f)

        val dragged = BmwGraphGestureMath.draggedTiltFrequency(original, xFraction = 0.5f)

        assertEquals(original.amountDb, dragged.amountDb, 0f)
        val expected = round(
            PeqGraphMath.fractionToFrequency(0.5f, BmwGraphGestureMath.TILT_MIN_FREQ, BmwGraphGestureMath.TILT_MAX_FREQ)
        ).toFloat()
        assertEquals(expected, dragged.frequencyHz, 1e-3f)
    }

    @Test
    fun draggedTiltAmountChangesOnlyAmount() {
        val original = BmwGraphGestureMath.TiltValues(frequencyHz = 550f, amountDb = 3f)

        val dragged = BmwGraphGestureMath.draggedTiltAmount(original, yFraction = 0f)

        assertEquals(original.frequencyHz, dragged.frequencyHz, 0f)
        // yFraction=0 maps to the top of the axis, i.e. the maximum tilt amount.
        assertEquals(BmwGraphGestureMath.TILT_MAX_AMOUNT.toFloat(), dragged.amountDb, 1e-4f)
    }

    @Test
    fun draggedTiltFrequencyClampsToNativeRange() {
        val original = BmwGraphGestureMath.TiltValues(frequencyHz = 550f, amountDb = 0f)

        val atMin = BmwGraphGestureMath.draggedTiltFrequency(original, xFraction = -5f)
        val atMax = BmwGraphGestureMath.draggedTiltFrequency(original, xFraction = 5f)

        assertEquals(BmwGraphGestureMath.TILT_MIN_FREQ.toFloat(), atMin.frequencyHz, 1e-3f)
        assertEquals(BmwGraphGestureMath.TILT_MAX_FREQ.toFloat(), atMax.frequencyHz, 1e-3f)
    }

    @Test
    fun draggedTiltAmountClampsToNativeRange() {
        val original = BmwGraphGestureMath.TiltValues(frequencyHz = 550f, amountDb = 0f)

        val atMax = BmwGraphGestureMath.draggedTiltAmount(original, yFraction = -5f)
        val atMin = BmwGraphGestureMath.draggedTiltAmount(original, yFraction = 5f)

        assertEquals(BmwGraphGestureMath.TILT_MAX_AMOUNT.toFloat(), atMax.amountDb, 1e-3f)
        assertEquals(BmwGraphGestureMath.TILT_MIN_AMOUNT.toFloat(), atMin.amountDb, 1e-3f)
    }

    @Test
    fun draggedTiltAmountQuantizesToOneTenthDb() {
        val original = BmwGraphGestureMath.TiltValues(frequencyHz = 550f, amountDb = 0f)

        val dragged = BmwGraphGestureMath.draggedTiltAmount(original, yFraction = 0.37f)

        val scaled = dragged.amountDb * 10f
        assertEquals(Math.round(scaled).toFloat(), scaled, 1e-3f)
    }

    @Test
    fun draggedTiltFrequencyQuantizesToOneHertz() {
        val original = BmwGraphGestureMath.TiltValues(frequencyHz = 550f, amountDb = 0f)

        val dragged = BmwGraphGestureMath.draggedTiltFrequency(original, xFraction = 0.63f)

        assertEquals(Math.round(dragged.frequencyHz).toFloat(), dragged.frequencyHz, 1e-3f)
    }

    @Test
    fun tiltAmountToFractionAndFrequencyToFractionAreMonotonic() {
        val lowFreqFraction = BmwGraphGestureMath.tiltFrequencyToFraction(BmwGraphGestureMath.TILT_MIN_FREQ.toFloat())
        val highFreqFraction = BmwGraphGestureMath.tiltFrequencyToFraction(BmwGraphGestureMath.TILT_MAX_FREQ.toFloat())
        assertEquals(0f, lowFreqFraction, 1e-4f)
        assertEquals(1f, highFreqFraction, 1e-4f)

        val lowAmountFraction = BmwGraphGestureMath.tiltAmountToFraction(BmwGraphGestureMath.TILT_MIN_AMOUNT.toFloat())
        val highAmountFraction = BmwGraphGestureMath.tiltAmountToFraction(BmwGraphGestureMath.TILT_MAX_AMOUNT.toFloat())
        // gainToFraction is inverted (0 at max, 1 at min) to match screen-Y conventions.
        assertEquals(1f, lowAmountFraction, 1e-4f)
        assertEquals(0f, highAmountFraction, 1e-4f)
    }
}
