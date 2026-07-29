package app.siphondsp.view

import org.junit.Assert.assertEquals
import org.junit.Test

class CompressorGraphMathTest {
    @Test
    fun outputForBelowThresholdIsUnityPlusMakeup() {
        val output = CompressorGraphMath.outputFor(
            inputDb = -30f, thresholdDb = -12f, ratio = 4f, kneeDb = 0f, makeupDb = 1.5f,
            compressorEnabled = true,
        )
        assertEquals(-30f + 1.5f, output, 1e-4f)
    }

    @Test
    fun outputForAboveThresholdAppliesRatio() {
        val output = CompressorGraphMath.outputFor(
            inputDb = 0f, thresholdDb = -12f, ratio = 4f, kneeDb = 0f, makeupDb = 0f,
            compressorEnabled = true,
        )
        // 12dB over threshold, ratio 4:1 -> 3dB over threshold at the output.
        assertEquals(-9f, output, 1e-4f)
    }

    @Test
    fun outputForDisabledCompressorIsPassthrough() {
        val output = CompressorGraphMath.outputFor(
            inputDb = 3f, thresholdDb = -12f, ratio = 4f, kneeDb = 6f, makeupDb = 2f,
            compressorEnabled = false,
        )
        assertEquals(3f, output, 1e-4f)
    }

    @Test
    fun outputForIsContinuousAcrossKneeBoundaries() {
        val thresholdDb = -12f
        val kneeDb = 6f
        val ratio = 3f
        val halfKnee = kneeDb * .5f

        val justInside = CompressorGraphMath.outputFor(
            thresholdDb + halfKnee - 0.01f, thresholdDb, ratio, kneeDb, 0f, true,
        )
        val justOutside = CompressorGraphMath.outputFor(
            thresholdDb + halfKnee + 0.01f, thresholdDb, ratio, kneeDb, 0f, true,
        )
        assertEquals(justInside, justOutside, 0.01f)
    }

    @Test
    fun kneeHandlePointSitsAtUpperKneeBoundary() {
        val (inputDb, _) = CompressorGraphMath.kneeHandlePoint(
            thresholdDb = -12f, ratio = 4f, kneeDb = 6f, makeupDb = 0f, compressorEnabled = true,
        )
        assertEquals(-12f + 3f, inputDb, 1e-4f)
    }

    @Test
    fun kneeHandlePointOutputMatchesTransferFunction() {
        val thresholdDb = -12f
        val kneeDb = 6f
        val ratio = 4f
        val makeupDb = 1f
        val (inputDb, outputDb) = CompressorGraphMath.kneeHandlePoint(
            thresholdDb, ratio, kneeDb, makeupDb, compressorEnabled = true,
        )
        val expected = CompressorGraphMath.outputFor(inputDb, thresholdDb, ratio, kneeDb, makeupDb, true)
        assertEquals(expected, outputDb, 1e-4f)
    }

    @Test
    fun pickDragModePrefersThresholdOverKneeWhenBothInRange() {
        val mode = CompressorGraphMath.pickDragMode(
            touchX = 100f, touchY = 100f,
            thresholdX = 100f, thresholdY = 100f,
            kneeX = 105f, kneeY = 105f,
            thresholdRadiusPx = 34f, kneeRadiusPx = 26f,
        )
        assertEquals(CompressorGraphMath.DragMode.THRESHOLD, mode)
    }

    @Test
    fun pickDragModeFallsThroughToKnee() {
        val mode = CompressorGraphMath.pickDragMode(
            touchX = 200f, touchY = 200f,
            thresholdX = 100f, thresholdY = 100f,
            kneeX = 205f, kneeY = 205f,
            thresholdRadiusPx = 34f, kneeRadiusPx = 26f,
        )
        assertEquals(CompressorGraphMath.DragMode.KNEE, mode)
    }

    @Test
    fun pickDragModeFallsThroughToRatioWhenNeitherHandleIsClose() {
        val mode = CompressorGraphMath.pickDragMode(
            touchX = 500f, touchY = 500f,
            thresholdX = 100f, thresholdY = 100f,
            kneeX = 150f, kneeY = 150f,
            thresholdRadiusPx = 34f, kneeRadiusPx = 26f,
        )
        assertEquals(CompressorGraphMath.DragMode.RATIO, mode)
    }

    @Test
    fun kneeFromDragQuantizesToOneDbAndClamps() {
        assertEquals(4f, CompressorGraphMath.kneeFromDrag(draggedInputDb = -10f, thresholdDb = -12f), 1e-4f)
        assertEquals(0f, CompressorGraphMath.kneeFromDrag(draggedInputDb = -20f, thresholdDb = -12f), 1e-4f)
        assertEquals(12f, CompressorGraphMath.kneeFromDrag(draggedInputDb = 10f, thresholdDb = -12f), 1e-4f)
    }

    @Test
    fun ratioFromDragQuantizesToOneTenthAndClamps() {
        val ratio = CompressorGraphMath.ratioFromDrag(inputDb = 0f, outputDb = -9f, thresholdDb = -12f)
        assertEquals(4f, ratio, 1e-4f)

        val clampedLow = CompressorGraphMath.ratioFromDrag(inputDb = -11f, outputDb = -6f, thresholdDb = -12f)
        assertEquals(1f, clampedLow, 1e-4f)

        val clampedHigh = CompressorGraphMath.ratioFromDrag(inputDb = 6f, outputDb = -11.9f, thresholdDb = -12f)
        assertEquals(10f, clampedHigh, 1e-4f)
    }
}
