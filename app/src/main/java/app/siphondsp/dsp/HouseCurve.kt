package app.siphondsp.dsp

import java.io.BufferedReader
import kotlin.math.abs
import kotlin.math.ln

/**
 * A house (target) curve for the Parametric EQ graph: ascending [frequencies] in Hz with the
 * target level in [db]. Display only -- nothing here reaches the DSP.
 *
 * The graph draws it on its own log-frequency grid ([onGrid]) and lines it up with the full
 * curve by one offset ([alignmentOffset]): the house curve's mean over [ALIGN_LOW_HZ] ..
 * [ALIGN_HIGH_HZ] is moved onto the displayed curve's mean over the same range.
 */
class HouseCurve(val frequencies: DoubleArray, val db: DoubleArray) {
    init {
        require(frequencies.size == db.size) { "frequency and level counts differ" }
        require(frequencies.size >= HouseCurveParser.MIN_POINTS) { "a house curve needs at least two points" }
    }

    /**
     * The curve at each of [axis] (Hz), interpolated linearly in log frequency between its
     * points. NaN outside the curve's own range: it is never extended past what the file gives.
     */
    fun onGrid(axis: DoubleArray): DoubleArray = DoubleArray(axis.size) { at(axis[it]) }

    /** The level at [frequency], or NaN outside the curve's range. */
    fun at(frequency: Double): Double {
        val f = frequencies
        if (!(frequency >= f.first() && frequency <= f.last())) return Double.NaN
        var hi = f.binarySearch(frequency)
        if (hi >= 0) return db[hi]
        hi = -hi - 1
        val lo = hi - 1
        val t = (ln(frequency) - ln(f[lo])) / (ln(f[hi]) - ln(f[lo]))
        return db[lo] + t * (db[hi] - db[lo])
    }

    companion object {
        const val ALIGN_LOW_HZ = 200.0
        const val ALIGN_HIGH_HZ = 5_000.0

        /**
         * The offset (dB) to add to [house] (already on [axis], see [onGrid]) so its mean over
         * [ALIGN_LOW_HZ] .. [ALIGN_HIGH_HZ] equals the mean of [displayed] over the same grid
         * points. [displayed] holds one curve per channel shown; with both, their joint mean is
         * the target, so one offset serves both channels. Points where any curve isn't finite
         * are left out. 0 when no point qualifies.
         */
        fun alignmentOffset(house: DoubleArray, displayed: List<DoubleArray>, axis: DoubleArray): Double {
            var houseSum = 0.0
            var curveSum = 0.0
            var count = 0
            for (i in axis.indices) {
                if (axis[i] < ALIGN_LOW_HZ || axis[i] > ALIGN_HIGH_HZ) continue
                val h = house.getOrElse(i) { Double.NaN }
                if (!h.isFinite()) continue
                var point = 0.0
                var usable = displayed.isNotEmpty()
                for (curve in displayed) {
                    val v = curve.getOrElse(i) { Double.NaN }
                    if (!v.isFinite()) {
                        usable = false
                        break
                    }
                    point += v
                }
                if (!usable) continue
                houseSum += h
                curveSum += point / displayed.size
                count++
            }
            return if (count > 0) (curveSum - houseSum) / count else 0.0
        }
    }
}

/**
 * Reads a REW-style house-curve text file: one `frequency_hz dB` pair per line, separated by
 * commas, spaces or tabs (a decimal comma is read where it can't be a separator, as in
 * [CarBaselineParser.fields]). Lines starting with `*` or `#`, blank lines and lines whose first
 * two fields aren't numbers (headers) are skipped; extra columns are ignored. Points are sorted
 * by frequency; a repeated frequency keeps its first level.
 */
object HouseCurveParser {
    const val MIN_POINTS = 2
    /** A house curve is a few dozen points; REW's densest export is ~32k lines. */
    const val MAX_LINES = 200_000
    /** A target this far from 0 dB isn't a target (and would wreck the alignment). */
    const val MAX_ABS_DB = 60.0

    fun parse(reader: BufferedReader): HouseCurve {
        val points = ArrayList<Pair<Double, Double>>()
        var lines = 0
        while (true) {
            val raw = reader.readLine() ?: break
            if (++lines > MAX_LINES) throw IllegalArgumentException("File is too long to be a house curve")
            val line = raw.trim()
            if (line.isEmpty() || line[0] == '*' || line[0] == '#') continue
            val fields = CarBaselineParser.fields(line)
            if (fields.size < 2) continue
            val frequency = fields[0].toDoubleOrNull() ?: continue
            val level = fields[1].toDoubleOrNull() ?: continue
            if (!frequency.isFinite() || frequency <= 0.0 || !level.isFinite() || abs(level) > MAX_ABS_DB) continue
            points += frequency to level
        }
        points.sortBy { it.first }
        val frequencies = ArrayList<Double>(points.size)
        val levels = ArrayList<Double>(points.size)
        for ((frequency, level) in points) {
            if (frequencies.isNotEmpty() && frequency == frequencies.last()) continue
            frequencies += frequency
            levels += level
        }
        if (frequencies.size < MIN_POINTS) {
            throw IllegalArgumentException("No house curve found (need frequency and dB columns)")
        }
        if (frequencies.first() > HouseCurve.ALIGN_LOW_HZ || frequencies.last() < HouseCurve.ALIGN_HIGH_HZ) {
            throw IllegalArgumentException("House curve doesn't cover 200 Hz - 5 kHz")
        }
        return HouseCurve(frequencies.toDoubleArray(), levels.toDoubleArray())
    }
}
