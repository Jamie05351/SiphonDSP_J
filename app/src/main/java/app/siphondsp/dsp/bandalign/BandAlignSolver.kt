package app.siphondsp.dsp.bandalign

import app.siphondsp.dsp.BmwOutputChannel
import app.siphondsp.model.NativeBmwDspValues
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Band Align: the Low band's delay and polarity, against Mid, that make one side's two bands add
 * up best through their crossover -- at every seat measured, together.
 *
 * The inputs are REW measurements taken through the tune as it's set now, one side at a time:
 * that side's Low band alone (Mid and High muted) and its Mid band alone (Low and High muted),
 * at each seat. Each holds everything that band actually produces in the car -- the app's
 * crossover, delay and polarity, and the factory amp's own split across its speakers after it.
 * A delay and a polarity flip are exact on top of a measurement (a pure time shift and a sign),
 * so a candidate Low band is the measured one moved by the difference from the current setting:
 * no model of the crossovers is needed, and none can be wrong.
 *
 * Each candidate is scored by what the side's Low + Mid sum does through the crossover region
 * (see [scoreBand]), power-smoothed over +-1/24 octave, averaged over the region with cuts
 * counted double, and over the seats. A candidate that leaves any seat worse off on that
 * measure is rejected outright, so one seat is never traded for another. Only the Low band's
 * timing against everything else and its polarity change. Its delay is searched from 0-2.8 ms and
 * below 0 too, as far as the other delays leave room ([minDelayMs]): Low can't play early, so a
 * negative answer is written by [applyUpdates] as Low at 0 and every other delay on both sides
 * later by the same amount, which keeps Mid / High and Left / Right timing exactly as tuned.
 */
class BandAlignSolver {

    /** One seat's measurements of one side. [both] (Low and Mid together) is optional: it checks the timing. */
    data class SeatMeasurements(
        val seat: String,
        val low: RewMeasurement,
        val mid: RewMeasurement,
        val both: RewMeasurement?,
    )

    data class Setting(val delayMs: Double, val inverted: Boolean)

    data class SeatResult(
        val name: String,
        val frequencies: DoubleArray,
        /** The side's Low + Mid sum, smoothed, as set now. */
        val beforeDb: DoubleArray,
        /** The same with the proposal (equal to [beforeDb] when there is none). */
        val afterDb: DoubleArray,
        /** How closely Low + Mid reproduce the both-bands measurement, RMS dB (null without one). */
        val fitRmsDb: Double?,
        /** The Low/Mid timing correction the fit settled on, ms (0 without one). */
        val shiftMs: Double,
        /** True when the fit had a rival offset as good, so REW's own timing was kept. */
        val timingAmbiguous: Boolean = false,
    )

    data class SideResult(
        val side: BmwOutputChannel,
        val current: Setting,
        /** Null when nothing in range beats the current setting by [MIN_IMPROVEMENT_DB]. */
        val proposal: Setting?,
        /** Mean change over the scored band across the seats, dB (cuts counted double). */
        val scoreDb: Double,
        val bandHz: ClosedFloatingPointRange<Double>,
        val seats: List<SeatResult>,
        /** True when the best delay sits at an end of the range: it may want more than the range allows. */
        val atRangeLimit: Boolean,
    )

    val frequencies: DoubleArray = DoubleArray(POINTS) { i -> MIN_HZ * (MAX_HZ / MIN_HZ).pow(i / (POINTS - 1.0)) }

    fun solveSide(
        side: BmwOutputChannel,
        seats: List<SeatMeasurements>,
        current: Setting,
        bandHz: ClosedFloatingPointRange<Double>,
        isActive: () -> Boolean = { true },
        /** How far below 0 the Low delay may go (0 or negative): see [minDelayMs]. */
        minDelayMs: Double = 0.0,
    ): SideResult {
        require(seats.isNotEmpty()) { "at least one seat" }
        require(minDelayMs <= 0.0) { "minDelayMs is 0 or below" }
        val f = frequencies
        val fits = seats.map { fitSeat(it) }
        val mids = seats.map { s -> f.map { s.mid.at(it) ?: Complex(0.0, 0.0) } }
        val inBand = BooleanArray(f.size) { f[it] in bandHz }
        val covered = seats.map { s ->
            // Mid may start above the bottom of the range (its sweep began there): below its
            // data it's silent, as a high-passed Mid is, so it doesn't narrow what's scored.
            val ms = listOfNotNull(s.low, s.mid, s.both)
            val lo = listOfNotNull(s.low, s.both).maxOf { it.frequencies.first() }
            val hi = ms.minOf { it.frequencies.last() }
            BooleanArray(f.size) { f[it] in lo..hi }
        }

        fun sideDb(seat: Int, setting: Setting): DoubleArray {
            val low = fits[seat].low
            val dt = (setting.delayMs - current.delayMs) / 1000.0
            val sign = if (setting.inverted != current.inverted) -1.0 else 1.0
            val power = DoubleArray(f.size) { i ->
                val moved = low[i] * Complex.polar(sign, -2 * PI * f[i] * dt)
                (moved + mids[seat][i]).power()
            }
            val db = smoothDb(power)
            return DoubleArray(db.size) { if (covered[seat][it]) db[it] else Double.NaN }
        }

        val before = seats.indices.map { sideDb(it, current) }
        /** The mean over the seats, or NaN when any seat ends up worse: no seat is traded for another. */
        fun score(after: List<DoubleArray>): Double {
            var total = 0.0
            for (s in seats.indices) {
                var sum = 0.0
                var n = 0
                for (i in f.indices) if (inBand[i]) {
                    val d = after[s][i] - before[s][i]
                    if (!d.isFinite()) continue
                    sum += if (d > 0) d else 2 * d
                    n++
                }
                val seatScore = if (n > 0) sum / n else 0.0
                if (seatScore < 0) return Double.NaN
                total += seatScore
            }
            return total / seats.size
        }

        // Nearest-to-current first, so a tie keeps the smaller change.
        val steps = (DELAY_MAX_MS / DELAY_STEP_MS).roundToInt()
        val firstStep = (minDelayMs / DELAY_STEP_MS).roundToInt()
        val candidates = buildList {
            for (k in firstStep..steps) for (inv in listOf(current.inverted, !current.inverted)) {
                add(Setting((k * DELAY_STEP_MS).roundTo(DELAY_STEP_MS), inv))
            }
        }.sortedBy { abs(it.delayMs - current.delayMs) + if (it.inverted != current.inverted) 1e-6 else 0.0 }

        var best: Triple<Setting, Double, List<DoubleArray>>? = null
        for (c in candidates) {
            if (!isActive()) break
            val after = seats.indices.map { sideDb(it, c) }
            val sc = score(after)
            if (sc.isFinite() && (best == null || sc > best.second + 1e-9)) best = Triple(c, sc, after)
        }
        val winner = best?.takeIf { it.second >= MIN_IMPROVEMENT_DB && it.first != current }
        val proposal = winner?.first
        return SideResult(
            side = side,
            current = current,
            proposal = proposal,
            scoreDb = winner?.second ?: 0.0,
            bandHz = bandHz,
            seats = seats.mapIndexed { i, s ->
                SeatResult(
                    name = s.seat,
                    frequencies = f,
                    beforeDb = before[i],
                    afterDb = winner?.third?.get(i) ?: before[i],
                    fitRmsDb = fits[i].rmsDb,
                    shiftMs = fits[i].shiftSeconds * 1000.0,
                    timingAmbiguous = fits[i].ambiguous,
                )
            },
            atRangeLimit = proposal != null && (proposal.delayMs <= minDelayMs + 1e-6 || proposal.delayMs >= DELAY_MAX_MS - 1e-6),
        )
    }

    private class SeatFit(val low: List<Complex>, val rmsDb: Double?, val shiftSeconds: Double, val ambiguous: Boolean = false)

    /**
     * Lines the Low measurement up in time with the Mid one. REW's delay notes do that when both
     * were taken against the same acoustic timing reference; with a both-bands measurement the
     * residual offset is searched for (+-2 ms) and kept only when it fits clearly better than none
     * and no mirror-image offset fits about as well.
     */
    private fun fitSeat(seat: SeatMeasurements): SeatFit {
        val f = frequencies
        val low = f.map { seat.low.at(it) ?: Complex(0.0, 0.0) }
        val mid = f.map { seat.mid.at(it) ?: Complex(0.0, 0.0) }
        val both = seat.both ?: return SeatFit(low, null, 0.0)
        val target = f.map { hz -> both.at(hz)?.let { 10 * log10(it.power().coerceAtLeast(1e-30)) } }
        val fitBand = f.indices.filter { f[it] in FIT_LOW_HZ..FIT_HIGH_HZ && target[it] != null }
        if (fitBand.size < MIN_FIT_POINTS) return SeatFit(low, null, 0.0)
        fun rms(tau: Double): Double {
            val err = fitBand.map { i ->
                val shift = Complex.polar(1.0, -2 * PI * f[i] * tau)
                10 * log10((low[i] * shift + mid[i]).power().coerceAtLeast(1e-30)) - target[i]!!
            }
            val mean = err.average()
            return sqrt(err.sumOf { (it - mean) * (it - mean) } / err.size)
        }
        val atZero = rms(0.0)
        val fits = ArrayList<Pair<Double, Double>>()
        var bestTau = 0.0
        var bestRms = atZero
        var tau = -MAX_SHIFT_S
        while (tau <= MAX_SHIFT_S + 1e-12) {
            val e = rms(tau)
            fits += tau to e
            if (e < bestRms) {
                bestRms = e
                bestTau = tau
            }
            tau += SHIFT_STEP_S
        }
        if (atZero - bestRms < KEEP_SHIFT_DB) return SeatFit(low, atZero, 0.0)
        val rival = fits.indices
            .filter { k ->
                k > 0 && k < fits.size - 1 && abs(fits[k].first - bestTau) > RIVAL_DISTANCE_S &&
                    fits[k].second <= fits[k - 1].second && fits[k].second <= fits[k + 1].second
            }
            .minOfOrNull { fits[it].second }
        if (rival != null && rival - bestRms < AMBIGUOUS_FIT_DB) return SeatFit(low, atZero, 0.0, ambiguous = true)
        val shifted = low.mapIndexed { i, c -> c * Complex.polar(1.0, -2 * PI * f[i] * bestTau) }
        return SeatFit(shifted, bestRms, bestTau)
    }

    private fun smoothDb(power: DoubleArray): DoubleArray {
        val pointsPerOctave = (POINTS - 1) / (ln(MAX_HZ / MIN_HZ) / ln(2.0))
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
        const val POINTS = 240
        const val MIN_HZ = 20.0
        const val MAX_HZ = 1000.0
        /** The Gains & Delay page's delay range and step. */
        const val DELAY_MAX_MS = 2.8
        const val DELAY_STEP_MS = 0.01
        /** Below this mean improvement Band Align recommends leaving the side as it is. */
        const val MIN_IMPROVEMENT_DB = 0.5
        private const val FIT_LOW_HZ = 30.0
        private const val FIT_HIGH_HZ = 500.0
        private const val MAX_SHIFT_S = 0.002
        private const val SHIFT_STEP_S = 0.00002
        private const val MIN_FIT_POINTS = 8
        private const val KEEP_SHIFT_DB = 0.3
        private const val AMBIGUOUS_FIT_DB = 0.2
        private const val RIVAL_DISTANCE_S = 0.0003

        private fun lowOutput(side: BmwOutputChannel) =
            if (side == BmwOutputChannel.LEFT) NativeBmwDspValues.OUTPUT_LOW_LEFT else NativeBmwDspValues.OUTPUT_LOW_RIGHT

        private fun midOutput(side: BmwOutputChannel) =
            if (side == BmwOutputChannel.LEFT) NativeBmwDspValues.OUTPUT_MID_LEFT else NativeBmwDspValues.OUTPUT_MID_RIGHT

        /** The Mid high-pass corner, Hz; 0 or below when Mid has none. */
        fun midHighPassHz(values: FloatArray, side: BmwOutputChannel): Double =
            values[NativeBmwDspValues.outputIndex(midOutput(side), NativeBmwDspValues.FIELD_CROSSOVER_FREQ)].toDouble()

        /**
         * Why a Mid-only measurement can't be used with this tune, or null: it has to start an
         * octave or more below the Mid high-pass, where Mid is well down, so the band where Mid
         * and Low overlap is in it. Below its data Mid is taken as silent.
         */
        fun midStartProblem(mid: RewMeasurement, values: FloatArray, side: BmwOutputChannel): String? {
            val hp = midHighPassHz(values, side)
            val needed = if (hp > 0.0) hp / 2.0 else RewMeasurement.MIN_COVER_HZ
            val start = mid.frequencies.first()
            if (start <= needed) return null
            return if (hp > 0.0) {
                "${mid.name} starts at ${start.roundToInt()} Hz, but Mid's high-pass is at ${hp.roundToInt()} Hz: " +
                    "measure Mid from ${needed.roundToInt()} Hz or lower (an octave below), so its overlap with Low is in it."
            } else {
                "${mid.name} starts at ${start.roundToInt()} Hz, but Mid has no high-pass: measure it from ${needed.roundToInt()} Hz."
            }
        }

        fun delayIndex(side: BmwOutputChannel) =
            if (side == BmwOutputChannel.LEFT) NativeBmwDspValues.INDEX_LOW_DELAY_L else NativeBmwDspValues.INDEX_LOW_DELAY_R

        fun invertIndex(side: BmwOutputChannel) = NativeBmwDspValues.outputIndex(lowOutput(side), NativeBmwDspValues.FIELD_INVERT)

        fun currentSetting(values: FloatArray, side: BmwOutputChannel) =
            Setting(values[delayIndex(side)].toDouble().roundTo(DELAY_STEP_MS), values[invertIndex(side)] >= .5f)

        /**
         * How much later [applyUpdates] makes every delay: the most negative proposed Low delay,
         * made positive, across both sides (0 when none is negative). One offset serves both.
         */
        fun sharedOffsetMs(results: List<SideResult>): Double =
            (-(results.mapNotNull { it.proposal?.delayMs }.minOrNull() ?: 0.0)).coerceAtLeast(0.0)

        /** The six band delays, High / Mid / Low on each side. */
        val BandDelays = listOf(
            NativeBmwDspValues.INDEX_HIGH_DELAY_L, NativeBmwDspValues.INDEX_HIGH_DELAY_R,
            NativeBmwDspValues.INDEX_MID_DELAY_L, NativeBmwDspValues.INDEX_MID_DELAY_R,
            NativeBmwDspValues.INDEX_LOW_DELAY_L, NativeBmwDspValues.INDEX_LOW_DELAY_R,
        )

        /**
         * How far below 0 [side]'s Low delay can be searched: as far as the other five delays can
         * all be made later without passing [DELAY_MAX_MS]. 0 when one is already at the top.
         */
        fun minDelayMs(values: FloatArray, side: BmwOutputChannel): Double {
            val highest = BandDelays.filter { it != delayIndex(side) }.maxOf { values[it].toDouble() }
            return -(DELAY_MAX_MS - highest).coerceAtLeast(0.0).roundTo(DELAY_STEP_MS)
        }

        /**
         * What applying every side's proposal writes: each proposed side's Low delay and polarity.
         * A Low delay below 0 can't play, so the most negative one sets an offset that's added to
         * all six delays: that Low lands at 0 and nothing else changes relative to anything but it.
         * Only delays that actually change are written. Null when that would put a delay past
         * [DELAY_MAX_MS] (another side's proposal used the room up); empty with no proposals.
         */
        fun applyUpdates(values: FloatArray, results: List<SideResult>): Map<Int, Float>? {
            val proposals = results.mapNotNull { r -> r.proposal?.let { r.side to it } }
            if (proposals.isEmpty()) return emptyMap()
            val delays = LinkedHashMap<Int, Double>()
            for (i in BandDelays) delays[i] = values[i].toDouble()
            for ((side, p) in proposals) delays[delayIndex(side)] = p.delayMs
            val offset = sharedOffsetMs(results)
            if (offset > 0.0) delays.replaceAll { _, d -> d + offset }
            if (delays.values.any { it > DELAY_MAX_MS + 1e-6 }) return null
            val out = LinkedHashMap<Int, Float>()
            for ((i, d) in delays) {
                val v = d.roundTo(DELAY_STEP_MS).coerceIn(0.0, DELAY_MAX_MS).toFloat()
                if (abs(v - values[i]) > 1e-4f) out[i] = v
            }
            for ((side, p) in proposals) out[invertIndex(side)] = if (p.inverted) 1f else 0f
            return out
        }

        /**
         * The crossover region the side is scored over: from 2.5x below to 2.5x above the
         * geometric centre of its Low low-pass and Mid high-pass corners, held within 40-400 Hz.
         */
        fun scoreBand(values: FloatArray, side: BmwOutputChannel): ClosedFloatingPointRange<Double> {
            val lp = values[NativeBmwDspValues.outputIndex(lowOutput(side), NativeBmwDspValues.FIELD_CROSSOVER_FREQ)].toDouble()
            val hp = values[NativeBmwDspValues.outputIndex(midOutput(side), NativeBmwDspValues.FIELD_CROSSOVER_FREQ)].toDouble()
            val centre = if (lp > 0 && hp > 0) sqrt(lp * hp) else 130.0
            val lo = (centre / 2.5).coerceIn(40.0, 200.0)
            val hi = (centre * 2.5).coerceIn(lo * 2, 400.0)
            return lo..hi
        }

        private fun Double.roundTo(step: Double) = (this / step).roundToInt() * step
    }
}
