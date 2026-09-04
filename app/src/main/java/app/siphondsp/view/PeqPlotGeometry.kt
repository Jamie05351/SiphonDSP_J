package app.siphondsp.view

/**
 * Pure coordinate mapping for [ParametricEqSurface]'s plot area: log-frequency across x, and
 * either the dB gain axis or a linear min..max axis (phase, group delay) down y.
 *
 * Built from the view's already-resolved plot rectangle plus its current [maximumFrequency];
 * every draw and hit-test call in the surface routes its x/y math through one of these. Lifted
 * verbatim out of ParametricEqSurface (plotLeft/Right/Top/Bottom + xForFrequency / yForGain /
 * yForRange / yForPhaseDeg / yForGroupDelayMs) so the mapping is unit-testable without a View.
 */
class PeqPlotGeometry(
    val left: Float,
    val right: Float,
    val top: Float,
    val bottom: Float,
    private val maximumFrequency: Double,
) {
    fun xForFrequency(frequency: Double): Float {
        val fraction = PeqGraphMath.frequencyToFraction(frequency, PeqGraphMath.MIN_FREQUENCY, maximumFrequency)
        return left + fraction * (right - left)
    }

    fun yForGain(gain: Double): Float {
        val fraction = PeqGraphMath.gainToFraction(gain)
        return top + fraction * (bottom - top)
    }

    fun yForRange(value: Double, min: Double, max: Double): Float {
        val clamped = value.coerceIn(min, max)
        val fraction = (max - clamped) / (max - min)
        return top + fraction.toFloat() * (bottom - top)
    }

    fun yForPhaseDeg(deg: Double): Float = yForRange(deg, PHASE_MIN_DEG, PHASE_MAX_DEG)

    fun yForGroupDelayMs(ms: Double): Float = yForRange(ms, GROUP_DELAY_MIN_MS, GROUP_DELAY_MAX_MS)

    companion object {
        const val PHASE_MIN_DEG = -180.0
        const val PHASE_MAX_DEG = 180.0
        const val GROUP_DELAY_MIN_MS = -2.0
        const val GROUP_DELAY_MAX_MS = 10.0
    }
}
