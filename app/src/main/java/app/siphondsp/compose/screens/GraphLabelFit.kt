package app.siphondsp.compose.screens

/**
 * Text scale (between [floor] and 1) at which the widest label still fits between the two closest
 * tick positions on an axis. Graphs shrink their labels with this instead of dropping any, so a
 * narrow plot (a phone, or the home-screen graphs) shows every label smaller rather than fewer.
 *
 * [floor] defaults to 0.68, which turns the 14px axis text back into roughly the original 9.5px, so
 * labels never get smaller than they used to be.
 */
internal fun graphLabelFitScale(
    minSpacingPx: Float,
    widestLabelPx: Float,
    gapPx: Float,
    floor: Float = 0.68f,
): Float {
    if (widestLabelPx <= 0f || minSpacingPx <= 0f) return 1f
    return ((minSpacingPx - gapPx) / widestLabelPx).coerceIn(floor, 1f)
}

/** Smallest distance between neighbouring values of [xs] (any order), or 0 if there are fewer than two. */
internal fun minNeighbourSpacing(xs: FloatArray): Float {
    if (xs.size < 2) return 0f
    val sorted = xs.sortedArray()
    var best = Float.MAX_VALUE
    for (i in 1 until sorted.size) best = minOf(best, sorted[i] - sorted[i - 1])
    return best
}
