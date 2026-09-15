package app.siphondsp.view

import app.siphondsp.model.NativeBmwDspValues

/**
 * Pure geometry / band-section helpers for [CompressorSurface], split out so the band-boundary
 * and axis math can be unit-tested without a Canvas.
 *
 * The surface uses a single dBFS Y-axis shared by everything it draws: the live dry/wet
 * spectrum, the per-band threshold lines, and the applied gain-reduction curve (which hangs
 * off the 0 dB reference). Frequencies in Hz on a log X-axis; see [PeqGraphMath] for the
 * frequency<->fraction mapping, reused here so there is one log-frequency implementation.
 */
object CompressorSurfaceMath {
    const val MIN_FREQUENCY = PeqGraphMath.MIN_FREQUENCY
    const val MAX_FREQUENCY = PeqGraphMath.MAX_FREQUENCY

    // -72 keeps the noise floor visible; +6 leaves room for makeup / boost above unity.
    const val MIN_DB = -72.0
    const val MAX_DB = 6.0

    val GRID_DB = doubleArrayOf(6.0, 0.0, -12.0, -24.0, -36.0, -48.0, -60.0, -72.0)

    const val BAND_COUNT = NativeBmwDspValues.MBC_BAND_COUNT

    // NativeBmwDspProcessor.h's sampleRate_ compiled-in default -- used here only when no engine
    // instance is live to report the real device rate (see splitFrequencies's sampleRateHz param).
    private const val DEFAULT_SAMPLE_RATE_HZ = 48_000.0

    /**
     * The 3 MBC split frequencies from [values] at [sampleRateHz], normalized the same two-stage
     * way NativeBmwDspProcessor is so the shaded regions line up with what the engine actually
     * crosses over at:
     *  1. configure() sorts the raw magnitudes ascending, then clamps each to its slot's own
     *     bounds, so an out-of-order stored triple still normalizes the same way regardless of
     *     which slot it was stored in.
     *  2. rebuildMbc() re-clamps against the sample-rate ceiling (0.45 * sampleRateHz), reserving
     *     the 5% spacing owed to the stage(s) above so two splits can't collapse onto one ceiling.
     */
    fun splitFrequencies(values: FloatArray, sampleRateHz: Double = DEFAULT_SAMPLE_RATE_HZ): DoubleArray {
        val raw = doubleArrayOf(
            values[NativeBmwDspValues.INDEX_MBC_XO_0].toDouble(),
            values[NativeBmwDspValues.INDEX_MBC_XO_1].toDouble(),
            values[NativeBmwDspValues.INDEX_MBC_XO_2].toDouble(),
        ).also { it.sort() }
        val slot0 = clampD(raw[0], 20.0, 2000.0)
        val slot1 = clampD(raw[1], 40.0, 8000.0)
        val slot2 = clampD(raw[2], 80.0, 20000.0)

        val ceiling = sampleRateHz * 0.45
        val f1Ceiling = ceiling / 1.05
        val f0Ceiling = f1Ceiling / 1.05
        val f0 = clampD(slot0, MIN_FREQUENCY, f0Ceiling)
        val f1 = clampD(slot1, minOf(f0 * 1.05, f1Ceiling), f1Ceiling)
        val f2 = clampD(slot2, minOf(f1 * 1.05, ceiling), ceiling)
        return doubleArrayOf(f0, f1, f2)
    }

    /** Mirrors NativeBmwDspProcessor.cpp's clampf: well-defined (returns [lo]) even if [lo] > [hi]. */
    private fun clampD(x: Double, lo: Double, hi: Double): Double = maxOf(lo, minOf(hi, x))

    /** Which of the 4 bands [frequencyHz] falls in, given ascending [splits] (length 3). */
    fun bandForFrequency(frequencyHz: Double, splits: DoubleArray): Int = when {
        frequencyHz < splits[0] -> 0
        frequencyHz < splits[1] -> 1
        frequencyHz < splits[2] -> 2
        else -> 3
    }

    /** Inclusive [lowHz, highHz] frequency span of band [band], given [splits]. */
    fun bandRange(band: Int, splits: DoubleArray): Pair<Double, Double> = when (band) {
        0 -> MIN_FREQUENCY to splits[0]
        1 -> splits[0] to splits[1]
        2 -> splits[1] to splits[2]
        else -> splits[2] to MAX_FREQUENCY
    }

    fun frequencyToFraction(frequencyHz: Double): Float =
        PeqGraphMath.frequencyToFraction(frequencyHz, MIN_FREQUENCY, MAX_FREQUENCY)

    /** 0 at [MAX_DB] (top of plot), 1 at [MIN_DB] (bottom). */
    fun dbToFraction(db: Double): Float =
        (1.0 - (db.coerceIn(MIN_DB, MAX_DB) - MIN_DB) / (MAX_DB - MIN_DB)).toFloat()

    /** dBFS a [CompressorSurface] meter's gain-reduction reading places the gain curve at. */
    fun gainCurveDbForReduction(gainReductionDb: Float): Double =
        (-gainReductionDb.toDouble()).coerceIn(MIN_DB, MAX_DB)
}
