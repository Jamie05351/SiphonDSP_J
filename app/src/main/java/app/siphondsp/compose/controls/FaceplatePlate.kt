package app.siphondsp.compose.controls

import android.graphics.Bitmap
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.dp
import kotlin.random.Random

/**
 * The hardware faceplate's plate and bezel, shared so every surface made of it matches: the front
 * page ([HomeFaceplate]) and the workspace sidebar ([DspSidebarRail]). Draw [drawPlate], then
 * [drawBezelRing] with [FaceplateCorner] and [FaceplateBezel].
 */
internal val FaceplateCorner = 12.dp
internal val FaceplateBezel = 4.dp

/**
 * The plate's fine grain: a 512x512 tile of per-pixel speckle, generated once from a fixed seed so it
 * never shimmers or changes. Each pixel is a faint white or black fleck (or nothing), so the grain
 * adds texture without lightening or darkening the plate. Unlike the old brushed-metal streaks,
 * random speckle has no shapes for the eye to catch repeating when the tile is laid across a wide
 * plate, at any screen density.
 */
internal fun plateGrainBrush(): ShaderBrush {
    val size = GrainTileSize
    val pixels = IntArray(size * size)
    val random = Random(20261001)
    for (i in pixels.indices) {
        val alpha = random.nextInt(GrainMaxAlpha + 1)
        pixels[i] = if (random.nextBoolean()) {
            android.graphics.Color.argb(alpha, 255, 255, 255)
        } else {
            android.graphics.Color.argb(alpha, 0, 0, 0)
        }
    }
    val bitmap = Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
    return ShaderBrush(ImageShader(bitmap.asImageBitmap(), TileMode.Repeated, TileMode.Repeated))
}

/**
 * The plate over the whole draw area: a smooth near-black gradient, a touch lighter at the top, with the fine
 * [grain] over it, rounded to [corner]. (It was brushed-metal streaks from a small repeated tile,
 * which showed as a busy repeating pattern, worst on high-density phones.)
 */
internal fun DrawScope.drawPlate(grain: ShaderBrush, corner: Float) {
    drawRoundRect(
        brush = Brush.verticalGradient(listOf(PlateTop, PlateBottom)),
        cornerRadius = CornerRadius(corner),
    )
    drawRoundRect(brush = grain, cornerRadius = CornerRadius(corner))
}

private const val GrainTileSize = 512
// Fleck strength: up to ~5% white or black per pixel. Enough to read as a surface, not as noise.
private const val GrainMaxAlpha = 14
private val PlateTop = Color(0xFF151517)
private val PlateBottom = Color(0xFF09090A)
