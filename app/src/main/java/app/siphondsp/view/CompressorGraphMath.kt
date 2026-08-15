package app.siphondsp.view

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Pure transfer-curve and gesture math for the low-band compressor editor, shared by
 * [NativeBmwCompressorView] and its tests. No Android imports.
 */
object CompressorGraphMath {
    enum class DragMode { THRESHOLD, KNEE, RATIO }

    /** Soft-knee compressor transfer function; mirrors NativeBmwDspProcessor's gain computer. */
    fun outputFor(
        inputDb: Float,
        thresholdDb: Float,
        ratio: Float,
        kneeDb: Float,
        makeupDb: Float,
        compressorEnabled: Boolean,
    ): Float {
        if (!compressorEnabled) return inputDb
        val halfKnee = kneeDb * .5f
        val compressed = when {
            kneeDb <= 0f && inputDb <= thresholdDb -> inputDb
            kneeDb <= 0f -> thresholdDb + (inputDb - thresholdDb) / ratio
            inputDb < thresholdDb - halfKnee -> inputDb
            inputDb > thresholdDb + halfKnee -> thresholdDb + (inputDb - thresholdDb) / ratio
            else -> {
                val distance = inputDb - thresholdDb + halfKnee
                inputDb + (1f / ratio - 1f) * distance * distance / (2f * kneeDb)
            }
        }
        return compressed + makeupDb
    }

    /** The point on the curve where the knee's soft region ends and the straight ratio segment begins. */
    fun kneeHandlePoint(
        thresholdDb: Float,
        ratio: Float,
        kneeDb: Float,
        makeupDb: Float,
        compressorEnabled: Boolean,
    ): Pair<Float, Float> {
        val inputDb = thresholdDb + kneeDb * .5f
        val outputDb = outputFor(inputDb, thresholdDb, ratio, kneeDb, makeupDb, compressorEnabled)
        return inputDb to outputDb
    }

    /**
     * Threshold is checked first (largest radius, matches prior behavior exactly), then knee
     * (smaller radius, so it can only claim area that used to fall through to "ratio anywhere"),
     * then ratio -- but only within [curveRadiusPx] of the curve itself, not anywhere on the
     * graph. Returns null when the touch is far from all three (empty graph space), so the
     * caller can decline the gesture instead of always consuming it -- otherwise the view's
     * touch handling permanently blocks a parent ViewPager2 from ever seeing a swipe that starts
     * on the graph, even in blank areas nowhere near any control.
     */
    fun pickDragMode(
        touchX: Float,
        touchY: Float,
        thresholdX: Float,
        thresholdY: Float,
        kneeX: Float,
        kneeY: Float,
        curveY: Float,
        thresholdRadiusPx: Float,
        kneeRadiusPx: Float,
        curveRadiusPx: Float,
    ): DragMode? {
        if (hypot(touchX - thresholdX, touchY - thresholdY) < thresholdRadiusPx) return DragMode.THRESHOLD
        if (hypot(touchX - kneeX, touchY - kneeY) < kneeRadiusPx) return DragMode.KNEE
        if (abs(touchY - curveY) < curveRadiusPx) return DragMode.RATIO
        return null
    }

    /** Dragged input position determines knee half-width; quantized to 1dB, matching the knee slider's step. */
    fun kneeFromDrag(draggedInputDb: Float, thresholdDb: Float): Float {
        val halfKnee = (draggedInputDb - thresholdDb).coerceAtLeast(0f)
        return (halfKnee * 2f).roundToInt().toFloat().coerceIn(0f, 12f)
    }

    /** Quantized to 0.1, matching the ratio slider's step. Caller guarantees outputDb > thresholdDb. */
    fun ratioFromDrag(inputDb: Float, outputDb: Float, thresholdDb: Float): Float {
        val raw = ((inputDb - thresholdDb) / (outputDb - thresholdDb)).coerceIn(1f, 10f)
        return (raw * 10f).roundToInt() / 10f
    }
}
