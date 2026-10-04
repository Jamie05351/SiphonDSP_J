package app.siphondsp.compose.controls

import kotlin.math.ceil
import kotlin.math.pow

/**
 * The arithmetic behind [ValueStepper]'s −/+ buttons, kept free of Compose so it can be unit
 * tested.
 *
 * Frequencies ([isMusical]) step by a fixed ratio, [SEMITONE] (1/12 octave) per step, so a step
 * feels the same at 80 Hz as at 8 kHz; everything else steps by the control's own [step]. Either
 * way the result lands on the control's step grid and inside its range, and always moves at least
 * one grid step, so a coarse grid (50 Hz steps at 100 Hz) can't leave a button stuck.
 *
 * Holding a button repeats it: [holdInterval] and [holdMultiplier] say how fast and how far each
 * repeat goes after the button has been held for a while, so a long range can be crossed in a few
 * seconds while a short hold still moves one step at a time.
 */
internal object StepMath {

    /** 2^(1/12): one musical step (a semitone). */
    val SEMITONE: Float = 2f.pow(1f / 12f)

    /** A frequency control: stepped musically. */
    fun isMusical(unit: String): Boolean = unit.equals("Hz", ignoreCase = true) || unit.equals("kHz", ignoreCase = true)

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
        val target = if (musical && current > 0f) {
            current * SEMITONE.pow(direction * times.toFloat())
        } else {
            current + direction * step * times
        }
        var snapped = snapToStep(target, range, step)
        // A step smaller than the grid snaps back to where it started: take one grid step instead.
        if (snapped == current && step > 0f) snapped = snapToStep(current + direction * step, range, step)
        return snapped
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
     * How many steps each repeat moves, [heldMs] after repeating started. Musical controls stay at
     * one or two semitones (20 Hz to 20 kHz is 120 of them, about six seconds at full speed).
     * Linear controls speed up so that, at full speed, the whole range takes about [FULL_RANGE_REPEATS]
     * repeats however fine their step is.
     */
    fun holdMultiplier(heldMs: Long, range: ClosedFloatingPointRange<Float>, step: Float, musical: Boolean): Int {
        if (heldMs < 1_000L) return 1
        if (musical) return if (heldMs < 2_500L) 1 else 2
        val totalSteps = if (step > 0f) (range.endInclusive - range.start) / step else 0f
        val full = ceil(totalSteps / FULL_RANGE_REPEATS).toInt().coerceAtLeast(1)
        return if (heldMs < 2_500L) (full / 4).coerceAtLeast(1) else full
    }

    private const val FULL_RANGE_REPEATS = 120f
}
