package app.siphondsp.view

/**
 * The last [capacity] frames of post-DSP output level, L and R, for the front page's output scope.
 * One frame is pushed per meter tick (about 20 a second); once full, the oldest frame drops off.
 * Allocation-free after construction, and read on the same (main) thread that pushes.
 *
 * Each frame holds four dBFS values: L RMS, L peak, R RMS, R peak. [age] 0 is the newest frame.
 */
class LevelHistory(val capacity: Int = DEFAULT_CAPACITY) {
    init {
        require(capacity > 0) { "capacity must be positive" }
    }

    private val values = FloatArray(capacity * 4) { LevelReadout.FLOOR_DB }
    private var next = 0

    /** How many frames have been pushed since the last [clear], up to [capacity]. */
    var size = 0
        private set

    fun push(leftRmsDb: Float, leftPeakDb: Float, rightRmsDb: Float, rightPeakDb: Float) {
        val i = next * 4
        values[i] = leftRmsDb
        values[i + 1] = leftPeakDb
        values[i + 2] = rightRmsDb
        values[i + 3] = rightPeakDb
        next = (next + 1) % capacity
        if (size < capacity) size++
    }

    /** Back to silence: every frame at the floor. */
    fun clear() {
        values.fill(LevelReadout.FLOOR_DB)
        next = 0
        size = 0
    }

    fun leftRmsDb(age: Int) = valueAt(age, 0)
    fun leftPeakDb(age: Int) = valueAt(age, 1)
    fun rightRmsDb(age: Int) = valueAt(age, 2)
    fun rightPeakDb(age: Int) = valueAt(age, 3)

    /** Frames older than [size] read as the floor, so a fresh history draws as silence. */
    private fun valueAt(age: Int, field: Int): Float {
        if (age < 0 || age >= size) return LevelReadout.FLOOR_DB
        val slot = ((next - 1 - age) % capacity + capacity) % capacity
        return values[slot * 4 + field]
    }

    companion object {
        /** About 2.8 s of history at the meter's 20 frames a second. */
        const val DEFAULT_CAPACITY = 56
    }
}
