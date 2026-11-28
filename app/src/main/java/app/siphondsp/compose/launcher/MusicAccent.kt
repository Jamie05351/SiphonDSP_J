package app.siphondsp.compose.launcher

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toPixelMap

/**
 * The album art's accent for the music tile's edge and glow: the most vivid colour that is common
 * in the art, from a coarse grid of samples. Null for art that is near grey throughout (black and
 * white covers), where the player's own colour reads better.
 */
internal fun artAccent(full: ImageBitmap): Color? {
    // A 24 x 24 copy is all the sampling needs: never the whole (possibly 1000 px) art's pixels.
    val art = android.graphics.Bitmap.createScaledBitmap(full.asAndroidBitmap(), AccentSamples, AccentSamples, true).asImageBitmap()
    val step = 1
    val pixels = art.toPixelMap(0, 0, art.width, art.height)
    // Hue buckets of 30°, weighted by saturation and brightness so a vivid area wins over a big dull one.
    val weight = FloatArray(12)
    val r = FloatArray(12)
    val g = FloatArray(12)
    val b = FloatArray(12)
    val hsv = FloatArray(3)
    var y = 0
    while (y < art.height) {
        var x = 0
        while (x < art.width) {
            val c = pixels[x, y]
            android.graphics.Color.RGBToHSV((c.red * 255).toInt(), (c.green * 255).toInt(), (c.blue * 255).toInt(), hsv)
            val sat = hsv[1]
            val value = hsv[2]
            if (sat > 0.25f && value > 0.25f) {
                val bucket = ((hsv[0] / 30f).toInt()).coerceIn(0, 11)
                val w = sat * value
                weight[bucket] += w
                r[bucket] += c.red * w
                g[bucket] += c.green * w
                b[bucket] += c.blue * w
            }
            x += step
        }
        y += step
    }
    val best = weight.indices.maxByOrNull { weight[it] } ?: return null
    val total = weight.sum()
    if (weight[best] <= 0f || total <= 0f) return null
    val w = weight[best]
    // Lifted to full brightness so it glows like the other tiles' edges.
    android.graphics.Color.RGBToHSV((r[best] / w * 255).toInt(), (g[best] / w * 255).toInt(), (b[best] / w * 255).toInt(), hsv)
    hsv[1] = hsv[1].coerceAtLeast(0.55f)
    hsv[2] = 1f
    return Color(android.graphics.Color.HSVToColor(hsv))
}

private const val AccentSamples = 24

/** "1:23" / "1:02:03" for [ms]. */
internal fun formatPlayTime(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
