package app.siphondsp.dsp

import app.siphondsp.model.NativeBmwDspValues
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * The worst-case level through the chain, step by step in the engine's order, worked out from the
 * settings (not measured): the loudest point of the preamp + EQ + crossover response, then
 * headroom, tilt, post gain, the master limiter and finally the master volume with Deep bass.
 *
 * Each step's worst case is added as if they all landed on the same frequency, so the total is an
 * upper bound: real music peaks lower. The stereo convolver isn't counted: its gain is applied
 * against the IR's own peak (see [ConvolverResponse.appliedGainDb]).
 */
class GainBudget(
    val steps: List<Step>,
    /** The level reaching the master limiter, in dBFS. */
    val intoLimiterDb: Double,
    /** The limiter's ceiling when it's on, else null. */
    val limiterCeilingDb: Double?,
    /** The level after the volume and Deep bass: what reaches the output, in dBFS. */
    val outputDb: Double,
) {
    enum class Kind { PREAMP, EQ, HEADROOM, TILT, POST_GAIN, LIMITER, VOLUME }

    /** One gain in the chain: [db] it adds (negative cuts), [startDb] the running level before it. */
    data class Step(val kind: Kind, val db: Double, val startDb: Double) {
        val endDb: Double get() = startDb + db
    }

    /** Whether the output stays at or under full scale. */
    val clean: Boolean get() = outputDb <= CLEAN_MARGIN_DB

    /** The limiter is holding the level down (it would be over its ceiling without it). */
    val limiterCatches: Boolean get() = limiterCeilingDb != null && intoLimiterDb > limiterCeilingDb

    companion object {
        /** Rounding slack: a total of +0.04 dB still reads 0.0. */
        const val CLEAN_MARGIN_DB = 0.05

        /**
         * [eqPeakDb] is the loudest point of the preamp + Pre EQ + band response at 0 dB headroom
         * ([AutoHeadroomCalculator.peakDb]); [preampDb] the preamp inside it; [headroomDb] the
         * headroom the engine runs (the automatic one when Auto is on).
         */
        fun compute(values: FloatArray, preampDb: Double, eqPeakDb: Double, headroomDb: Double): GainBudget {
            val steps = ArrayList<Step>(7)
            var level = 0.0
            fun add(kind: Kind, db: Double) {
                steps += Step(kind, db, level)
                level += db
            }
            add(Kind.PREAMP, preampDb)
            add(Kind.EQ, eqPeakDb - preampDb)
            add(Kind.HEADROOM, headroomDb)
            add(Kind.TILT, tiltBoostDb(values))
            add(Kind.POST_GAIN, max(value(values, NativeBmwDspValues.INDEX_POST_GAIN_L), value(values, NativeBmwDspValues.INDEX_POST_GAIN_R)))
            val intoLimiter = level
            val ceiling = if (value(values, NativeBmwDspValues.INDEX_MASTER_LIMITER_ENABLED) >= .5) {
                value(values, NativeBmwDspValues.INDEX_MASTER_LIMITER_THRESHOLD)
            } else null
            if (ceiling != null) add(Kind.LIMITER, min(0.0, ceiling - level))
            add(Kind.VOLUME, value(values, NativeBmwDspValues.INDEX_MASTER_VOLUME_DB) + NativeBmwDspValues.deepBassCurve(values).gainDb)
            return GainBudget(steps, intoLimiter, ceiling, level)
        }

        /** The tilt's boost at its louder end (a tilt boosts one end and cuts the other), 0 when off. */
        fun tiltBoostDb(values: FloatArray): Double =
            if (value(values, NativeBmwDspValues.INDEX_TILT_ENABLED) < .5) 0.0
            else abs(value(values, NativeBmwDspValues.INDEX_TILT_AMOUNT)) * NativeBmwDspValues.TILT_END_GAIN_PER_AMOUNT

        private fun value(values: FloatArray, index: Int): Double = values.getOrElse(index) { 0f }.toDouble()
    }
}
