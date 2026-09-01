package app.siphondsp.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sin

/**
 * Frequency-domain mirror of NativeBmwDspProcessor's pre-crossover multiband compressor tree
 * (rebuildMbc / processMbc / mbcBandGain). Kept in lockstep with the native math the same way
 * [BiquadCascade] mirrors the routing all-pass -- do not re-derive, transcribe.
 *
 * The tree is a serial 4-band Linkwitz-Riley 24 dB/oct split: split @ f0, then the high side
 * @ f1, then that high side @ f2. Each LP/HP is LR4 = two cascaded Butterworth (Q = 1/sqrt(2))
 * biquads. The two lower bands are then run through 2nd-order all-passes matching the later
 * crossovers (band 0 through the f1 and f2 all-passes, band 1 through the f2 all-pass) so the
 * four bands sum back to an overall all-pass -- i.e. flat magnitude. See [MBC identity] below.
 *
 * [MBC identity] For LR4, `LP4 + HP4` at fc is exactly the RBJ 2nd-order all-pass at fc with
 * Q = 1/sqrt(2). Substituting that three times collapses the compensated four-band sum to
 * `AP(f0) * AP(f1) * AP(f2)`, whose magnitude is 1 at every frequency.
 */
internal class MultibandCompressorTree(
    crossover0Hz: Double,
    crossover1Hz: Double,
    crossover2Hz: Double,
    private val sampleRate: Double,
) {
    /** Effective split frequencies after the native monotonic clamp in rebuildMbc(). */
    val f0: Double
    val f1: Double
    val f2: Double

    // One cascade per band; the product of its sections is that band's transfer function.
    private val band0: BiquadCascade
    private val band1: BiquadCascade
    private val band2: BiquadCascade
    private val band3: BiquadCascade

    init {
        val ceiling = sampleRate * NYQUIST_FRACTION
        f0 = crossover0Hz.coerceIn(MIN_SPLIT_HZ, ceiling)
        f1 = crossover1Hz.coerceIn(f0 * SPLIT_SPACING, ceiling)
        f2 = crossover2Hz.coerceIn(f1 * SPLIT_SPACING, ceiling)

        // band 0 = LP4(f0), then the f1 + f2 all-pass compensators.
        band0 = BiquadCascade(4).apply {
            addLowPass(f0, BUTTERWORTH_Q, sampleRate)
            addLowPass(f0, BUTTERWORTH_Q, sampleRate)
            addAllPass(enabled = true, secondOrder = true, frequencyHz = f1, q = BUTTERWORTH_Q, sampleRate = sampleRate)
            addAllPass(enabled = true, secondOrder = true, frequencyHz = f2, q = BUTTERWORTH_Q, sampleRate = sampleRate)
        }
        // band 1 = HP4(f0) -> LP4(f1), then the f2 all-pass compensator.
        band1 = BiquadCascade(5).apply {
            addHighPass(f0, BUTTERWORTH_Q, sampleRate)
            addHighPass(f0, BUTTERWORTH_Q, sampleRate)
            addLowPass(f1, BUTTERWORTH_Q, sampleRate)
            addLowPass(f1, BUTTERWORTH_Q, sampleRate)
            addAllPass(enabled = true, secondOrder = true, frequencyHz = f2, q = BUTTERWORTH_Q, sampleRate = sampleRate)
        }
        // band 2 = HP4(f0) -> HP4(f1) -> LP4(f2).
        band2 = BiquadCascade(6).apply {
            addHighPass(f0, BUTTERWORTH_Q, sampleRate)
            addHighPass(f0, BUTTERWORTH_Q, sampleRate)
            addHighPass(f1, BUTTERWORTH_Q, sampleRate)
            addHighPass(f1, BUTTERWORTH_Q, sampleRate)
            addLowPass(f2, BUTTERWORTH_Q, sampleRate)
            addLowPass(f2, BUTTERWORTH_Q, sampleRate)
        }
        // band 3 = HP4(f0) -> HP4(f1) -> HP4(f2).
        band3 = BiquadCascade(6).apply {
            addHighPass(f0, BUTTERWORTH_Q, sampleRate)
            addHighPass(f0, BUTTERWORTH_Q, sampleRate)
            addHighPass(f1, BUTTERWORTH_Q, sampleRate)
            addHighPass(f1, BUTTERWORTH_Q, sampleRate)
            addHighPass(f2, BUTTERWORTH_Q, sampleRate)
            addHighPass(f2, BUTTERWORTH_Q, sampleRate)
        }
    }

    /**
     * Magnitude, in dB, of the summed four-band output at [frequencyHz] when every band passes
     * through at unity gain (all compressors idle). Should read ~0 dB everywhere -- that is the
     * flat-reconstruction property the all-pass compensation exists to provide.
     */
    fun reconstructionMagnitudeDbAt(frequencyHz: Double): Double {
        val w = 2.0 * PI * frequencyHz / sampleRate
        val cosW = cos(w)
        val sinW = sin(w)
        val cos2W = 2.0 * cosW * cosW - 1.0
        val sin2W = 2.0 * sinW * cosW

        val total = ComplexAcc().apply { setZero() }
        val scratch = ComplexAcc()
        for (band in listOf(band0, band1, band2, band3)) {
            scratch.setUnity()
            band.accumulate(cosW, sinW, cos2W, sin2W, scratch)
            total.addFrom(scratch)
        }
        return total.magnitudeDb()
    }

    companion object {
        const val BUTTERWORTH_Q = 0.70710678
        private const val MIN_SPLIT_HZ = 20.0
        private const val SPLIT_SPACING = 1.05
        private const val NYQUIST_FRACTION = 0.45

        /**
         * Mirror of NativeBmwDspProcessor::mbcBandGain's static gain computer -- the smoothed
         * ballistics aren't reproduced (they need a time-domain run), just the instantaneous
         * target gain reduction in dB (<= 0) for a detector level of [detectorDb].
         */
        fun targetGainReductionDb(detectorDb: Double, thresholdDb: Double, ratio: Double, kneeDb: Double): Double {
            val over = detectorDb - thresholdDb
            val slope = 1.0 - 1.0 / max(1.001, ratio)
            val halfKnee = kneeDb * 0.5
            return when {
                kneeDb > 0.0 -> when {
                    over >= halfKnee -> -over * slope
                    over > -halfKnee -> {
                        val x = over + halfKnee
                        -slope * x * x / (2.0 * kneeDb)
                    }
                    else -> 0.0
                }
                over > 0.0 -> -over * slope
                else -> 0.0
            }
        }

        /** One-pole smoothing coefficient `1 - exp(-1 / (tauMs/1000 * sr))`, as in rebuildMbc(). */
        fun onePoleMix(tauMs: Double, sampleRate: Double): Double =
            1.0 - exp(-1.0 / (max(1.0, tauMs) / 1000.0 * sampleRate))
    }
}
