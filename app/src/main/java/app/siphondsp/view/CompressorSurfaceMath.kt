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

    /**
     * The 3 MBC split frequencies from [values], clamped monotonic with the same 5% spacing
     * NativeBmwDspProcessor::rebuildMbc enforces, so the shaded regions line up with what the
     * engine actually crosses over at.
     */
    fun splitFrequencies(values: FloatArray): DoubleArray {
        val f0 = values[NativeBmwDspValues.INDEX_MBC_XO_0].toDouble().coerceIn(MIN_FREQUENCY, MAX_FREQUENCY)
        val f1 = values[NativeBmwDspValues.INDEX_MBC_XO_1].toDouble().coerceIn(f0 * 1.05, MAX_FREQUENCY)
        val f2 = values[NativeBmwDspValues.INDEX_MBC_XO_2].toDouble().coerceIn(f1 * 1.05, MAX_FREQUENCY)
        return doubleArrayOf(f0, f1, f2)
    }

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
