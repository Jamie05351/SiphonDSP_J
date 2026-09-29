package app.siphondsp.compose.controls

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.unit.dp
import app.siphondsp.view.BmwDashboardSkin

/**
 * The recessed display box behind every boxed slider-row title and value readout.
 *
 * A near-black recessed body with an inner top shadow (so it reads as sunk into the panel), a soft
 * accent wash rising from the bottom edge, a bottom lip highlight, an inner hairline, then the
 * accent ring and a faint outer accent glow. [borderAlpha] lets titles sit back at ~0.6 so the
 * value readout is the brightest thing in the row. [showBorder] = false draws the body only.
 */
fun Modifier.bmwGlassBox(
    accentColor: Color,
    showBorder: Boolean = true,
    borderAlpha: Float = 1f,
): Modifier = this.drawBehind {
    val ringPx = GlassBoxStrokeWidth.toPx()
    val w = size.width
    val h = size.height
    if (w <= 0f || h <= 0f) return@drawBehind
    val corner = CornerRadius(GlassBoxCornerRadius.toPx())
    val a = borderAlpha.coerceIn(0f, 1f)

    if (showBorder) {
        // Outer accent glow (drawn just outside the box).
        val g = 1.dp.toPx()
        drawRoundRect(
            color = accentColor.copy(alpha = 0.12f * a),
            topLeft = Offset(-g, -g),
            size = Size(w + 2 * g, h + 2 * g),
            cornerRadius = CornerRadius(corner.x + g),
            style = Stroke(3.dp.toPx()),
        )
    }

    // Recessed body.
    drawRoundRect(
        brush = Brush.verticalGradient(0f to BodyTop, 0.35f to BodyMid, 1f to BodyBottom),
        cornerRadius = corner,
    )

    val clip = Path().apply { addRoundRect(RoundRect(0f, 0f, w, h, corner)) }
    clipPath(clip) {
        // Inner top shadow.
        val shadowH = 7.dp.toPx()
        drawRect(
            brush = Brush.verticalGradient(
                listOf(Color.Black.copy(alpha = 0.65f), Color.Transparent),
                startY = 0f, endY = shadowH,
            ),
            size = Size(w, shadowH),
        )
        // Accent wash rising from the bottom edge.
        if (showBorder) {
            drawRect(
                brush = Brush.verticalGradient(
                    listOf(Color.Transparent, accentColor.copy(alpha = 0.16f * a)),
                    startY = h * 0.35f, endY = h,
                ),
            )
        }
        // Bottom lip highlight.
        drawRect(
            color = Color.White.copy(alpha = 0.10f),
            topLeft = Offset(corner.x, h - 1.dp.toPx()),
            size = Size(w - 2f * corner.x, 1.dp.toPx()),
        )
    }

    if (showBorder) {
        // Inner hairline, then the accent ring on top.
        val hair = 2.dp.toPx()
        drawRoundRect(
            color = Color.White.copy(alpha = 0.06f),
            topLeft = Offset(hair, hair),
            size = Size(w - 2 * hair, h - 2 * hair),
            cornerRadius = CornerRadius((corner.x - 1.5.dp.toPx()).coerceAtLeast(0f)),
            style = Stroke(1.dp.toPx()),
        )
        val half = ringPx / 2f
        drawRoundRect(
            color = accentColor.copy(alpha = a),
            topLeft = Offset(half, half),
            size = Size(w - ringPx, h - ringPx),
            cornerRadius = corner,
            style = Stroke(ringPx),
        )
    }
}

internal val GlassBoxCornerRadius = BmwDashboardSkin.GLASS_BOX_CORNER_RADIUS_DP.dp
private val GlassBoxStrokeWidth = BmwDashboardSkin.GLASS_BOX_STROKE_WIDTH_DP.dp

private val BodyTop = Color(0xFF050608)
private val BodyMid = Color(0xFF0B0D10)
private val BodyBottom = Color(0xFF14171B)
