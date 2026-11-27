package app.siphondsp.dsp.bandalign

import app.siphondsp.dsp.BmwOutputChannel
import app.siphondsp.model.NativeBmwDspValues
import kotlin.math.PI
import kotlin.math.log10

/**
 * The car's measured Low + Mid sum through their crossover, as it is with the delays and
 * polarities set *now*: the REW Low-only and Mid-only measurements (complex, with REW's removed
 * delay put back) re-timed by how far each band's delay and polarity have moved since it was
 * measured, then added.
 *
 * Only delay and polarity are carried forward exactly. A crossover, gain or PEQ change since the
 * measurement isn't (it would need the old filters divided out); [Result.crossoverChanged] flags
 * the crossover case so the graph can say so instead of implying more than it shows.
 */
object MeasuredLowMid {

    /** A band's timing on one side: its delay and whether it is inverted. */
    data class Timing(val delayMs: Double, val inverted: Boolean)

    class Result(
        /** dB at each requested frequency with today's timing; NaN where a measurement doesn't reach. */
        val nowDb: DoubleArray,
        /** The same sum as it was measured (no timing change). */
        val thenDb: DoubleArray,
        /** A Low / Mid crossover frequency or slope differs from when the files were imported. */
        val crossoverChanged: Boolean,
    )

    fun lowTiming(values: FloatArray, side: BmwOutputChannel) = Timing(
        values[if (side == BmwOutputChannel.LEFT) NativeBmwDspValues.INDEX_LOW_DELAY_L else NativeBmwDspValues.INDEX_LOW_DELAY_R].toDouble(),
        values[NativeBmwDspValues.outputIndex(
            if (side == BmwOutputChannel.LEFT) NativeBmwDspValues.OUTPUT_LOW_LEFT else NativeBmwDspValues.OUTPUT_LOW_RIGHT,
            NativeBmwDspValues.FIELD_INVERT,
        )] >= .5f,
    )

    fun midTiming(values: FloatArray, side: BmwOutputChannel) = Timing(
        values[if (side == BmwOutputChannel.LEFT) NativeBmwDspValues.INDEX_MID_DELAY_L else NativeBmwDspValues.INDEX_MID_DELAY_R].toDouble(),
        values[NativeBmwDspValues.outputIndex(
            if (side == BmwOutputChannel.LEFT) NativeBmwDspValues.OUTPUT_MID_LEFT else NativeBmwDspValues.OUTPUT_MID_RIGHT,
            NativeBmwDspValues.FIELD_INVERT,
        )] >= .5f,
    )

    /** The Low / Mid crossover settings a measurement depends on: both corner frequencies and both slopes. */
    private val CrossoverIndices = intArrayOf(
        NativeBmwDspValues.INDEX_LOW_CROSSOVER_FREQ,
        NativeBmwDspValues.INDEX_MID_CROSSOVER_FREQ,
        NativeBmwDspValues.outputIndex(NativeBmwDspValues.OUTPUT_LOW_LEFT, NativeBmwDspValues.FIELD_CROSSOVER_TYPE),
        NativeBmwDspValues.outputIndex(NativeBmwDspValues.OUTPUT_LOW_RIGHT, NativeBmwDspValues.FIELD_CROSSOVER_TYPE),
        NativeBmwDspValues.outputIndex(NativeBmwDspValues.OUTPUT_MID_LEFT, NativeBmwDspValues.FIELD_CROSSOVER_TYPE),
        NativeBmwDspValues.outputIndex(NativeBmwDspValues.OUTPUT_MID_RIGHT, NativeBmwDspValues.FIELD_CROSSOVER_TYPE),
    )

    fun crossoverChanged(then: FloatArray, now: FloatArray): Boolean =
        CrossoverIndices.any { it < then.size && it < now.size && then[it] != now[it] }

    /** The factor that moves a band measured at [then] to [now]: a pure delay, and a sign flip if the polarity changed. */
    fun change(then: Timing, now: Timing, frequencyHz: Double): Complex =
        Complex.polar(
            if (then.inverted != now.inverted) -1.0 else 1.0,
            -2.0 * PI * frequencyHz * (now.delayMs - then.delayMs) / 1000.0,
        )

    fun sum(
        low: RewMeasurement,
        lowThen: Timing,
        lowNow: Timing,
        mid: RewMeasurement,
        midThen: Timing,
        midNow: Timing,
        frequencies: DoubleArray,
        crossoverChanged: Boolean = false,
    ): Result {
        val now = DoubleArray(frequencies.size)
        val then = DoubleArray(frequencies.size)
        for (i in frequencies.indices) {
            val f = frequencies[i]
            val l = low.at(f)
            val m = mid.at(f)
            if (l == null || m == null) {
                now[i] = Double.NaN
                then[i] = Double.NaN
                continue
            }
            then[i] = db(l + m)
            now[i] = db(l * change(lowThen, lowNow, f) + m * change(midThen, midNow, f))
        }
        return Result(now, then, crossoverChanged)
    }

    private fun db(c: Complex): Double = 10.0 * log10(c.power().coerceAtLeast(1e-20))
}
