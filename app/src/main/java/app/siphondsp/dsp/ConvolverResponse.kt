package app.siphondsp.dsp

import app.siphondsp.interop.NativeDsp

/**
 * The running convolver IR's complex response on one frequency grid, as the engine computed it
 * ([NativeDsp.getConvolverSnapshotResponse]): per logical channel ([BmwOutputChannel.ordinal]; the IR's
 * left is the car's left), with the IR's bulk latency taken out so the phase shows the correction
 * rather than a pure delay. [BmwResponseCalculator] multiplies the sum by it, at the gain the
 * engine applies ([appliedGainDb]).
 *
 * [generation] is the engine's convolver generation when it was computed: a new one means a new
 * IR, rebuild or clear, so refetch.
 */
class ConvolverResponse(
    val generation: Long,
    /** The grid this was computed on, Hz; only applied to a calculator on this exact grid. */
    val frequencies: DoubleArray,
    /** Indexed by [BmwOutputChannel.ordinal], then frequency. */
    val re: Array<DoubleArray>,
    val im: Array<DoubleArray>,
    val peakGainDb: Double,
    val latencyFrames: Int,
    val sampleRate: Double,
) {
    /** True when this was computed on exactly [grid]. */
    fun matches(grid: DoubleArray): Boolean = frequencies.contentEquals(grid)

    companion object {
        /**
         * The gain the engine's stage applies: the trim (clamped as native clamps it), capped at
         * -peak so the IR never lifts any frequency above unity (ConvolverStage::process()).
         */
        fun appliedGainDb(trimDb: Double, peakGainDb: Double): Double = minOf(trimDb, -peakGainDb)

        /** Parses [NativeDsp.getConvolverSnapshotResponse]'s array for [frequencies]; null if malformed. */
        fun fromNative(values: DoubleArray, frequencies: DoubleArray): ConvolverResponse? {
            val n = frequencies.size
            if (n == 0 || values.size != NativeDsp.CONVOLVER_RESPONSE_HEADER + 4 * n) return null
            val re = Array(2) { DoubleArray(n) }
            val im = Array(2) { DoubleArray(n) }
            for (ch in 0..1) {
                val base = NativeDsp.CONVOLVER_RESPONSE_HEADER + ch * 2 * n
                for (i in 0 until n) {
                    re[ch][i] = values[base + 2 * i]
                    im[ch][i] = values[base + 2 * i + 1]
                }
            }
            return ConvolverResponse(
                generation = values[NativeDsp.CONVOLVER_RESPONSE_GENERATION].toLong(),
                frequencies = frequencies.copyOf(),
                re = re,
                im = im,
                peakGainDb = values[NativeDsp.CONVOLVER_RESPONSE_PEAK_GAIN_DB],
                latencyFrames = values[NativeDsp.CONVOLVER_RESPONSE_LATENCY_FRAMES].toInt(),
                sampleRate = values[NativeDsp.CONVOLVER_RESPONSE_SAMPLE_RATE],
            )
        }
    }
}
