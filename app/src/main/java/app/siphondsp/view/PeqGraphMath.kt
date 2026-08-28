package app.siphondsp.view

import kotlin.math.exp
import kotlin.math.ln

object PeqGraphMath {
    const val MIN_FREQUENCY = 20.0
    const val MAX_FREQUENCY = 20_000.0
    // Asymmetric on purpose: real-world tunes routinely run 8-10 dB of negative headroom, so the
    // useful action is well below 0 dB. A -24..+6 window keeps those curves off the floor while
    // still showing the occasional boost, instead of the old symmetric +-18 that wasted its top
    // half and clipped everything interesting against the bottom edge.
    const val MIN_GAIN = -24.0
    const val MAX_GAIN = 6.0

    fun frequencyToFraction(
        frequency: Double,
        minFrequency: Double = MIN_FREQUENCY,
        maxFrequency: Double = MAX_FREQUENCY,
    ): Float {
        val clamped = frequency.coerceIn(minFrequency, maxFrequency)
        return ((ln(clamped) - ln(minFrequency)) / (ln(maxFrequency) - ln(minFrequency))).toFloat()
    }

    fun fractionToFrequency(
        fraction: Float,
        minFrequency: Double = MIN_FREQUENCY,
        maxFrequency: Double = MAX_FREQUENCY,
    ): Double {
        val position = fraction.coerceIn(0f, 1f)
        return exp(ln(minFrequency) + position * (ln(maxFrequency) - ln(minFrequency)))
    }

    fun gainToFraction(gain: Double, minGain: Double = MIN_GAIN, maxGain: Double = MAX_GAIN): Float =
        (1.0 - (gain.coerceIn(minGain, maxGain) - minGain) / (maxGain - minGain)).toFloat()

    /**
     * Maps a live spectrum magnitude (dB, on its own [floorDb]..[ceilingDb] scale) onto the
     * response graph's own gain axis, so the spectrum overlay sits at the correct visual
     * height relative to the response curves instead of using an unrelated full-view scale.
     */
    fun spectrumDbToGraphGain(
        spectrumDb: Float,
        floorDb: Float,
        ceilingDb: Float,
        minGain: Double = MIN_GAIN,
        maxGain: Double = MAX_GAIN,
    ): Double {
        val span = ceilingDb - floorDb
        if (span <= 0f) return minGain
        val normalized = ((spectrumDb - floorDb) / span).coerceIn(0f, 1f)
        return minGain + normalized * (maxGain - minGain)
    }

    fun fractionToGain(fraction: Float, minGain: Double = MIN_GAIN, maxGain: Double = MAX_GAIN): Double =
        maxGain - fraction.coerceIn(0f, 1f) * (maxGain - minGain)
}
