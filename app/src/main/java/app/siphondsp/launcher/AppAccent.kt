package app.siphondsp.launcher

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * An app's primary colour, read from its icon, for the home tile's neon edge: the tile it opens
 * then wears the app's own colour (Spotify green, Waze blue...) rather than the tile's fixed accent.
 *
 * Every opaque, clearly coloured pixel votes for its hue, weighted by how vivid it is (saturation x
 * brightness), so a big flat background beats a small badge and a white glyph or dark outline
 * doesn't count at all. The winning hue band's pixels are averaged, then lifted to an edge-light
 * brightness and saturation so a dark-red or muted-blue icon still glows. An icon with too little
 * colour to say (black-and-white, grey) gives null, and the tile keeps its own accent.
 *
 * Pure (ARGB ints in, ARGB int out) so it is unit-tested on the JVM.
 */
object AppAccent {
    /** [argb] pixels (as Android packs them) -> the edge colour as ARGB, or null for no clear colour. */
    fun of(argb: IntArray): Int? {
        val weight = DoubleArray(BANDS)
        val sumR = DoubleArray(BANDS)
        val sumG = DoubleArray(BANDS)
        val sumB = DoubleArray(BANDS)
        var opaque = 0
        for (p in argb) {
            if ((p ushr 24) < MIN_ALPHA) continue
            opaque++
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            val hsv = hsv(r, g, b)
            val s = hsv[1]
            val v = hsv[2]
            if (s < MIN_SATURATION || v < MIN_VALUE) continue
            val band = ((hsv[0] / 360f) * BANDS).toInt().coerceIn(0, BANDS - 1)
            val w = (s * v).toDouble()
            weight[band] += w
            sumR[band] += r * w
            sumG[band] += g * w
            sumB[band] += b * w
        }
        if (opaque == 0) return null
        // A hue spans the edge of two bands as often as not: score each band with its neighbours.
        var best = -1
        var bestScore = 0.0
        for (i in 0 until BANDS) {
            val score = weight[i] + 0.5 * (weight[(i + BANDS - 1) % BANDS] + weight[(i + 1) % BANDS])
            if (score > bestScore) {
                bestScore = score
                best = i
            }
        }
        // Too little colour to call it the app's colour: a few coloured pixels on a grey icon.
        if (best < 0 || weight.sum() < MIN_COLOURED_SHARE * opaque) return null
        var w = 0.0
        var r = 0.0
        var g = 0.0
        var b = 0.0
        for (j in -1..1) {
            val k = (best + j + BANDS) % BANDS
            w += weight[k]; r += sumR[k]; g += sumG[k]; b += sumB[k]
        }
        val mean = hsv((r / w).roundToInt(), (g / w).roundToInt(), (b / w).roundToInt())
        return fromHsv(mean[0], max(mean[1], EDGE_SATURATION), max(mean[2], EDGE_VALUE))
    }

    /** RGB 0..255 -> floatArrayOf(hue 0..360, saturation 0..1, value 0..1). */
    internal fun hsv(r: Int, g: Int, b: Int): FloatArray {
        val rf = r / 255f
        val gf = g / 255f
        val bf = b / 255f
        val mx = max(rf, max(gf, bf))
        val mn = min(rf, min(gf, bf))
        val d = mx - mn
        val h = when {
            d == 0f -> 0f
            mx == rf -> 60f * (((gf - bf) / d) % 6f)
            mx == gf -> 60f * (((bf - rf) / d) + 2f)
            else -> 60f * (((rf - gf) / d) + 4f)
        }.let { if (it < 0f) it + 360f else it }
        return floatArrayOf(h, if (mx == 0f) 0f else d / mx, mx)
    }

    /** Hue 0..360, saturation and value 0..1 -> opaque ARGB. */
    internal fun fromHsv(h: Float, s: Float, v: Float): Int {
        val c = v * s
        val x = c * (1f - abs((h / 60f) % 2f - 1f))
        val m = v - c
        val (r, g, b) = when ((h / 60f).toInt().coerceIn(0, 5)) {
            0 -> Triple(c, x, 0f)
            1 -> Triple(x, c, 0f)
            2 -> Triple(0f, c, x)
            3 -> Triple(0f, x, c)
            4 -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        fun ch(f: Float) = ((f + m) * 255f).roundToInt().coerceIn(0, 255)
        return (0xFF shl 24) or (ch(r) shl 16) or (ch(g) shl 8) or ch(b)
    }

    private const val BANDS = 24 // 15 degrees each
    private const val MIN_ALPHA = 128
    private const val MIN_SATURATION = 0.3f
    private const val MIN_VALUE = 0.2f
    // Weighted coloured pixels must add up to at least this share of the opaque ones.
    private const val MIN_COLOURED_SHARE = 0.06
    // An edge light: at least this vivid and this bright, whatever the icon's own shade.
    private const val EDGE_SATURATION = 0.6f
    private const val EDGE_VALUE = 0.9f
}
