package app.siphondsp.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppAccentTest {

    private val size = 48

    /** An icon [size] px square: [paint] gives each pixel's ARGB. */
    private fun icon(paint: (x: Int, y: Int) -> Int) = IntArray(size * size) { paint(it % size, it / size) }

    private fun inCircle(x: Int, y: Int, r: Float): Boolean {
        val dx = x - size / 2f + 0.5f
        val dy = y - size / 2f + 0.5f
        return dx * dx + dy * dy <= r * r
    }

    private fun hue(argb: Int) = AppAccent.hsv((argb shr 16) and 0xFF, (argb shr 8) and 0xFF, argb and 0xFF)[0]

    private fun assertHue(expected: Float, argb: Int?, tolerance: Float = 12f) {
        assertNotNull("expected a colour", argb)
        val h = hue(argb!!)
        val diff = minOf(kotlin.math.abs(h - expected), 360f - kotlin.math.abs(h - expected))
        assertTrue("hue $h, expected $expected", diff <= tolerance)
    }

    @Test
    fun aGreenRoundIconWithDarkMarksReadsGreen() {
        // A green disc on a transparent square, with dark curved strokes across it.
        val green = 0xFF1ED760.toInt()
        val px = icon { x, y ->
            when {
                !inCircle(x, y, 22f) -> 0x00000000
                y % 9 in 0..1 -> 0xFF121212.toInt()
                else -> green
            }
        }
        assertHue(141f, AppAccent.of(px))
    }

    @Test
    fun aSmallBadgeDoesNotOutvoteTheBackground() {
        // A blue tile with a small red notification badge in its corner.
        val px = icon { x, y -> if (x > 38 && y < 10) 0xFFE53935.toInt() else 0xFF2F6FED.toInt() }
        assertHue(220f, AppAccent.of(px))
    }

    @Test
    fun aBlackAndWhiteIconHasNoColour() {
        val px = icon { x, y -> if (inCircle(x, y, 12f)) 0xFFFFFFFF.toInt() else 0xFF000000.toInt() }
        assertNull(AppAccent.of(px))
    }

    @Test
    fun aGreyIconWithATinySpeckOfColourHasNoColour() {
        val px = icon { x, y -> if (x == 3 && y == 3) 0xFF00C853.toInt() else 0xFF8A8A8A.toInt() }
        assertNull(AppAccent.of(px))
    }

    @Test
    fun aTransparentIconHasNoColour() {
        assertNull(AppAccent.of(IntArray(size * size)))
        assertNull(AppAccent.of(IntArray(0)))
    }

    @Test
    fun aDarkMutedIconIsLiftedToAnEdgeLight() {
        // Deep maroon: the edge still needs to glow.
        val px = icon { _, _ -> 0xFF5A1E24.toInt() }
        val argb = AppAccent.of(px)
        assertHue(354f, argb, tolerance = 8f)
        val hsv = AppAccent.hsv((argb!! shr 16) and 0xFF, (argb shr 8) and 0xFF, argb and 0xFF)
        assertTrue("value ${hsv[2]}", hsv[2] >= 0.89f)
        assertTrue("saturation ${hsv[1]}", hsv[1] >= 0.59f)
    }

    @Test
    fun redsEitherSideOfZeroDegreesCountTogether() {
        // Half the pixels at hue ~358, half at ~3: one red, not split and lost to a smaller blue.
        val px = icon { x, y ->
            when {
                y < 10 -> 0xFF1E5BFF.toInt()
                x % 2 == 0 -> 0xFFFF0A14.toInt()
                else -> 0xFFFF140A.toInt()
            }
        }
        assertHue(0f, AppAccent.of(px), tolerance = 6f)
    }

    @Test
    fun hsvRoundTrips() {
        for (argb in intArrayOf(0xFF1ED760.toInt(), 0xFF2F6FED.toInt(), 0xFFE53935.toInt(), 0xFFFFC107.toInt())) {
            val hsv = AppAccent.hsv((argb shr 16) and 0xFF, (argb shr 8) and 0xFF, argb and 0xFF)
            val back = AppAccent.fromHsv(hsv[0], hsv[1], hsv[2])
            for (shift in intArrayOf(16, 8, 0)) {
                assertEquals("channel $shift of ${Integer.toHexString(argb)}", ((argb shr shift) and 0xFF).toDouble(), ((back shr shift) and 0xFF).toDouble(), 1.0)
            }
        }
    }
}
