package app.siphondsp.dsp

import app.siphondsp.model.ParametricEqBand
import app.siphondsp.utils.BiquadUtils
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * A cascade of normalised biquad sections (a0 already divided out), stored as a flat
 * DoubleArray of [b0,b1,b2,a1,a2] per section so building/evaluating a cascade never
 * allocates per-section objects.
 *
 * Coefficient formulas are direct transcriptions of NativeBmwDspProcessor.cpp's
 * makeLowPass/makeHighPass/makeOnePoleLow/makeLowShelf/makeHighShelf (and
 * BiquadUtils.computeCoefficients for PEQ bands) -- keep these in lockstep with the
 * native math, do not re-derive.
 *
 * All coefficients are normalized by a0 to simplify per-sample processing.
 * Stability is guaranteed for frequencies in the range [20 Hz, Nyquist/2).
 */
internal class BiquadCascade(maxSections: Int) {
    private val coeffs = DoubleArray(maxSections * 5)
    var sectionCount = 0
        private set

    /**
     * Clears all sections and resets the section count.
     */
    fun clear() {
        sectionCount = 0
    }

    /**
     * Adds a normalized biquad section to the cascade.
     *
     * @param b0 Normalized numerator coefficient (b0/a0)
     * @param b1 Normalized numerator coefficient (b1/a0)
     * @param b2 Normalized numerator coefficient (b2/a0)
     * @param a1 Normalized denominator coefficient (-a1/a0)
     * @param a2 Normalized denominator coefficient (-a2/a0)
     */
    private fun addNormalised(b0: Double, b1: Double, b2: Double, a1: Double, a2: Double) {
        val base = sectionCount * 5
        coeffs[base] = b0
        coeffs[base + 1] = b1
        coeffs[base + 2] = b2
        coeffs[base + 3] = a1
        coeffs[base + 4] = a2
        sectionCount++
    }

    /**
     * Adds a parametric EQ band with the specified frequency, gain, and Q factor.
     *
     * Mirrors JamesDspLocalEngine/NativeBmwDspProcessor::makePeq via the existing
     * BiquadUtils formulas.
     *
     * @param band The parametric EQ band model (frequency, gain, Q, filter type)
     * @param sampleRate Sample rate in Hz (e.g., 48000)
     *
     * @see BiquadUtils.computeCoefficients
     */
    fun addPeqBand(band: ParametricEqBand, sampleRate: Double) {
        val c = BiquadUtils.computeCoefficients(band.frequency, band.gain, band.q, band.filterType, sampleRate)
        addNormalised(c.b0 / c.a0, c.b1 / c.a0, c.b2 / c.a0, c.a1 / c.a0, c.a2 / c.a0)
    }

    /**
     * Adds a second-order low-pass filter (Butterworth).
     *
     * Mirrors NativeBmwDspProcessor::makeLowPass.
     *
     * @param fc Cutoff frequency in Hz (clamped to [20, Nyquist/2))
     * @param q Q factor (typical: 0.707 for Butterworth)
     * @param sampleRate Sample rate in Hz
     *
     * Example: addLowPass(1000.0, 0.707, 48000.0) for a 1 kHz Butterworth LP at 48 kHz
     */
    fun addLowPass(fc: Double, q: Double, sampleRate: Double) {
        val w = 2.0 * PI * fc.coerceIn(FREQ_MIN, sampleRate * NYQUIST_FRACTION) / sampleRate
        val c = cos(w)
        val s = sin(w)
        val alpha = s / (2.0 * q)
        val d = 1.0 + alpha
        val b0 = ((1.0 - c) * .5) / d
        addNormalised(b0, (1.0 - c) / d, b0, (-2.0 * c) / d, (1.0 - alpha) / d)
    }

    /**
     * Adds a second-order high-pass filter (Butterworth).
     *
     * Mirrors NativeBmwDspProcessor::makeHighPass.
     *
     * @param fc Cutoff frequency in Hz (clamped to [20, Nyquist/2))
     * @param q Q factor (typical: 0.707 for Butterworth)
     * @param sampleRate Sample rate in Hz
     *
     * Example: addHighPass(100.0, 0.707, 48000.0) for a 100 Hz Butterworth HP at 48 kHz
     */
    fun addHighPass(fc: Double, q: Double, sampleRate: Double) {
        val w = 2.0 * PI * fc.coerceIn(FREQ_MIN, sampleRate * NYQUIST_FRACTION) / sampleRate
        val c = cos(w)
        val s = sin(w)
        val alpha = s / (2.0 * q)
        val d = 1.0 + alpha
        val b0 = ((1.0 + c) * .5) / d
        addNormalised(b0, (-(1.0 + c)) / d, b0, (-2.0 * c) / d, (1.0 - alpha) / d)
    }

    /**
     * Adds a first-order low-pass filter (one-pole).
     *
     * Mirrors NativeBmwDspProcessor::makeOnePoleLow / OnePole::run.
     * Stored as a biquad section with b2=a2=0 for compatibility.
     *
     * @param fc Cutoff frequency in Hz (clamped to [20, Nyquist/2))
     * @param sampleRate Sample rate in Hz
     *
     * Example: addOnePoleLow(10.0, 48000.0) for a 10 Hz one-pole LP (DC blocker)
     */
    fun addOnePoleLow(fc: Double, sampleRate: Double) {
        val k = tan(PI * fc.coerceIn(FREQ_MIN, sampleRate * NYQUIST_FRACTION) / sampleRate)
        val a0 = k / (k + 1.0)
        addNormalised(a0, a0, 0.0, (k - 1.0) / (k + 1.0), 0.0)
    }

    /**
     * Adds a low-shelf filter with the specified gain.
     *
     * Mirrors NativeBmwDspProcessor::makeLowShelf.
     *
     * @param fc Center frequency in Hz
     * @param gainDb Shelf gain in dB (positive = boost, negative = cut)
     * @param sampleRate Sample rate in Hz
     *
     * Example: addLowShelf(100.0, 6.0, 48000.0) for a +6 dB low-shelf at 100 Hz
     */
    fun addLowShelf(fc: Double, gainDb: Double, sampleRate: Double) = addShelf(fc, gainDb, sampleRate, high = false)

    /**
     * Adds a high-shelf filter with the specified gain.
     *
     * Mirrors NativeBmwDspProcessor::makeHighShelf.
     *
     * @param fc Center frequency in Hz
     * @param gainDb Shelf gain in dB (positive = boost, negative = cut)
     * @param sampleRate Sample rate in Hz
     *
     * Example: addHighShelf(8000.0, -3.0, 48000.0) for a -3 dB high-shelf at 8 kHz
     */
    fun addHighShelf(fc: Double, gainDb: Double, sampleRate: Double) = addShelf(fc, gainDb, sampleRate, high = true)

    /**
     * Internal shelf filter implementation.
     *
     * @param fc Center frequency in Hz
     * @param gainDb Shelf gain in dB
     * @param sampleRate Sample rate in Hz
     * @param high True for high-shelf, false for low-shelf
     */
    private fun addShelf(fc: Double, gainDb: Double, sampleRate: Double, high: Boolean) {
        val a = Math.pow(10.0, gainDb / 40.0)
        val w = 2.0 * PI * fc / sampleRate
        val c = cos(w)
        val s = sin(w)
        val alpha = s / (2.0 * BUTTERWORTH_Q)
        val rootA = sqrt(a)
        if (!high) {
            val inv = 1.0 / ((a + 1.0) + (a - 1.0) * c + 2.0 * rootA * alpha)
            addNormalised(
                a * ((a + 1.0) - (a - 1.0) * c + 2.0 * rootA * alpha) * inv,
                2.0 * a * ((a - 1.0) - (a + 1.0) * c) * inv,
                a * ((a + 1.0) - (a - 1.0) * c - 2.0 * rootA * alpha) * inv,
                -2.0 * ((a - 1.0) + (a + 1.0) * c) * inv,
                ((a + 1.0) + (a - 1.0) * c - 2.0 * rootA * alpha) * inv,
            )
        } else {
            val inv = 1.0 / ((a + 1.0) - (a - 1.0) * c + 2.0 * rootA * alpha)
            addNormalised(
                a * ((a + 1.0) + (a - 1.0) * c + 2.0 * rootA * alpha) * inv,
                -2.0 * a * ((a - 1.0) + (a + 1.0) * c) * inv,
                a * ((a + 1.0) + (a - 1.0) * c - 2.0 * rootA * alpha) * inv,
                2.0 * ((a - 1.0) - (a + 1.0) * c) * inv,
                ((a + 1.0) - (a - 1.0) * c - 2.0 * rootA * alpha) * inv,
            )
        }
    }

    /**
     * Adds an all-pass filter for phase correction.
     *
     * Mirrors NativeBmwRouting::AllPassSection::rebuild. An all-pass section that isn't
     * enabled (or has invalid parameters) contributes unity gain/zero phase, which omitting
     * it from the cascade already achieves.
     *
     * @param enabled Whether to add the all-pass (if false, no section is added)
     * @param secondOrder True for second-order, false for first-order
     * @param frequencyHz Center frequency in Hz (must be in [20, Nyquist/2) if enabled)
     * @param q Q factor (must be in [0.1, 30.0] if enabled)
     * @param sampleRate Sample rate in Hz
     *
     * Example: addAllPass(true, true, 1000.0, 10.0, 48000.0) for a second-order all-pass at 1 kHz
     */
    fun addAllPass(enabled: Boolean, secondOrder: Boolean, frequencyHz: Double, q: Double, sampleRate: Double) {
        if (!enabled || !frequencyHz.isFinite() || frequencyHz < FREQ_MIN || frequencyHz >= sampleRate * NYQUIST_FRACTION ||
            !q.isFinite() || q < Q_MIN || q > Q_MAX
        ) return
        val w = 2.0 * PI * frequencyHz / sampleRate
        if (!secondOrder) {
            val t = tan(w * .5)
            val denom = t + 1.0
            if (denom == 0.0) return
            val a = (t - 1.0) / denom
            if (!a.isFinite()) return
            addNormalised(a, 1.0, 0.0, a, 0.0)
            return
        }
        val c = cos(w)
        val s = sin(w)
        val alpha = s / (2.0 * q)
        val a0 = 1.0 + alpha
        if (a0 == 0.0) return
        val b0 = (1.0 - alpha) / a0
        val b1 = (-2.0 * c) / a0
        if (!b0.isFinite() || !b1.isFinite()) return
        addNormalised(b0, b1, 1.0, b1, b0)
    }

    /**
     * Adds a DC blocker (high-pass filter at 10 Hz).
     *
     * Mirrors NativeBmwDspProcessor::processChannelInput's DC blocker:
     * H(z) = (1 - z^-1) / (1 - dcR*z^-1), where dcR = exp(-2*pi*cutoffHz/sampleRate).
     *
     * @param cutoffHz Cutoff frequency in Hz (typically 10 Hz for audio DC blocking)
     * @param sampleRate Sample rate in Hz
     */
    fun addDcBlocker(cutoffHz: Double, sampleRate: Double) {
        val dcR = exp(-2.0 * PI * cutoffHz / sampleRate)
        addNormalised(1.0, -1.0, 0.0, -dcR, 0.0)
    }

    /**
     * Multiplies [acc] by this cascade's H(e^jw) at the point described by [cosW]/[sinW]
     * (angle w = 2*pi*f/sr) and [cos2W]/[sin2W] (double angle, for z^-2).
     * z^-1 = cos(w) - j*sin(w), matching NativeBmwDspResponseView's unitDelay convention.
     *
     * Used for frequency-response graph computation.
     *
     * @param cosW cos(2π*f/sr) for current frequency
     * @param sinW sin(2π*f/sr) for current frequency
     * @param cos2W cos(4π*f/sr) for double-angle
     * @param sin2W sin(4π*f/sr) for double-angle
     * @param acc Complex accumulator to multiply in-place
     */
    fun accumulate(cosW: Double, sinW: Double, cos2W: Double, sin2W: Double, acc: ComplexAcc) {
        val z1Re = cosW
        val z1Im = -sinW
        val z2Re = cos2W
        val z2Im = -sin2W
        for (i in 0 until sectionCount) {
            val base = i * 5
            val b0 = coeffs[base]
            val b1 = coeffs[base + 1]
            val b2 = coeffs[base + 2]
            val a1 = coeffs[base + 3]
            val a2 = coeffs[base + 4]
            val numRe = b0 + b1 * z1Re + b2 * z2Re
            val numIm = b1 * z1Im + b2 * z2Im
            val denRe = 1.0 + a1 * z1Re + a2 * z2Re
            val denIm = a1 * z1Im + a2 * z2Im
            acc.mul(numRe, numIm)
            acc.div(denRe, denIm)
        }
    }

    companion object {
        /** Q factor for Butterworth filters (1/√2) */
        private const val BUTTERWORTH_Q = .7071067812
        
        /** Minimum frequency for stability (Hz) */
        private const val FREQ_MIN = 20.0
        
        /** Maximum frequency as fraction of Nyquist */
        private const val NYQUIST_FRACTION = .49
        
        /** Minimum Q factor for all-pass filters */
        private const val Q_MIN = 0.1
        
        /** Maximum Q factor for all-pass filters */
        private const val Q_MAX = 30.0
    }
}
