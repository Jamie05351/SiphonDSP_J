package app.siphondsp.dsp.bandalign

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin

/**
 * One acoustic measurement as REW exports it with "Export measurement as text": rows of
 * `frequency  SPL(dB)  phase(degrees)`, plus the header note REW writes when it has taken the
 * measurement's delay out of the phase ("Delay -4.4223 ms ... using estimated IR delay ...").
 *
 * The complex response is rebuilt with that delay put back, so two measurements made against the
 * same acoustic timing reference line up in time -- which is what summing them needs. Band Align
 * then refines the residual Low/Mid offset against a both-bands measurement when there is one
 * (see [BandAlignSolver]), because REW's delay estimate isn't always taken the same way.
 */
class RewMeasurement private constructor(
    val name: String,
    /** Hz, ascending. */
    val frequencies: DoubleArray,
    val splDb: DoubleArray,
    /** Unwrapped phase in radians, with the removed delay restored. */
    val phaseRad: DoubleArray,
    /** The delay REW removed, in seconds (0 if the header didn't say). */
    val removedDelaySeconds: Double,
    /** The smoothing REW applied before exporting, as written in the header (e.g. "1/48 octave"). */
    val smoothing: String?,
) {
    /** Complex response at [frequencyHz], interpolated linearly in log frequency; null outside the data. */
    fun at(frequencyHz: Double): Complex? {
        if (frequencyHz < frequencies.first() || frequencyHz > frequencies.last()) return null
        var hi = frequencies.binarySearch(frequencyHz)
        if (hi >= 0) return polar(splDb[hi], phaseRad[hi])
        hi = -hi - 1
        val lo = hi - 1
        val t = (ln(frequencyHz) - ln(frequencies[lo])) / (ln(frequencies[hi]) - ln(frequencies[lo]))
        return polar(splDb[lo] + (splDb[hi] - splDb[lo]) * t, phaseRad[lo] + (phaseRad[hi] - phaseRad[lo]) * t)
    }

    /**
     * True when the export was smoothed too coarsely to sum its phase: coarser than 1/12 octave
     * (1/6, 1/3, ...), or one of REW's variable smoothings (Psychoacoustic, ERB, Var), which are
     * 1/3 to 1/6 octave through the bass -- exactly where Band Align works.
     */
    val heavilySmoothed: Boolean
        get() {
            val s = smoothing ?: return false
            val m = Regex("""1/(\d+)""").find(s)
                ?: return !s.trim().lowercase().let { it == "none" || it.startsWith("no ") }
            return (m.groupValues[1].toIntOrNull() ?: 48) < 12
        }

    companion object {
        /** The band every measurement must cover. */
        const val MIN_COVER_HZ = 28.0
        const val MAX_COVER_HZ = 500.0
        private val DelayNote = Regex("""Delay\s+(-?[0-9.]+)\s*ms""")
        private val SmoothingNote = Regex("""^\*\s*Smoothing:\s*(.+)$""")

        /**
         * A data row's frequency, SPL and phase, or null for anything else (a header, a blank
         * line). Takes REW's tab- or space-separated text, CSV, semicolon-separated, and a comma
         * as the decimal point (as REW writes it in some languages): "20.0 85.3 120.1",
         * "20.0,85.3,120.1", "20,0;85,3;120,1" and "20,0 85,3 120,1" all read the same.
         */
        internal fun rowNumbers(line: String): DoubleArray? {
            val t = line.trim()
            if (t.isEmpty()) return null
            val spaced = t.split(SPACE_OR_SEMICOLON).map { it.trimEnd(',') }.filter { it.isNotEmpty() }
            val parts = if (spaced.size >= 3) spaced.map { it.replace(',', '.') } else t.split(',').map { it.trim() }
            if (parts.size < 3) return null
            val values = parts.take(3).map { it.toDoubleOrNull() }
            return if (values.any { it == null }) null else DoubleArray(3) { values[it]!! }
        }

        private val SPACE_OR_SEMICOLON = Regex("""[\s;]+""")

        /**
         * Parses an export, or returns the reason it can't be used. [coverFromHz]: the lowest
         * frequency it must reach; null for a Mid-only measurement, which may start wherever its
         * sweep did (Mid is high-passed: below its data it's taken as silent, and Analyse checks
         * it starts low enough for its crossover).
         */
        fun parse(name: String, text: String, coverFromHz: Double? = MIN_COVER_HZ): Result<RewMeasurement> {
            val f = ArrayList<Double>()
            val spl = ArrayList<Double>()
            val ph = ArrayList<Double>()
            var delayMs = 0.0
            var smoothing: String? = null
            for (line in text.lineSequence()) {
                if (line.startsWith("*")) {
                    DelayNote.find(line)?.let { m ->
                        delayMs = m.groupValues[1].toDoubleOrNull()
                            ?: return Result.failure(IllegalArgumentException("$name: couldn't read the delay in its header note"))
                    }
                    SmoothingNote.find(line.trim())?.let { smoothing = it.groupValues[1].trim() }
                    continue
                }
                val row = rowNumbers(line) ?: continue
                val freq = row[0]
                val db = row[1]
                val deg = row[2]
                if (!freq.isFinite() || !db.isFinite() || !deg.isFinite() || freq <= 0.0) continue
                if (f.isNotEmpty() && freq <= f.last()) continue
                f += freq
                spl += db
                ph += deg * PI / 180.0
            }
            if (f.size < 16) {
                return Result.failure(IllegalArgumentException("$name: no frequency / SPL / phase rows -- export from REW with File > Export > Measurement as text"))
            }
            // The solver scores the crossover region (within 40-400 Hz) and times Low/Mid up to 500 Hz.
            if (coverFromHz == null) {
                if (f.last() < MAX_COVER_HZ) {
                    return Result.failure(IllegalArgumentException("$name: needs to reach at least 500 Hz (it ends at ${f.last().toInt()} Hz)"))
                }
            } else if (f.first() > coverFromHz || f.last() < MAX_COVER_HZ) {
                return Result.failure(IllegalArgumentException(
                    "$name: needs to cover at least ${coverFromHz.toInt()}-500 Hz (it covers ${f.first().toInt()}-${f.last().toInt()} Hz; REW exports 20 Hz-20 kHz by default)",
                ))
            }
            val delay = delayMs / 1000.0
            val unwrapped = DoubleArray(ph.size)
            var offset = 0.0
            for (i in ph.indices) {
                if (i > 0) {
                    val d = ph[i] - ph[i - 1]
                    if (d > PI) offset -= 2 * PI else if (d < -PI) offset += 2 * PI
                }
                // REW removed `delay` (a negative "Delay -x ms" note) from the phase; putting it back.
                unwrapped[i] = ph[i] + offset - 2 * PI * f[i] * delay
            }
            return Result.success(
                RewMeasurement(name, f.toDoubleArray(), spl.toDoubleArray(), unwrapped, delay, smoothing),
            )
        }

        private fun polar(db: Double, rad: Double): Complex {
            val a = 10.0.pow(db / 20.0)
            return Complex(a * cos(rad), a * sin(rad))
        }
    }
}

/** A minimal immutable complex number for the solver's arithmetic. */
data class Complex(val re: Double, val im: Double) {
    operator fun plus(o: Complex) = Complex(re + o.re, im + o.im)
    operator fun times(o: Complex) = Complex(re * o.re - im * o.im, re * o.im + im * o.re)
    operator fun div(o: Complex): Complex {
        val d = o.re * o.re + o.im * o.im
        return if (d == 0.0) Complex(0.0, 0.0) else Complex((re * o.re + im * o.im) / d, (im * o.re - re * o.im) / d)
    }
    fun power() = re * re + im * im

    companion object {
        fun polar(magnitude: Double, phase: Double) = Complex(magnitude * cos(phase), magnitude * sin(phase))
        val ONE = Complex(1.0, 0.0)
    }
}
