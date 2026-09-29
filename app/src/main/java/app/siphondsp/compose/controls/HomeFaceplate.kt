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
 * The front page's hardware faceplate, drawn live instead of baked into an image: brushed dark
 * plates, a seam between the two bands, three recessed top screens and one bottom screen, the
 * decorative volume knob, and a slim metal bezel around the whole screen.
 *
 * Everything is placed in the same art space as [HomeArt] (cover-scaled, so it lines up with the
 * live views [app.siphondsp.view.HomeArtLayout] lays over it on any display aspect), using the
 * `screen_*` and `knob` rects. The live views (graphs, level bars, status strip) sit on the black
 * screens; the seven tiles and the power button are drawn by their own views on top.
 *
 * The volume knob is decoration only: volume is the car's, not the app's.
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
    val scale = max(w / imageW, h / imageH) // art px -> view px (cover)
    fun rect(key: String): Pair<Offset, Size> {
        val px = HomeArt.map(HomeArt.frac(key, phone)!!, w.toInt(), h.toInt(), imageW, imageH)
        return Offset(px.left.toFloat(), px.top.toFloat()) to Size((px.right - px.left).toFloat(), (px.bottom - px.top).toFloat())
    }

    val corner = 12.dp.toPx()
    val bezel = 4.dp.toPx()
    val unit = scale // one art pixel in view pixels

    // The real screen corners are square, so anything outside the rounded bezel stays black.
    drawRect(Color.Black)

    // Plates.
    drawRoundRect(
        brush = Brush.verticalGradient(listOf(Color(0xFF2A2A2C), Color(0xFF0E0E0F))),
        cornerRadius = CornerRadius(corner),
    )
    drawRoundRect(brush = streaks, alpha = 0.55f, cornerRadius = CornerRadius(corner))

    // Seam between the top band and the bottom band.
    val top = rect("screen_left")
    val bottom = rect("screen_bottom")
    val seamY = ((top.first.y + top.second.height) + bottom.first.y) / 2f
    drawLine(Color.Black.copy(alpha = 0.85f), Offset(0f, seamY), Offset(w, seamY), strokeWidth = 3f * unit)
    drawLine(Color.White.copy(alpha = 0.14f), Offset(0f, seamY + 3f * unit), Offset(w, seamY + 3f * unit), strokeWidth = 1.5f * unit)

    // Screens.
    for (key in listOf("screen_left", "screen_centre", "screen_right", "screen_bottom")) {
        val (at, extent) = rect(key)
        drawScreen(at, extent, unit)
    }

    // Decorative volume knob (the car controls volume).
    val (knobAt, knobSize) = rect("knob")
    val radius = knobSize.width / 2f
    val centre = Offset(knobAt.x + radius, knobAt.y + knobSize.height / 2f)
    drawKnob(KnobBrushes(radius, centre), fraction = 0.55f, accent = Color(0xFF2110F7))

    // Slim metal bezel around the whole screen.
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
