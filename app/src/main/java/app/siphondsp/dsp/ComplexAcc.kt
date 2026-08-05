package app.siphondsp.dsp

import kotlin.math.atan2
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Allocation-free mutable complex number used to accumulate a cascade's H(e^jw)
 * response at a single frequency point without boxing.
 */
internal class ComplexAcc {
    var re: Double = 1.0
    var im: Double = 0.0

    fun setUnity() {
        re = 1.0
        im = 0.0
    }

    fun setZero() {
        re = 0.0
        im = 0.0
    }

    fun setFrom(other: ComplexAcc) {
        re = other.re
        im = other.im
    }

    /** Multiplies this by (otherRe, otherIm) in place. */
    fun mul(otherRe: Double, otherIm: Double) {
        val newRe = re * otherRe - im * otherIm
        val newIm = re * otherIm + im * otherRe
        re = newRe
        im = newIm
    }

    /** Divides this by (denRe, denIm) in place; zeroes on a near-singular denominator. */
    fun div(denRe: Double, denIm: Double) {
        val d = denRe * denRe + denIm * denIm
        if (d <= 1e-24) {
            re = 0.0
            im = 0.0
            return
        }
        val newRe = (re * denRe + im * denIm) / d
        val newIm = (im * denRe - re * denIm) / d
        re = newRe
        im = newIm
    }

    /** Real scalar gain (e.g. headroom, branch gain, polarity invert via -1.0). */
    fun scale(gain: Double) {
        re *= gain
        im *= gain
    }

    fun addFrom(other: ComplexAcc) {
        re += other.re
        im += other.im
    }

    fun magnitude(): Double = sqrt(re * re + im * im)

    /** Wrapped phase in radians, (-pi, pi]. Zero re/im (fully muted point) reads as 0. */
    fun phase(): Double = if (re == 0.0 && im == 0.0) 0.0 else atan2(im, re)

    fun magnitudeDb(floorDb: Double = -120.0): Double {
        val amplitude = magnitude()
        return if (amplitude <= 1e-9) floorDb else 20.0 * log10(amplitude)
    }
}
