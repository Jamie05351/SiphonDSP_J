package app.siphondsp.compose.controls

import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.siphondsp.view.HomeArt
import app.siphondsp.view.isHeadUnitDisplay
import kotlin.math.max
import kotlin.random.Random

/**
 * The front page's hardware faceplate, drawn live: a brushed dark plate, one recessed black glass
 * screen, faint dividers between the live panel's blocks, and a slim metal bezel around the whole
 * display.
 *
 * Everything is placed in the same art space as [HomeArt] (cover-scaled, so it lines up with the
 * live views [app.siphondsp.view.HomeArtLayout] lays over it on any display aspect). The tiles, the
 * live panel and the power button are drawn by their own views on top.
 */
@Composable
fun HomeFaceplate(modifier: Modifier = Modifier) {
    val phone = !LocalContext.current.isHeadUnitDisplay()
    val streaks = remember { brushedMetalBrush() }
    Canvas(modifier.fillMaxSize()) { drawFaceplate(phone, streaks) }
}

/** Horizontal machining streaks, generated once from a fixed seed so it never shimmers or changes. */
private fun brushedMetalBrush(): ShaderBrush {
    val bitmap = Bitmap.createBitmap(256, 128, Bitmap.Config.ARGB_8888)
    val canvas = AndroidCanvas(bitmap)
    val paint = Paint()
    val random = Random(20260929)
    repeat(140) {
        val light = random.nextBoolean()
        paint.color = android.graphics.Color.argb(
            (10 + random.nextInt(40)),
            if (light) 255 else 0, if (light) 255 else 0, if (light) 255 else 0,
        )
        val y = random.nextInt(128).toFloat()
        val x = random.nextInt(256).toFloat()
        canvas.drawRect(x, y, x + 40f + random.nextInt(216), y + 1f, paint)
    }
    return ShaderBrush(ImageShader(bitmap.asImageBitmap(), TileMode.Repeated, TileMode.Repeated))
}

private fun DrawScope.drawFaceplate(phone: Boolean, streaks: ShaderBrush) {
    val imageW = if (phone) HomeArt.PHONE_IMAGE_WIDTH else HomeArt.IMAGE_WIDTH
    val imageH = if (phone) HomeArt.PHONE_IMAGE_HEIGHT else HomeArt.IMAGE_HEIGHT
    val w = size.width
    val h = size.height
    val unit = max(w / imageW, h / imageH) // one art pixel in view pixels (cover)
    fun rect(key: String): Pair<Offset, Size> {
        val px = HomeArt.map(HomeArt.frac(key, phone)!!, w.toInt(), h.toInt(), imageW, imageH)
        return Offset(px.left.toFloat(), px.top.toFloat()) to Size((px.right - px.left).toFloat(), (px.bottom - px.top).toFloat())
    }

    val corner = 12.dp.toPx()
    val bezel = 4.dp.toPx()

    // The real screen corners are square, so anything outside the rounded bezel stays black.
    drawRect(Color.Black)

    // Plate.
    drawRoundRect(
        brush = Brush.verticalGradient(listOf(Color(0xFF2A2A2C), Color(0xFF0E0E0F))),
        cornerRadius = CornerRadius(corner),
    )
    drawRoundRect(brush = streaks, alpha = 0.55f, cornerRadius = CornerRadius(corner))

    // The one screen.
    val (screenAt, screenSize) = rect("screen")
    drawScreen(screenAt, screenSize, unit)

    // Faint dividers between the live panel's blocks, inset from their top and bottom.
    val blocks = HomeArt.LIVE_KEYS.map { rect(it) }
    blocks.zipWithNext { (leftAt, leftSize), (rightAt, _) ->
        val x = (leftAt.x + leftSize.width + rightAt.x) / 2f
        val inset = leftSize.height * 0.1f
        drawLine(
            Color.White.copy(alpha = 0.12f),
            Offset(x, leftAt.y + inset),
            Offset(x, leftAt.y + leftSize.height - inset),
            strokeWidth = 2f * unit,
        )
    }

    // Slim metal bezel around the whole display.
    drawBezelRing(0f, 0f, w, h, corner, bezel)
}

/** One recessed black glass screen: a dark rim, the glass, then a metal hairline lit from top-left. */
private fun DrawScope.drawScreen(at: Offset, extent: Size, unit: Float) {
    val r = 8f * unit
    drawRoundRect(
        color = Color.Black.copy(alpha = 0.9f),
        topLeft = Offset(at.x - 3f * unit, at.y - 3f * unit),
        size = Size(extent.width + 6f * unit, extent.height + 6f * unit),
        cornerRadius = CornerRadius(r + 3f * unit),
        style = Stroke(6f * unit),
    )
    drawRoundRect(Color.Black, at, extent, CornerRadius(r))
    drawRoundRect(
        brush = Brush.linearGradient(
            0f to Color.White.copy(alpha = 0.55f), 0.35f to Color.White.copy(alpha = 0.05f), 1f to Color.White.copy(alpha = 0.25f),
            start = at, end = Offset(at.x + extent.width, at.y + extent.height),
        ),
        topLeft = at,
        size = extent,
        cornerRadius = CornerRadius(r),
        style = Stroke(1.6f * unit),
    )
}
