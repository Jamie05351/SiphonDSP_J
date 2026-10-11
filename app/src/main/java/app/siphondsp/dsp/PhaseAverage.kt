package app.siphondsp.dsp

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * The average of two wrapped phases (radians), as a direction: the angle of the sum of their unit
 * phasors. An arithmetic mean breaks across the wrap -- +179 deg and -179 deg average to 0 deg
 * instead of 180 deg. NaN inputs are skipped (NaN only when both are); two exactly opposite
 * phases have no average and give NaN, so a graph breaks its line instead of drawing a false 0.
 */
fun averagePhaseRad(left: Double, right: Double): Double {
    if (left.isNaN()) return right
    if (right.isNaN()) return left
    val x = cos(left) + cos(right)
    val y = sin(left) + sin(right)
    return if (x * x + y * y < 1e-18) Double.NaN else atan2(y, x)
}
