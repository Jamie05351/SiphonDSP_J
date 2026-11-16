package app.siphondsp.view

/**
 * The dB axis of every frequency-response magnitude graph (the PEQ / Pre EQ graph with its band
 * views, and the crossover graph's MAG modes): a fixed window the user picks, never fitted to
 * the curves. Gridlines every [GRID_STEP_DB]; a curve outside the window is clipped at its edge.
 * Display only.
 */
enum class GraphDbRange(val topDb: Double, val bottomDb: Double) {
    PLUS_18_MINUS_18(18.0, -18.0),
    PLUS_12_MINUS_24(12.0, -24.0),
    PLUS_24_MINUS_24(24.0, -24.0),
    ;

    /** "+12 / −24 dB". */
    val label: String
        get() = "+${topDb.toInt()} / −${(-bottomDb).toInt()} dB"

    /** The gridline levels, top to bottom, every [GRID_STEP_DB] dB (both ends included). */
    val gridLines: DoubleArray
        get() {
            val count = ((topDb - bottomDb) / GRID_STEP_DB).toInt() + 1
            return DoubleArray(count) { topDb - it * GRID_STEP_DB }
        }

    companion object {
        const val GRID_STEP_DB = 6.0
        val DEFAULT = PLUS_12_MINUS_24

        /** The stored [name], or [DEFAULT] for none or an unknown one. */
        fun fromName(name: String?): GraphDbRange = entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}
