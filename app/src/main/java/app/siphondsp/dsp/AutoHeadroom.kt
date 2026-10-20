package app.siphondsp.dsp

import app.siphondsp.model.BmwPeqState
import app.siphondsp.model.NativeBmwDspValues
import kotlin.math.max
import kotlin.math.min

/**
 * Automatic headroom: just enough cut ahead of the band split that the loudest point of the
 * response the PEQ graph draws -- preamp + Pre EQ, and each audible band's crossover, band PEQ and
 * gain -- sits [MARGIN_DB] below full scale. Worst band and channel wins. Tilt, loudness, post
 * gain and the master volume come after the band sum and aren't part of it.
 *
 * Pure: the same values and PEQ always give the same headroom, so the service (which sends it to
 * the engine) and the screens (which show it and draw with it) agree without passing it around.
 */
object AutoHeadroom {
    const val MARGIN_DB = 1.5

    fun isAuto(values: FloatArray): Boolean =
        values.getOrElse(NativeBmwDspValues.INDEX_HEADROOM_AUTO) { 0f } >= .5f

    /** Headroom for a response whose loudest point is [peakDb]: [MARGIN_DB] below 0 dBFS, never
     *  a boost, and no deeper than the headroom range allows. */
    fun headroomForPeak(peakDb: Double): Float {
        val wanted = -(peakDb + MARGIN_DB)
        return wanted.coerceIn(NativeBmwDspValues.HEADROOM_MIN_DB.toDouble(), NativeBmwDspValues.HEADROOM_MAX_DB.toDouble())
            .toFloat()
    }

    /**
     * The loudest point, in dB, of Pre EQ's curve and every audible band's branch curve over both
     * channels, from [curves] computed with 0 dB headroom. A muted band's curve is silent and so
     * never wins; a disabled processor is a flat 0 dB.
     */
    fun peakDb(curves: BmwResponseCurves): Double {
        var peak = Double.NEGATIVE_INFINITY
        for (ch in 0 until 2) {
            peak = max(peak, curves.preSplitDb[ch].maxFinite())
            if (curves.lowBranchActive) peak = max(peak, curves.lowBranchDb[ch].maxFinite())
            if (curves.midBranchActive) peak = max(peak, curves.midBranchDb[ch].maxFinite())
            if (curves.highBranchActive) peak = max(peak, curves.highBranchDb[ch].maxFinite())
        }
        return if (peak.isFinite()) peak else 0.0
    }

    private fun DoubleArray.maxFinite(): Double {
        var m = Double.NEGATIVE_INFINITY
        for (v in this) if (v.isFinite() && v > m) m = v
        return m
    }
}

/**
 * Evaluates [AutoHeadroom] for a configuration. Holds its own response models and scratch, so it
 * allocates nothing per call after the first -- but it isn't thread-safe: each thread (the
 * service's config thread, a graph) keeps its own.
 *
 * A fixed grid can step over a narrow peak (a Q-30 bell lands up to ~0.5 dB short of its top
 * depending on where the grid points fall), so the coarse pass only finds where the peaks are:
 * every local maximum within [CANDIDATE_WINDOW_DB] of the highest is then re-evaluated on a fine
 * grid spanning its two neighbours, which brings the error down to thousandths of a dB.
 */
class AutoHeadroomCalculator(private val pointCount: Int = DEFAULT_POINT_COUNT) {
    private val coarse = BmwResponseCalculator(pointCount)
    private val coarseCurves = BmwResponseCurves(pointCount)
    private val fine = BmwResponseCalculator(FINE_POINT_COUNT)
    private val fineCurves = BmwResponseCurves(FINE_POINT_COUNT)
    private val zeroHeadroom = FloatArray(NativeBmwDspValues.SIZE)
    private val candidateIndex = IntArray(MAX_CANDIDATES)
    private val candidateDb = DoubleArray(MAX_CANDIDATES)

    /** The automatic headroom for [values] and [peq], whether or not auto is switched on. */
    fun headroomDb(values: FloatArray, peq: BmwPeqState, sampleRate: Double): Float =
        AutoHeadroom.headroomForPeak(peakDb(values, peq, sampleRate))

    /** The loudest point of the response [AutoHeadroom] measures, at 0 dB headroom. */
    fun peakDb(values: FloatArray, peq: BmwPeqState, sampleRate: Double): Double {
        require(values.size == NativeBmwDspValues.SIZE) { "expected ${NativeBmwDspValues.SIZE} values, got ${values.size}" }
        values.copyInto(zeroHeadroom)
        zeroHeadroom[NativeBmwDspValues.INDEX_HEADROOM] = 0f
        val maxFrequency = min(20_000.0, sampleRate * 0.5 * 0.999)
        coarse.configureAxis(sampleRate, 20.0, maxFrequency)
        coarse.invalidateAll()
        coarse.compute(zeroHeadroom, peq, coarseCurves)
        var peak = AutoHeadroom.peakDb(coarseCurves)
        if (!coarseCurves.processorEnabled) return peak

        val candidates = findCandidates(peak)
        val frequencies = coarseCurves.frequencies
        for (c in 0 until candidates) {
            val i = candidateIndex[c]
            fine.configureAxis(sampleRate, frequencies[max(i - 1, 0)], frequencies[min(i + 1, pointCount - 1)])
            fine.invalidateAll()
            fine.compute(zeroHeadroom, peq, fineCurves)
            peak = max(peak, AutoHeadroom.peakDb(fineCurves))
        }
        return peak
    }

    /**
     * Fills [candidateIndex] with the coarse grid's local maxima -- across every curve
     * [AutoHeadroom.peakDb] reads -- within [CANDIDATE_WINDOW_DB] of [coarsePeak], keeping the
     * [MAX_CANDIDATES] highest. Returns how many it kept.
     */
    private fun findCandidates(coarsePeak: Double): Int {
        var count = 0
        fun offer(index: Int, db: Double) {
            for (k in 0 until count) {
                if (candidateIndex[k] == index) {
                    if (db > candidateDb[k]) candidateDb[k] = db
                    return
                }
            }
            if (count < MAX_CANDIDATES) {
                candidateIndex[count] = index
                candidateDb[count] = db
                count++
                return
            }
            var lowest = 0
            for (k in 1 until count) if (candidateDb[k] < candidateDb[lowest]) lowest = k
            if (db > candidateDb[lowest]) {
                candidateIndex[lowest] = index
                candidateDb[lowest] = db
            }
        }
        fun scan(curve: DoubleArray) {
            val floor = coarsePeak - CANDIDATE_WINDOW_DB
            for (i in curve.indices) {
                val v = curve[i]
                if (!v.isFinite() || v < floor) continue
                val left = if (i > 0) curve[i - 1] else Double.NEGATIVE_INFINITY
                val right = if (i < curve.size - 1) curve[i + 1] else Double.NEGATIVE_INFINITY
                if (v >= left && v >= right) offer(i, v)
            }
        }
        for (ch in 0 until 2) {
            scan(coarseCurves.preSplitDb[ch])
            if (coarseCurves.lowBranchActive) scan(coarseCurves.lowBranchDb[ch])
            if (coarseCurves.midBranchActive) scan(coarseCurves.midBranchDb[ch])
            if (coarseCurves.highBranchActive) scan(coarseCurves.highBranchDb[ch])
        }
        return count
    }

    /**
     * [values] as the engine should run them: with auto on, a copy whose headroom is the computed
     * one (the manual value stays in [values] for when auto goes off); with it off, [values].
     */
    fun effectiveValues(values: FloatArray, peq: BmwPeqState, sampleRate: Double): FloatArray {
        if (!AutoHeadroom.isAuto(values) || values.size != NativeBmwDspValues.SIZE) return values
        val headroom = headroomDb(values, peq, sampleRate)
        return values.copyOf().also { it[NativeBmwDspValues.INDEX_HEADROOM] = headroom }
    }

    companion object {
        const val DEFAULT_POINT_COUNT = 512
        // Each refinement spans two coarse steps.
        private const val FINE_POINT_COUNT = 65
        private const val CANDIDATE_WINDOW_DB = 6.0
        private const val MAX_CANDIDATES = 8
    }
}
