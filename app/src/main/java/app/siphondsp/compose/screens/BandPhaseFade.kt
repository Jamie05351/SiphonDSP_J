package app.siphondsp.compose.screens

/**
 * Where the crossover PHASE graph draws a band's phase: only where the band is actually playing.
 * Far outside its passband a band is tens of dB down and its phase, though defined, says nothing
 * about what you hear -- it just spins and clutters the plot. So a band's phase line is drawn at
 * full strength down to [FULL_BELOW_PASSBAND_DB] under its passband, fades out to nothing at
 * [HIDDEN_BELOW_PASSBAND_DB], and isn't drawn beyond. The passband is the band's own peak level
 * across the plot. The sum is never faded.
 */
internal object BandPhaseFade {
    const val FULL_BELOW_PASSBAND_DB = 15.0
    const val HIDDEN_BELOW_PASSBAND_DB = 20.0
    /** At or below this a band is silent (muted, or the calculator's -120 dB floor): nothing to draw. */
    const val SILENT_DB = -100.0

    /** The band's passband level: its peak, ignoring non-finite points; null if it never plays. */
    fun passbandDb(db: DoubleArray): Double? {
        var peak = Double.NEGATIVE_INFINITY
        for (v in db) if (v.isFinite() && v > peak) peak = v
        return if (peak > SILENT_DB) peak else null
    }

    /** 1 within [FULL_BELOW_PASSBAND_DB] of [passbandDb], 0 from [HIDDEN_BELOW_PASSBAND_DB] on, linear between. */
    fun strength(db: Double, passbandDb: Double): Float {
        if (!db.isFinite()) return 0f
        val below = passbandDb - db
        return when {
            below <= FULL_BELOW_PASSBAND_DB -> 1f
            below >= HIDDEN_BELOW_PASSBAND_DB -> 0f
            else -> ((HIDDEN_BELOW_PASSBAND_DB - below) / (HIDDEN_BELOW_PASSBAND_DB - FULL_BELOW_PASSBAND_DB)).toFloat()
        }
    }

    /**
     * Per segment i -> i+1 (size `db.size - 1`), the strength to draw it at, quantised to [levels]
     * steps so runs of equal strength can be drawn as one path: the weaker of its two ends, so a
     * segment never reaches past where the band is hidden. Each value is k / levels, k in 0..levels.
     */
    fun segmentStrengths(db: DoubleArray, levels: Int = 8): FloatArray {
        val n = db.size
        if (n < 2) return FloatArray(0)
        val passband = passbandDb(db) ?: return FloatArray(n - 1)
        val out = FloatArray(n - 1)
        var prev = strength(db[0], passband)
        for (i in 0 until n - 1) {
            val next = strength(db[i + 1], passband)
            val s = minOf(prev, next)
            // Round up, so only a truly hidden end gives 0; anything visible keeps at least 1 step.
            out[i] = if (s <= 0f) 0f else kotlin.math.ceil(s * levels) / levels
            prev = next
        }
        return out
    }
}
