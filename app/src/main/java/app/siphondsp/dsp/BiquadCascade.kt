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
 */
internal class BiquadCascade(maxSections: Int) {
    private val coeffs = DoubleArray(maxSections * 5)
    var sectionCount = 0
        private set

    fun clear() {
        sectionCount = 0
    }

    private fun addNormalised(b0: Double, b1: Double, b2: Double, a1: Double, a2: Double) {
        val base = sectionCount * 5
        coeffs[base] = b0
        coeffs[base + 1] = b1
        coeffs[base + 2] = b2
        coeffs[base + 3] = a1
        coeffs[base + 4] = a2
        sectionCount++
    }

    /** Mirrors JamesDspLocalEngine/NativeBmwDspProcessor::makePeq via the existing BiquadUtils formulas. */
    fun addPeqBand(band: ParametricEqBand, sampleRate: Double) {
        val c = BiquadUtils.computeCoefficients(band.frequency, band.gain, band.q, band.filterType, sampleRate)
        addNormalised(c.b0 / c.a0, c.b1 / c.a0, c.b2 / c.a0, c.a1 / c.a0, c.a2 / c.a0)
    }

    /** NativeBmwDspProcessor::makeLowPass. */
    fun addLowPass(fc: Double, q: Double, sampleRate: Double) {
        val w = 2.0 * PI * fc.coerceIn(20.0, sampleRate * .49) / sampleRate
        val c = cos(w)
        val s = sin(w)
        val alpha = s / (2.0 * q)
        val d = 1.0 + alpha
        val b0 = ((1.0 - c) * .5) / d
        addNormalised(b0, (1.0 - c) / d, b0, (-2.0 * c) / d, (1.0 - alpha) / d)
    }

    /** NativeBmwDspProcessor::makeHighPass. */
    fun addHighPass(fc: Double, q: Double, sampleRate: Double) {
        val w = 2.0 * PI * fc.coerceIn(20.0, sampleRate * .49) / sampleRate
        val c = cos(w)
        val s = sin(w)
        val alpha = s / (2.0 * q)
        val d = 1.0 + alpha
        val b0 = ((1.0 + c) * .5) / d
        addNormalised(b0, (-(1.0 + c)) / d, b0, (-2.0 * c) / d, (1.0 - alpha) / d)
    }

    /**
     * NativeBmwDspProcessor::makeOnePoleLow / OnePole::run. This is a first-order
     * section (H(z) = a0*(1+z^-1) / (1+b1*z^-1)); stored with b2=a2=0 so the generic
     * biquad evaluator in [accumulate] handles it without special-casing.
     */
    fun addOnePoleLow(fc: Double, sampleRate: Double) {
        val k = tan(PI * fc.coerceIn(20.0, sampleRate * .49) / sampleRate)
        val a0 = k / (k + 1.0)
        addNormalised(a0, a0, 0.0, (k - 1.0) / (k + 1.0), 0.0)
    }

    /** NativeBmwDspProcessor::makeLowShelf. */
    fun addLowShelf(fc: Double, gainDb: Double, sampleRate: Double) = addShelf(fc, gainDb, sampleRate, high = false)

    /** NativeBmwDspProcessor::makeHighShelf. */
    fun addHighShelf(fc: Double, gainDb: Double, sampleRate: Double) = addShelf(fc, gainDb, sampleRate, high = true)

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
     * NativeBmwDspProcessor::processChannelInput's 10Hz DC blocker:
     * H(z) = (1 - z^-1) / (1 - dcR*z^-1), dcR = exp(-2*pi*cutoffHz/sampleRate).
     */
    fun addDcBlocker(cutoffHz: Double, sampleRate: Double) {
        val dcR = exp(-2.0 * PI * cutoffHz / sampleRate)
        addNormalised(1.0, -1.0, 0.0, -dcR, 0.0)
    }

    /** NativeBmwDspProcessor::makeAllPass. Invalid sections are rejected by the state/UI layer. */
    fun addAllPass(secondOrder: Boolean, frequencyHz: Double, q: Double, sampleRate: Double) {
        val w = 2.0 * PI * frequencyHz / sampleRate
        if (!secondOrder) {
            val a = (tan(w * .5) - 1.0) / (tan(w * .5) + 1.0)
            addNormalised(a, 1.0, 0.0, a, 0.0)
            return
        }
        val c = cos(w)
        val alpha = sin(w) / (2.0 * q)
        val d = 1.0 + alpha
        addNormalised((1.0 - alpha) / d, (-2.0 * c) / d, 1.0, (-2.0 * c) / d, (1.0 - alpha) / d)
    }

    /**
     * Multiplies [acc] by this cascade's H(e^jw) at the point described by [cosW]/[sinW]
     * (angle w = 2*pi*f/sr) and [cos2W]/[sin2W] (double angle, for z^-2). z^-1 = cos(w) -
     * j*sin(w), matching NativeBmwDspResponseView's unitDelay convention.
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
        private const val BUTTERWORTH_Q = .7071067812
    }
}
