package app.siphondsp.view

import kotlin.math.round

/**
 * Pure (no Android imports) drag-to-value math for the unified surface's tilt handles,
 * mirroring the log-frequency / linear-dB mapping [PeqGraphMath] already uses for PEQ
 * nodes, but scoped to the tilt stage's own ranges (matching NativeBmwDspProcessor's
 * `configure()` clamps: 200-2000Hz, -6..+6dB).
 */
object BmwGraphGestureMath {
    const val TILT_MIN_FREQ = 200.0
    const val TILT_MAX_FREQ = 2000.0
    const val TILT_MIN_AMOUNT = -6.0
    const val TILT_MAX_AMOUNT = 6.0

    data class TiltValues(val frequencyHz: Float, val amountDb: Float)

    fun tiltFrequencyToFraction(frequencyHz: Float): Float =
        PeqGraphMath.frequencyToFraction(frequencyHz.toDouble(), TILT_MIN_FREQ, TILT_MAX_FREQ)

    fun tiltAmountToFraction(amountDb: Float): Float =
        PeqGraphMath.gainToFraction(amountDb.toDouble(), TILT_MIN_AMOUNT, TILT_MAX_AMOUNT)

    /** Pivot handle drag: frequency only, 1Hz quantized. Amount is left untouched. */
    fun draggedTiltFrequency(original: TiltValues, xFraction: Float): TiltValues {
        val freq = PeqGraphMath.fractionToFrequency(xFraction, TILT_MIN_FREQ, TILT_MAX_FREQ)
        val quantized = round(freq).toFloat().coerceIn(TILT_MIN_FREQ.toFloat(), TILT_MAX_FREQ.toFloat())
        return original.copy(frequencyHz = quantized)
    }

    /** Amount handle drag: amount only, 0.1dB quantized. Frequency is left untouched. */
    fun draggedTiltAmount(original: TiltValues, yFraction: Float): TiltValues {
        val amount = PeqGraphMath.fractionToGain(yFraction, TILT_MIN_AMOUNT, TILT_MAX_AMOUNT)
        val quantized = (round(amount * 10.0) / 10.0).toFloat().coerceIn(TILT_MIN_AMOUNT.toFloat(), TILT_MAX_AMOUNT.toFloat())
        return original.copy(amountDb = quantized)
    }
}
