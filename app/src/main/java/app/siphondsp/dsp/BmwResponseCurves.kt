package app.siphondsp.dsp

import kotlin.math.PI

/**
 * Output holder for [BmwResponseCalculator.compute]. All arrays are pre-sized and reused
 * across recomputes -- nothing here is ever reallocated after construction. No [ComplexAcc]
 * or complex type ever leaves the dsp package; everything public is a dB [DoubleArray] or
 * a radian phase [DoubleArray].
 */
class BmwResponseCurves(val pointCount: Int) {
    val frequencies = DoubleArray(pointCount)

    /** Indexed by [BmwOutputChannel.ordinal]. */
    val sumDb = Array(2) { DoubleArray(pointCount) }
    val lowBranchDb = Array(2) { DoubleArray(pointCount) }
    val midBranchDb = Array(2) { DoubleArray(pointCount) }
    val preSplitDb = Array(2) { DoubleArray(pointCount) }

    /** Wrapped phase in radians, (-pi, pi]. Indexed by [BmwOutputChannel.ordinal]. */
    val sumPhase = Array(2) { DoubleArray(pointCount) }
    val lowBranchPhase = Array(2) { DoubleArray(pointCount) }
    val midBranchPhase = Array(2) { DoubleArray(pointCount) }

    var processorEnabled = true
    var lowBranchActive = true
    var midBranchActive = true
    var bothCrossoversBypassed = false

    fun sumDbFor(channel: BmwOutputChannel): DoubleArray = sumDb[channel.ordinal]
    fun lowBranchDbFor(channel: BmwOutputChannel): DoubleArray = lowBranchDb[channel.ordinal]
    fun midBranchDbFor(channel: BmwOutputChannel): DoubleArray = midBranchDb[channel.ordinal]
    fun preSplitDbFor(channel: BmwOutputChannel): DoubleArray = preSplitDb[channel.ordinal]

    fun sumPhaseFor(channel: BmwOutputChannel): DoubleArray = sumPhase[channel.ordinal]
    fun lowBranchPhaseFor(channel: BmwOutputChannel): DoubleArray = lowBranchPhase[channel.ordinal]
    fun midBranchPhaseFor(channel: BmwOutputChannel): DoubleArray = midBranchPhase[channel.ordinal]

    /**
     * Group delay in milliseconds at each point, derived from the finite difference of
     * *unwrapped* [sumPhase] across the (log-spaced) frequency axis: -d(phase)/d(omega).
     * Endpoints reuse their single neighbouring slope. This is a display-only derivative,
     * not a separately accumulated model, so it inherits whatever smoothness the underlying
     * magnitude/phase curve has -- expect visible noise near sharp notches, which is why
     * group delay is opt-in in the UI rather than always shown.
     */
    fun groupDelayMsFor(channel: BmwOutputChannel): DoubleArray {
        val phase = sumPhaseFor(channel)
        val unwrapped = DoubleArray(pointCount)
        if (pointCount > 0) unwrapped[0] = phase[0]
        for (i in 1 until pointCount) {
            var delta = phase[i] - phase[i - 1]
            while (delta > PI) delta -= 2.0 * PI
            while (delta < -PI) delta += 2.0 * PI
            unwrapped[i] = unwrapped[i - 1] + delta
        }
        val result = DoubleArray(pointCount)
        for (i in 0 until pointCount) {
            val lo = (i - 1).coerceAtLeast(0)
            val hi = (i + 1).coerceAtMost(pointCount - 1)
            if (hi == lo) {
                result[i] = 0.0
                continue
            }
            val dPhase = unwrapped[hi] - unwrapped[lo]
            val dOmega = 2.0 * PI * (frequencies[hi] - frequencies[lo])
            result[i] = if (dOmega == 0.0) 0.0 else (-dPhase / dOmega) * 1000.0
        }
        return result
    }
}
