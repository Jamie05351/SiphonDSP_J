package app.siphondsp.view

/** One snapshot of the output levels for the front page's readout, all in dBFS. */
data class LevelReadout(
    val leftRmsDb: Float,
    val leftPeakDb: Float,
    val rightRmsDb: Float,
    val rightPeakDb: Float,
) {
    /** True when there is no signal worth a number: both peaks are at the meter's floor. */
    val silent: Boolean get() = maxOf(leftPeakDb, rightPeakDb) <= FLOOR_DB

    /**
     * How far the louder channel's held peak sits below [ceilingDb] (the limiter threshold while
     * the limiter is on, else 0 dBFS), never negative; null while [silent].
     */
    fun headroomDb(ceilingDb: Float): Float? =
        if (silent) null else (ceilingDb - maxOf(leftPeakDb, rightPeakDb)).coerceAtLeast(0f)

    companion object {
        /** The meters' floor: anything at or below it reads as no signal. */
        const val FLOOR_DB = -60f
        val SILENT = LevelReadout(FLOOR_DB, FLOOR_DB, FLOOR_DB, FLOOR_DB)
    }
}
