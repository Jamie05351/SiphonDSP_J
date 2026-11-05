package app.siphondsp.dsp.autoalign

import app.siphondsp.dsp.BmwOutputChannel
import app.siphondsp.dsp.BmwResponseCalculator
import app.siphondsp.dsp.BmwResponseCurves
import app.siphondsp.model.BmwPeqState
import app.siphondsp.model.NativeBmwDspValues
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Auto Align: finds the all-pass that makes the left and right sides add up best at the measured
 * seats, through the DSP exactly as it's set now.
 *
 * In a car each seat hears the left and right speakers by different paths, so through the bass
 * and lower mids they arrive out of step and partly cancel -- a loss no EQ can fill, because both
 * channels carry the same cut. Rotating one side's phase with an all-pass lines them up instead.
 * The solver models every seat as `left speakers x the app's left chain + right speakers x the
 * app's right chain` (the app chain from [BmwResponseCalculator]: crossovers, delays, all-passes,
 * PEQ), tries a 2nd-order all-pass on each side over a grid of corners and Qs, and keeps the one
 * that lifts the 30-400 Hz sum the most across the seats -- a cut counts double a lift, so a fix
 * for one seat can't simply be paid for by another.
 *
 * The result goes in all-pass section 2 of every output on the chosen side (section 1 is left to
 * the user and to the Crossovers page's Mid align; whatever it holds is part of the model).
 */
class AutoAlignSolver(private val sampleRate: Double = 48_000.0) {

    /** How the measurements were taken. */
    enum class MeasuredWith {
        /**
         * SiphonDSP off: the factory system alone. The app's left/right swap (which corrects the
         * car's reversed speaker harness) isn't running then, so the file REW labels "L" played
         * through the right-hand speakers, and "R" through the left.
         */
        DSP_OFF,

        /** SiphonDSP on, with the settings it has now: the app chain is already in the measurement. */
        DSP_ON,
    }

    data class Seat(val name: String, val left: RewMeasurement, val right: RewMeasurement, val both: RewMeasurement?)

    /** An all-pass on every output of the app's [side]. */
    data class Proposal(val side: BmwOutputChannel, val frequencyHz: Double, val q: Double)

    data class SeatResult(
        val name: String,
        val frequencies: DoubleArray,
        /** The seat's left + right sum, smoothed, as set now. */
        val beforeDb: DoubleArray,
        /** The same with the proposal applied (equal to [beforeDb] when there is no proposal). */
        val afterDb: DoubleArray,
        /** How closely left + right reproduce the both-channels measurement, RMS dB over 30-500 Hz (null without one). */
        val fitRmsDb: Double?,
        /** The left/right time offset the fit settled on, ms (0 without a both-channels measurement). */
        val shiftMs: Double,
    )

    data class Result(
        val proposal: Proposal?,
        /** Mean improvement over 30-400 Hz across the seats, dB (losses counted double). */
        val scoreDb: Double,
        val seats: List<SeatResult>,
        /** What applying [proposal] writes. Empty when there's no proposal. */
        val updates: Map<Int, Float>,
        /** True when [updates] replace an all-pass section 2 that was already switched on. */
        val replacesExisting: Boolean,
    )

    private val calculator = BmwResponseCalculator(POINTS)
    private val curves = BmwResponseCurves(POINTS)

    fun solve(
        seats: List<Seat>,
        measuredWith: MeasuredWith,
        values: FloatArray,
        peq: BmwPeqState,
        isActive: () -> Boolean = { true },
        onProgress: (Float) -> Unit = {},
    ): Result {
        require(seats.isNotEmpty()) { "at least one seat" }
        calculator.configureAxis(sampleRate, MIN_HZ, MAX_HZ)
        calculator.invalidateAll()
        val current = appChain(values, peq)
        val freqs = curves.frequencies.copyOf()

        // Each seat's speakers, keyed by the app channel that feeds them.
        val fits = seats.map { fitSeat(it, freqs) }
        val speakers = seats.mapIndexed { i, seat ->
            val shifted = fits[i].shiftedLeft
            val right = freqs.map { f -> seat.right.at(f) ?: Complex(0.0, 0.0) }
            when (measuredWith) {
                MeasuredWith.DSP_OFF -> Pair(right, shifted) // (app LEFT feeds "R", app RIGHT feeds "L")
                MeasuredWith.DSP_ON -> Pair(shifted, right)
            }
        }

        fun seatPower(seat: Int, chain: Array<Array<Complex>>): DoubleArray {
            val (leftSpk, rightSpk) = speakers[seat]
            return DoubleArray(freqs.size) { i ->
                val gL = if (measuredWith == MeasuredWith.DSP_OFF) chain[0][i] else chain[0][i] / current[0][i]
                val gR = if (measuredWith == MeasuredWith.DSP_OFF) chain[1][i] else chain[1][i] / current[1][i]
                (leftSpk[i] * gL + rightSpk[i] * gR).power()
            }
        }

        val band = freqs.map { it in SCORE_LOW_HZ..SCORE_HIGH_HZ }
        val before = seats.indices.map { smoothDb(seatPower(it, current)) }

        fun score(after: List<DoubleArray>): Double {
            var total = 0.0
            for (s in seats.indices) {
                var sum = 0.0
                var n = 0
                for (i in freqs.indices) if (band[i]) {
                    val d = after[s][i] - before[s][i]
                    sum += if (d > 0) d else 2 * d
                    n++
                }
                total += sum / n
            }
            return total / seats.size
        }

        var best: Triple<Proposal, Double, List<DoubleArray>>? = null
        val candidates = buildList {
            for (side in BmwOutputChannel.entries) {
                var fc = GRID_MIN_HZ
                while (fc <= GRID_MAX_HZ) {
                    for (q in GRID_Q) add(Proposal(side, fc, q))
                    fc *= GRID_RATIO
                }
            }
        }
        val trial = values.copyOf()
        candidates.forEachIndexed { k, c ->
            if (!isActive()) return@forEachIndexed
            values.copyInto(trial)
            for ((index, value) in updatesFor(c)) trial[index] = value
            val chain = appChain(trial, peq)
            val after = seats.indices.map { smoothDb(seatPower(it, chain)) }
            val sc = score(after)
            if (best == null || sc > best!!.second) best = Triple(c, sc, after)
            if (k % 16 == 0) onProgress(k / candidates.size.toFloat())
        }
        onProgress(1f)

        val winner = best?.takeIf { it.second >= MIN_IMPROVEMENT_DB }
        val proposal = winner?.first
        val updates = proposal?.let { updatesFor(it) }.orEmpty()
        val replaces = proposal != null && sectionTwoIndices(proposal.side).any { values[it] >= .5f }
        return Result(
            proposal = proposal,
            scoreDb = winner?.second ?: 0.0,
            seats = seats.mapIndexed { i, seat ->
                SeatResult(
                    name = seat.name,
                    frequencies = freqs,
                    beforeDb = before[i],
                    afterDb = winner?.third?.get(i) ?: before[i],
                    fitRmsDb = fits[i].rmsDb,
                    shiftMs = fits[i].shiftSeconds * 1000.0,
                )
            },
            updates = updates,
            replacesExisting = replaces,
        )
    }

    private class SeatFit(val shiftedLeft: List<Complex>, val rmsDb: Double?, val shiftSeconds: Double)

    /**
     * Lines the seat's left measurement up in time with its right one. REW's delay notes usually
     * do that already; with a both-channels measurement the residual offset is searched for
     * (+-4 ms) and kept only when it fits clearly better than none.
     */
    private fun fitSeat(seat: Seat, freqs: DoubleArray): SeatFit {
        val l = freqs.map { seat.left.at(it) ?: Complex(0.0, 0.0) }
        val r = freqs.map { seat.right.at(it) ?: Complex(0.0, 0.0) }
        val both = seat.both ?: return SeatFit(l, null, 0.0)
        val target = freqs.map { f -> both.at(f)?.let { 10 * log10(it.power().coerceAtLeast(1e-30)) } }
        val fitBand = freqs.indices.filter { freqs[it] in 30.0..500.0 && target[it] != null }
        fun rms(tau: Double): Double {
            val err = fitBand.map { i ->
                val shift = Complex.polar(1.0, -2 * PI * freqs[i] * tau)
                10 * log10((l[i] * shift + r[i]).power().coerceAtLeast(1e-30)) - target[i]!!
            }
            val mean = err.average()
            return sqrt(err.sumOf { (it - mean) * (it - mean) } / err.size)
        }
        val atZero = rms(0.0)
        var bestTau = 0.0
        var bestRms = atZero
        var tau = -MAX_SHIFT_S
        while (tau <= MAX_SHIFT_S) {
            val e = rms(tau)
            if (e < bestRms) {
                bestRms = e
                bestTau = tau
            }
            tau += SHIFT_STEP_S
        }
        if (atZero - bestRms < 0.3) {
            bestTau = 0.0
            bestRms = atZero
        }
        val shifted = l.mapIndexed { i, c -> c * Complex.polar(1.0, -2 * PI * freqs[i] * bestTau) }
        return SeatFit(shifted, bestRms, bestTau)
    }

    private val enabledValues = FloatArray(NativeBmwDspValues.SIZE)

    /**
     * The app chain per channel ([0] left, [1] right) for [values], on the solver's axis --
     * always as it runs with the processor switched on, which is how the proposal will be heard
     * (the user may well have SiphonDSP off right now, having just measured the factory system;
     * modelled off, every candidate would be the same identity and none could win).
     */
    private fun appChain(values: FloatArray, peq: BmwPeqState): Array<Array<Complex>> {
        values.copyInto(enabledValues)
        enabledValues[NativeBmwDspValues.INDEX_ENABLED] = 1f
        calculator.invalidateAll()
        calculator.compute(enabledValues, peq, curves)
        return Array(2) { ch ->
            Array(POINTS) { i -> Complex.polar(10.0.pow(curves.sumDb[ch][i] / 20.0), curves.sumPhase[ch][i]) }
        }
    }

    /** Power-averages over +-1/24 octave and returns dB, so a comb's fine teeth don't drive the choice. */
    private fun smoothDb(power: DoubleArray): DoubleArray {
        val pointsPerOctave = (POINTS - 1) / (log10(MAX_HZ / MIN_HZ) / log10(2.0))
        val half = (pointsPerOctave / 24.0).toInt().coerceAtLeast(1)
        return DoubleArray(power.size) { i ->
            var s = 0.0
            var n = 0
            for (j in (i - half).coerceAtLeast(0)..(i + half).coerceAtMost(power.size - 1)) {
                s += power[j]
                n++
            }
            10 * log10((s / n).coerceAtLeast(1e-30))
        }
    }

    companion object {
        const val POINTS = 220
        const val MIN_HZ = 20.0
        const val MAX_HZ = 1000.0
        const val SCORE_LOW_HZ = 30.0
        const val SCORE_HIGH_HZ = 400.0
        const val GRID_MIN_HZ = 60.0
        const val GRID_MAX_HZ = 320.0
        const val GRID_RATIO = 1.04
        val GRID_Q = doubleArrayOf(0.3, 0.4, 0.5, 0.7, 1.0)
        /** Below this mean improvement Auto Align recommends leaving things as they are. */
        const val MIN_IMPROVEMENT_DB = 0.5
        private const val MAX_SHIFT_S = 0.004
        private const val SHIFT_STEP_S = 0.00002

        /** The all-pass section Auto Align writes to (0-based: "section 2" on the All-pass page). */
        const val SECTION = 1

        private fun outputsOf(side: BmwOutputChannel) = if (side == BmwOutputChannel.LEFT) {
            Triple(NativeBmwDspValues.OUTPUT_LOW_LEFT, NativeBmwDspValues.OUTPUT_MID_LEFT, NativeBmwDspValues.OUTPUT_HIGH_LEFT)
        } else {
            Triple(NativeBmwDspValues.OUTPUT_LOW_RIGHT, NativeBmwDspValues.OUTPUT_MID_RIGHT, NativeBmwDspValues.OUTPUT_HIGH_RIGHT)
        }

        private fun sectionBase(output: Int) = NativeBmwDspValues.INDEX_ALL_PASS +
            (output * NativeBmwDspValues.ALL_PASS_SECTIONS_PER_OUTPUT + SECTION) * NativeBmwDspValues.ALL_PASS_SECTION_WIDTH

        /** The enable index of section 2 on each of [side]'s outputs. */
        fun sectionTwoIndices(side: BmwOutputChannel): List<Int> {
            val (low, mid, high) = outputsOf(side)
            return listOf(sectionBase(low), sectionBase(mid), NativeBmwDspValues.highAllPassIndex(high, SECTION, 0))
        }

        /** Section 2 = on, 2nd order, [Proposal.frequencyHz], [Proposal.q] on every output of the side. */
        fun updatesFor(p: Proposal): Map<Int, Float> {
            val (low, mid, high) = outputsOf(p.side)
            val out = LinkedHashMap<Int, Float>()
            for (base in listOf(sectionBase(low), sectionBase(mid), NativeBmwDspValues.highAllPassIndex(high, SECTION, 0))) {
                out[base] = 1f
                out[base + 1] = 2f
                out[base + 2] = p.frequencyHz.toFloat()
                out[base + 3] = p.q.toFloat()
            }
            return out
        }

        /** Undo for [updatesFor]: the values those indices hold in [values]. */
        fun previousValues(updates: Map<Int, Float>, values: FloatArray): Map<Int, Float> =
            updates.keys.associateWith { values[it] }

        internal fun dbAt(result: SeatResult, frequencyHz: Double, after: Boolean): Double {
            val i = result.frequencies.indices.minBy { abs(result.frequencies[it] - frequencyHz) }
            return if (after) result.afterDb[i] else result.beforeDb[i]
        }
    }
}
