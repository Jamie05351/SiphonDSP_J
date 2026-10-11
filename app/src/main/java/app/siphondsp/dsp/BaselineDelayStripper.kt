package app.siphondsp.dsp

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt

/** Raw measured channel as imported (e.g. REW text export). Phase is WRAPPED, in degrees. */
class MeasuredResponse(
    val freqHz: DoubleArray,
    val magDb: DoubleArray,
    val phaseDeg: DoubleArray,
) {
    init {
        require(freqHz.size == magDb.size && freqHz.size == phaseDeg.size) { "array size mismatch" }
        require(freqHz.size >= 8) { "too few points" }
        for (i in 1 until freqHz.size) require(freqHz[i] > freqHz[i - 1]) { "freq must be ascending" }
    }
}

/**
 * Position-independent baseline: magnitude + excess phase with ALL arrival delay removed.
 * Timing is owned by the geometry page + delay controls, never by this data.
 */
class BaselineChannel(
    val freqHz: DoubleArray,
    val magDb: DoubleArray,
    /** Unwrapped, smooth, delay-free. Safe to interpolate. */
    val excessPhaseRad: DoubleArray,
    /** Diagnostic only (log/debug). Never feed this into timing. */
    val removedDelaySec: Double,
)

object BaselineDelayStripper {

    private const val TWO_PI = 2.0 * PI

    /**
     * @param gateDb        bins more than this far below the channel's peak are ignored for the
     *                      delay fit (out-of-band phase is noise).
     * @param minDelaySec   allow slightly negative values in case the capture was over-compensated.
     * @param maxDelaySec   search ceiling; covers flight time + typical unreferenced capture latency.
     * @param delayOverrideSec use this instead of estimating (e.g. IR onset, if you have the IR).
     */
    fun strip(
        m: MeasuredResponse,
        gateDb: Double = 20.0,
        minDelaySec: Double = -0.002,
        maxDelaySec: Double = 0.050,
        delayOverrideSec: Double? = null,
    ): BaselineChannel {
        val f = m.freqHz
        val n = f.size
        val phi = DoubleArray(n) { Math.toRadians(m.phaseDeg[it]) }
        val w = weights(m.magDb, gateDb)

        val tau = delayOverrideSec ?: estimateDelay(f, phi, w, minDelaySec, maxDelaySec)

        // Remove the delay from the WRAPPED phase first, then unwrap the now slow residual.
        // Unwrapping before removal fails on log-spaced grids: at 3 ms and ~300 Hz bin spacing
        // near 20 kHz the true step between bins exceeds pi, so naive unwrap aliases.
        val resid = DoubleArray(n) { wrap(phi[it] + TWO_PI * f[it] * tau) }
        val excess = unwrap(resid)
        anchorToPassband(excess, w)

        return BaselineChannel(f.copyOf(), m.magDb.copyOf(), excess, tau)
    }

    // ---------------------------------------------------------------- delay estimation

    /**
     * Finds the tau that makes the residual phase smoothest across adjacent bins:
     *   cost(tau) = sum_i w_i * (1 - cos(dPhi_i + 2*pi*dF_i*tau))
     * cos() makes it immune to the wraps in the raw data. High-frequency bin pairs (largest dF)
     * dominate, which is where filter group delay is smallest, so tau ~ true arrival.
     */
    private fun estimateDelay(
        f: DoubleArray, phi: DoubleArray, w: DoubleArray,
        tauMin: Double, tauMax: Double,
    ): Double {
        val m = f.size - 1
        val dPhi = DoubleArray(m) { phi[it + 1] - phi[it] }
        val dF = DoubleArray(m) { f[it + 1] - f[it] }
        val pw = DoubleArray(m) { min(w[it], w[it + 1]) }

        var dfMax = 0.0
        for (i in 0 until m) if (pw[i] > 0.0) dfMax = max(dfMax, dF[i])
        if (dfMax == 0.0) return 0.0 // nothing in band; leave untouched

        fun cost(tau: Double): Double {
            var c = 0.0
            for (i in 0 until m) {
                val wi = pw[i]
                if (wi == 0.0) continue
                c += wi * (1.0 - cos(dPhi[i] + TWO_PI * dF[i] * tau))
            }
            return c
        }

        // Coarse grid fine enough to resolve the fastest ripple in cost (period 1/dfMax).
        val range = tauMax - tauMin
        val step = min(1.0 / (16.0 * dfMax), range / 200.0)
        val steps = (range / step).roundToLong().coerceAtMost(200_000L)
        var bestTau = tauMin
        var bestCost = Double.MAX_VALUE
        for (k in 0..steps) {
            val t = tauMin + k * step
            val c = cost(t)
            if (c < bestCost) { bestCost = c; bestTau = t }
        }

        // Golden-section refine inside one grid step either side.
        var a = max(tauMin, bestTau - step)
        var b = min(tauMax, bestTau + step)
        val gr = (sqrt(5.0) - 1.0) / 2.0
        var c = b - gr * (b - a)
        var d = a + gr * (b - a)
        var fc = cost(c)
        var fd = cost(d)
        repeat(80) {
            if (fc < fd) { b = d; d = c; fd = fc; c = b - gr * (b - a); fc = cost(c) }
            else { a = c; c = d; fc = fd; d = a + gr * (b - a); fd = cost(d) }
        }
        return (a + b) / 2.0
    }

    /** Linear-amplitude weight relative to the channel peak; zero below the gate. */
    private fun weights(magDb: DoubleArray, gateDb: Double): DoubleArray {
        var peak = Double.NEGATIVE_INFINITY
        for (v in magDb) if (v > peak) peak = v
        return DoubleArray(magDb.size) {
            val rel = magDb[it] - peak
            if (rel < -gateDb) 0.0 else 10.0.pow(rel / 20.0)
        }
    }

    // ---------------------------------------------------------------- phase helpers

    fun wrap(x: Double): Double = atan2(sin(x), cos(x))

    private fun unwrap(p: DoubleArray): DoubleArray {
        val out = p.copyOf()
        var offset = 0.0
        for (i in 1 until p.size) {
            val d = p[i] - p[i - 1]
            if (d > PI) offset -= TWO_PI else if (d < -PI) offset += TWO_PI
            out[i] = p[i] + offset
        }
        return out
    }

    /** Remove the arbitrary 2*pi*k from unwrapping so the passband sits near 0, not at -1440 deg. */
    private fun anchorToPassband(p: DoubleArray, w: DoubleArray) {
        var idx = 0
        for (i in w.indices) if (w[i] > w[idx]) idx = i
        val k = Math.round(p[idx] / TWO_PI)
        if (k != 0L) {
            val shift = k * TWO_PI
            for (i in p.indices) p[i] -= shift
        }
    }

    // ---------------------------------------------------------------- use at display time

    /**
     * Resample the baseline onto the plot / DSP grid. Linear in log-frequency.
     * Safe because excessPhaseRad is unwrapped and smooth. Clamps outside the measured range.
     */
    fun resample(b: BaselineChannel, targetHz: DoubleArray): Pair<DoubleArray, DoubleArray> {
        val src = b.freqHz
        val logSrc = DoubleArray(src.size) { ln(src[it]) }
        val mag = DoubleArray(targetHz.size)
        val ph = DoubleArray(targetHz.size)
        var j = 0
        for (i in targetHz.indices) {
            val lf = ln(targetHz[i])
            when {
                lf <= logSrc.first() -> { mag[i] = b.magDb.first(); ph[i] = b.excessPhaseRad.first() }
                lf >= logSrc.last() -> { mag[i] = b.magDb.last(); ph[i] = b.excessPhaseRad.last() }
                else -> {
                    while (logSrc[j + 1] < lf) j++
                    val t = (lf - logSrc[j]) / (logSrc[j + 1] - logSrc[j])
                    mag[i] = b.magDb[j] + t * (b.magDb[j + 1] - b.magDb[j])
                    ph[i] = b.excessPhaseRad[j] + t * (b.excessPhaseRad[j + 1] - b.excessPhaseRad[j])
                }
            }
        }
        return mag to ph
    }

    /**
     * Combined phase for drawing, in degrees, wrapped to +-180.
     * dspPhaseRad must be computed on the SAME grid (wrapped is fine: wrap(a + wrap(b)) == wrap(a + b)).
     * Include applied delays here (or not) depending on the plot mode; the baseline itself carries none.
     */
    fun combinedPhaseDeg(baselinePhaseRad: DoubleArray, dspPhaseRad: DoubleArray): DoubleArray {
        require(baselinePhaseRad.size == dspPhaseRad.size)
        return DoubleArray(dspPhaseRad.size) { Math.toDegrees(wrap(baselinePhaseRad[it] + dspPhaseRad[it])) }
    }

    /** Per-bin visibility mask so out-of-band phase isn't drawn. */
    fun visibleMask(magDb: DoubleArray, gateDb: Double = 20.0): BooleanArray {
        var peak = Double.NEGATIVE_INFINITY
        for (v in magDb) if (v > peak) peak = v
        return BooleanArray(magDb.size) { magDb[it] >= peak - gateDb }
    }
}
