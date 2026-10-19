package app.siphondsp.compose.controls

import kotlin.math.ceil

/**
 * The arithmetic behind [ValueStepper]'s −/+ buttons, kept free of Compose so it can be unit
 * tested.
 *
 * Frequencies ([isMusical]) step on a round-number ladder ([frequencyStep]): 1 Hz below 100 Hz,
 * 5 Hz to 300, 10 Hz to 1 kHz, 50 Hz to 3 kHz, 100 Hz to 10 kHz, 250 Hz above -- every value is a
 * clean number, steps are even within each range, fine where tuning happens (bass, crossovers) and
 * quick higher up. Everything else steps by the control's own [step]. Either way the result lands
 * inside the range and always moves at least one step, so a button can't stick.
 *
 * Holding a button repeats it: [holdInterval] and [holdMultiplier] say how fast and how far each
 * repeat goes after the button has been held for a while, so a long range can be crossed in a few
 * seconds while a short hold still moves one step at a time.
 */
internal object StepMath {

    /** A frequency control in Hz: stepped on the round-number ladder (which is defined in Hz). */
    fun isMusical(unit: String): Boolean = unit.equals("Hz", ignoreCase = true)

    /**
     * The value one press of − ([direction] -1) or + (+1) moves [value] to, [times] steps at once.
     */
    fun next(
        value: Float,
        range: ClosedFloatingPointRange<Float>,
        step: Float,
        direction: Int,
        musical: Boolean,
        times: Int = 1,
    ): Float {
        val current = value.coerceIn(range.start, range.endInclusive)
        if (musical) {
            var f = current.toDouble()
            repeat(times) { f = nextFrequency(f, direction, step.toDouble()) }
            return f.toFloat().coerceIn(range.start, range.endInclusive)
        }
        if (step <= 0f) return current
        // On the grid, move [times] grid steps. Off it (an old saved value, or a scaled display such
        // as the tilt's 1.5x), the first step goes to the next grid point in that direction, so +
        // and - stay symmetric: 0.15 on a 0.1 grid goes to 0.2 or 0.1, never skipping one.
        val position = (current - range.start).toDouble() / step
        val nearest = kotlin.math.round(position)
        val first = when {
            kotlin.math.abs(position - nearest) < GRID_EPSILON -> nearest + direction
            direction > 0 -> kotlin.math.ceil(position)
            else -> kotlin.math.floor(position)
        }
        val target = first + direction * (times - 1)
        return (range.start + target * step).toFloat().coerceIn(range.start, range.endInclusive)
    }

    /** How close to a grid point (in steps) still counts as on it, for float rounding. */
    private const val GRID_EPSILON = 1e-3

    /** The ladder's step size for frequencies at or above [hz] (the band [hz] sits in). */
    fun frequencyStep(hz: Double): Double = when {
        hz < 100.0 -> 1.0
        hz < 300.0 -> 5.0
        hz < 1_000.0 -> 10.0
        hz < 3_000.0 -> 50.0
        hz < 10_000.0 -> 100.0
        else -> 250.0
    }

    /**
     * One − (-1) or + (+1) step of [hz] on the ladder, landing on the ladder's grid: up uses the step
     * of the band [hz] is in, down the step of the band just below it, so 100 -> 105 going up and
     * 100 -> 99 going down. An off-grid value (an old 65.7, or a range bound) moves to the next grid
     * point that way. [minStep] is the control's own grid: the step used is the coarser of the two,
     * so a 10 Hz or 50 Hz control never lands between its own steps (every ladder step that is
     * coarser than 1 Hz is a multiple of 5, 10 or 50, so the result stays on that grid).
     */
    fun nextFrequency(hz: Double, direction: Int, minStep: Double = 0.0): Double {
        val eps = 1e-6
        return if (direction > 0) {
            val s = maxOf(frequencyStep(hz), minStep)
            (kotlin.math.floor(hz / s + eps) + 1) * s
        } else {
            val s = maxOf(frequencyStep(hz - eps), minStep)
            (kotlin.math.ceil(hz / s - eps) - 1) * s
        }
    }

    /** Before a held button starts repeating. */
    const val HOLD_DELAY_MS = 400L

    /** Time between repeats, [heldMs] after repeating started. */
    fun holdInterval(heldMs: Long): Long = when {
        heldMs < 1_000L -> 120L
        heldMs < 2_500L -> 70L
        else -> 50L
    }

    /**
     * How many steps each repeat moves, [heldMs] after repeating started. Frequency controls go 1,
     * then 2, then 4 ladder steps per repeat (20 Hz to 20 kHz is ~340 steps, a few seconds at full
     * speed).
     * Linear controls speed up so that, at full speed, the whole range takes about [FULL_RANGE_REPEATS]
     * repeats however fine their step is.
     */
    fun holdMultiplier(heldMs: Long, range: ClosedFloatingPointRange<Float>, step: Float, musical: Boolean): Int {
        if (heldMs < 1_000L) return 1
        if (musical) return if (heldMs < 2_500L) 2 else 4
        val totalSteps = if (step > 0f) (range.endInclusive - range.start) / step else 0f
        val full = ceil(totalSteps / FULL_RANGE_REPEATS).toInt().coerceAtLeast(1)
        return if (heldMs < 2_500L) (full / 4).coerceAtLeast(1) else full
    }

    private const val FULL_RANGE_REPEATS = 120f
}
