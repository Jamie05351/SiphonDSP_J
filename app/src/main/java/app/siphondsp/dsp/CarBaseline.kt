package app.siphondsp.dsp

import java.io.BufferedReader
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * One measured magnitude response, as REW's "Export measurement as text" (or any two-column
 * frequency / dB list) writes it: ascending [frequencies] in Hz with their level in [db].
 */
class CarBaselineMeasurement(val frequencies: DoubleArray, val db: DoubleArray) {
    init {
        require(frequencies.size == db.size) { "frequency and level counts differ" }
    }
}

/**
 * Reads a measurement export. Comment lines (`*`, `#`, `;`, `"`) and any line whose first two
 * fields aren't numbers (column headers) are skipped; fields may be separated by whitespace,
 * commas or semicolons. A line using semicolons is read with a decimal comma (`20,5;74,3`).
 * Only the first two columns are used -- a phase column, if present, is ignored.
 */
object CarBaselineParser {

    /** REW's full-resolution export is ~32k lines; anything far past that isn't a measurement. */
    const val MAX_LINES = 200_000
    const val MIN_POINTS = 8

    private val separators = Regex("[\\s,;]+")

    fun parse(reader: BufferedReader): CarBaselineMeasurement {
        val points = ArrayList<Pair<Double, Double>>()
        var lines = 0
        while (true) {
            val raw = reader.readLine() ?: break
            if (++lines > MAX_LINES) throw IllegalArgumentException("File is too long to be a measurement export")
            val line = raw.trim()
            if (line.isEmpty() || line[0] == '*' || line[0] == '#' || line[0] == ';' || line[0] == '"') continue
            val normalised = if (';' in line) line.replace(',', '.') else line
            val fields = normalised.split(separators)
            if (fields.size < 2) continue
            val frequency = fields[0].toDoubleOrNull() ?: continue
            val level = fields[1].toDoubleOrNull() ?: continue
            if (!frequency.isFinite() || frequency <= 0.0 || !level.isFinite()) continue
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
            throw IllegalArgumentException("No measurement found (need frequency and dB columns)")
        }
        if (frequencies.last() < 200.0 || frequencies.first() > 2_000.0) {
            throw IllegalArgumentException("Measurement doesn't cover 200 Hz - 2 kHz")
        }
        return CarBaselineMeasurement(frequencies.toDoubleArray(), levels.toDoubleArray())
    }
}

/**
 * The car's own response downstream of this DSP -- factory amplifier/DSP, speakers and cabin --
 * measured at the listening position with this DSP flat or off. This DSP feeds that chain in
 * series, so the response you hear is this DSP's modelled response plus the baseline, in dB, for
 * every curve: [applyTo] adds it to the sum, the pre-split (Pre EQ) curve and each band branch.
 *
 * Both channels are levelled by one shared reference (their mean level over 200 Hz - 2 kHz), so
 * the curves sit around 0 dB while a real left/right level difference still shows. A channel
 * with no measurement uses the other channel's. Magnitude only: a measured phase carries the
 * time of flight to the microphone, which would swamp the phase view, so phase stays modelled.
 */
class CarBaseline(val left: CarBaselineMeasurement?, val right: CarBaselineMeasurement?) {

    init {
        require(left != null || right != null) { "a baseline needs at least one channel" }
    }

    private var axis = DoubleArray(0)
    private val offsets = Array(2) { DoubleArray(0) }

    /** The baseline in dB at each of [frequencies] for [channel] (a [BmwOutputChannel] ordinal). */
    fun offsetsFor(channel: Int, frequencies: DoubleArray): DoubleArray {
        prepare(frequencies)
        return offsets[channel]
    }

    fun applyTo(curves: BmwResponseCurves) {
        prepare(curves.frequencies)
        for (ch in 0..1) {
            val offset = offsets[ch]
            for (arr in arrayOf(curves.sumDb[ch], curves.preSplitDb[ch], curves.lowBranchDb[ch], curves.midBranchDb[ch], curves.highBranchDb[ch])) {
                for (i in arr.indices) arr[i] += offset[i]
            }
        }
    }

    private fun prepare(frequencies: DoubleArray) {
        if (axis.contentEquals(frequencies)) return
        val l = left?.let { resample(it, frequencies) }
        val r = right?.let { resample(it, frequencies) }
        val leftDb = l ?: r!!
        val rightDb = r ?: l!!
        val reference = listOfNotNull(l, r).map { referenceLevel(it, frequencies) }.average()
        for (i in leftDb.indices) {
            leftDb[i] -= reference
            if (rightDb !== leftDb) rightDb[i] -= reference
        }
        offsets[BmwOutputChannel.LEFT.ordinal] = leftDb
        offsets[BmwOutputChannel.RIGHT.ordinal] = rightDb
        axis = frequencies.copyOf()
    }

    companion object {
        private const val REFERENCE_LOW_HZ = 200.0
        private const val REFERENCE_HIGH_HZ = 2_000.0

        /**
         * [m] on the graph's (log-spaced) [axis]: each point is the mean of every measured point
         * in its own band (halfway, in log frequency, to each neighbour), so a dense export doesn't
         * alias into the coarser graph; where no measured point falls in a band it's interpolated
         * in log frequency, and past either end of the measurement the end level is held.
         */
        internal fun resample(m: CarBaselineMeasurement, axis: DoubleArray): DoubleArray {
            val out = DoubleArray(axis.size)
            val f = m.frequencies
            var start = 0
            for (i in axis.indices) {
                val lo = if (i == 0) axis[0] else sqrt(axis[i - 1] * axis[i])
                val hi = if (i == axis.lastIndex) axis[i] else sqrt(axis[i] * axis[i + 1])
                while (start < f.size && f[start] < lo) start++
                var sum = 0.0
                var count = 0
                var j = start
                while (j < f.size && f[j] <= hi) {
                    sum += m.db[j]
                    count++
                    j++
                }
                out[i] = if (count > 0) sum / count else interpolate(m, axis[i])
            }
            return out
        }

        private fun interpolate(m: CarBaselineMeasurement, frequency: Double): Double {
            val f = m.frequencies
            if (frequency <= f.first()) return m.db.first()
            if (frequency >= f.last()) return m.db.last()
            var hi = f.binarySearch(frequency)
            if (hi >= 0) return m.db[hi]
            hi = -hi - 1
            val lo = hi - 1
            val t = (ln(frequency) - ln(f[lo])) / (ln(f[hi]) - ln(f[lo]))
            return m.db[lo] + t * (m.db[hi] - m.db[lo])
        }

        private fun referenceLevel(db: DoubleArray, axis: DoubleArray): Double {
            var sum = 0.0
            var count = 0
            for (i in axis.indices) {
                if (axis[i] in REFERENCE_LOW_HZ..REFERENCE_HIGH_HZ) {
                    sum += db[i]
                    count++
                }
            }
            return if (count > 0) sum / count else db.average()
        }
    }
}
