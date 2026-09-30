package app.siphondsp.compose.controls

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
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import app.siphondsp.view.HomeArt
import app.siphondsp.view.isHeadUnitDisplay
import kotlin.math.min

/**
 * The front page's hardware faceplate, drawn live: a grained dark plate with two black glass screens
 * recessed into it (a third of the height on top, two thirds below, a strip of plate between them),
 * faint dividers between the live panel's blocks, and a slim metal bezel around the whole display.
 *
 * Everything is placed in the same art space as [HomeArt] (fit-scaled and centred, so it lines up
 * with the live views [app.siphondsp.view.HomeArtLayout] lays over it on any display aspect; the
 * plate fills the whole view, margins included). The tiles, the live panel and the power button are
 * drawn by their own views on top.
 */
@Composable
fun HomeFaceplate(modifier: Modifier = Modifier) {
    val phone = !LocalContext.current.isHeadUnitDisplay()
    val grain = remember { plateGrainBrush() }
    Canvas(modifier.fillMaxSize()) { drawFaceplate(phone, grain) }
}

private fun DrawScope.drawFaceplate(phone: Boolean, grain: ShaderBrush) {
    val imageW = if (phone) HomeArt.PHONE_IMAGE_WIDTH else HomeArt.IMAGE_WIDTH
    val imageH = if (phone) HomeArt.PHONE_IMAGE_HEIGHT else HomeArt.IMAGE_HEIGHT
    val w = size.width
    val h = size.height
    val unit = min(w / imageW, h / imageH) // one art pixel in view pixels (fit)
    fun rect(key: String): Pair<Offset, Size> {
        val px = HomeArt.map(HomeArt.frac(key, phone)!!, w.toInt(), h.toInt(), imageW, imageH)
        return Offset(px.left.toFloat(), px.top.toFloat()) to Size((px.right - px.left).toFloat(), (px.bottom - px.top).toFloat())
    }

    val corner = FaceplateCorner.toPx()
    val bezel = FaceplateBezel.toPx()

    // The real screen corners are square, so anything outside the rounded bezel stays black.
    drawRect(Color.Black)

    drawPlate(grain, corner)

    // The two screens, each sunk into the plate.
    for (key in HomeArt.SCREEN_KEYS) {
        val (at, extent) = rect(key)
        drawRecessedScreen(at, extent, unit)
    }

    // The engraved seam across the strip of plate between the two screens: a dark groove with a
    // thin highlight under it, so they read as two separate pieces of glass, not one split screen.
    val (topAt, topSize) = rect("screen_top")
    val (bottomAt, _) = rect("screen_bottom")
    val seamY = (topAt.y + topSize.height + bottomAt.y) / 2f
    drawLine(Color.Black.copy(alpha = 0.85f), Offset(0f, seamY), Offset(w, seamY), strokeWidth = 3f * unit)
    drawLine(
        Color.White.copy(alpha = 0.12f),
        Offset(0f, seamY + 2.5f * unit),
        Offset(w, seamY + 2.5f * unit),
        strokeWidth = 1.5f * unit,
    )

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

/**
 * One black glass screen sunk into the plate: a well cut around it (dark at the top where the plate
 * overhangs, catching light on its bottom lip), then the glass, then a metal hairline lit from the
 * top-left.
 */
private fun DrawScope.drawRecessedScreen(at: Offset, extent: Size, unit: Float) {
    val r = 8f * unit
    val d = 9f * unit
    val wellAt = Offset(at.x - d, at.y - d)
    val wellSize = Size(extent.width + 2f * d, extent.height + 2f * d)
    drawRoundRect(
        brush = Brush.verticalGradient(
            0f to Color.Black.copy(alpha = 0.95f),
            0.5f to Color(0xFF0D0D0F).copy(alpha = 0.9f),
            1f to Color(0xFF4A4B4E).copy(alpha = 0.9f),
            startY = wellAt.y,
            endY = wellAt.y + wellSize.height,
        ),
        topLeft = wellAt,
        size = wellSize,
        cornerRadius = CornerRadius(r + d),
    )
    // Light catching the lip below the well.
    drawLine(
        Color.White.copy(alpha = 0.10f),
        Offset(wellAt.x + r + d, wellAt.y + wellSize.height + unit),
        Offset(wellAt.x + wellSize.width - r - d, wellAt.y + wellSize.height + unit),
        strokeWidth = 2f * unit,
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
