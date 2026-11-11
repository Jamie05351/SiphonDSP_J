package app.siphondsp.compose.controls

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.max

/**
 * The workspace screen's backdrop: the front page's facia over the whole screen, with the content
 * window to the right of the sidebar column ([sidebarWidth]), running to the screen's top, right
 * and bottom edges. The facia shows only round the sidebar screen that [DspSidebarRail] draws on top.
 *
 * The window is a plain near-black gradient, a little lighter at the top, with a faint wash of
 * [accent] (the workspace's tile colour on the front page's DSP bar) glowing from its top-left
 * corner, by the sidebar. Its rim is a [RimNeon] line in [accent], outermost, so on the screen's
 * edges it is the screen's border, with a faint glow inwards.
 */
@Composable
fun DspWorkspaceBackdrop(sidebarWidth: Dp, accent: Color, modifier: Modifier = Modifier) {
    Spacer(
        modifier
            .fillMaxSize()
            .drawBehind {
                drawRect(FaciaColour)
                drawContentWindow(contentWindowRect(size, sidebarWidth.toPx()), accent)
            },
    )
}

/**
 * The content window in [window]: the gradient and [accent] wash, shaded at its edges so it reads
 * as recessed, inside its [accent] rim. [alpha] fades it as a whole.
 */
internal fun DrawScope.drawContentWindow(window: Rect, accent: Color, alpha: Float = 1f) {
    if (alpha <= 0f) return
    val corner = ContentWindowCorner.toPx()
    translate(window.left, window.top) {
        drawWash(corner, window.size, accent, alpha)
        drawInsetShadow(corner, depth = 9.5.dp.toPx(), window.size, alpha)
        drawRim(corner, window.size, accent, alpha)
    }
}

/** The window's face over [area]: the dark gradient, then the [accent] glow from the top-left. */
private fun DrawScope.drawWash(corner: Float, area: Size, accent: Color, alpha: Float) {
    val radius = CornerRadius(corner)
    drawRoundRect(
        brush = Brush.verticalGradient(listOf(WindowTop, WindowBottom), endY = area.height),
        size = area,
        cornerRadius = radius,
        alpha = alpha,
    )
    drawRoundRect(
        brush = Brush.radialGradient(
            0f to accent.copy(alpha = WashAlpha),
            0.45f to accent.copy(alpha = WashAlpha * 0.35f),
            1f to Color.Transparent,
            center = Offset.Zero,
            radius = max(area.width, area.height) * WashReach,
        ),
        size = area,
        cornerRadius = radius,
        alpha = alpha,
    )
}

/** The window's gradient (top, bottom), and how strongly and how far its [accent] wash reaches. */
private val WindowTop = Color(0xFF1C1E24)
private val WindowBottom = Color(0xFF0F1014)
private const val WashAlpha = 0.16f
private const val WashReach = 0.75f

/** The window's rim over [area]: the neon line outermost, then its glow. */
private fun DrawScope.drawRim(corner: Float, area: Size, accent: Color, alpha: Float) {
    /** A stroke [width] wide whose outer edge is [inset] in from the window's edge. */
    fun ring(colour: Color, inset: Float, width: Float) = drawRoundRect(
        color = colour,
        topLeft = Offset(inset + width / 2f, inset + width / 2f),
        size = Size(area.width - 2 * inset - width, area.height - 2 * inset - width),
        cornerRadius = CornerRadius(max(0f, corner - inset - width / 2f)),
        style = Stroke(width = width),
    )
    val neon = RimNeon.toPx()
    ring(accent.copy(alpha = alpha), 0f, neon)
    // The glow: three fading bands just inside the line, as the tiles' halo but turned inwards.
    val glow = RimGlow.toPx() / 3f
    for (step in 1..3) {
        ring(accent.copy(alpha = RimGlowAlpha * alpha / step), neon + glow * (step - 1), glow)
    }
}

/** The rim's neon line and the line's inward glow. */
private val RimNeon = 2.dp
private val RimGlow = 6.dp
private const val RimGlowAlpha = 0.14f

/** Dark strokes fading inwards from the edge of [area], so the window reads as recessed. */
private fun DrawScope.drawInsetShadow(corner: Float, depth: Float, area: Size, alpha: Float, steps: Int = 4) {
    val step = depth / steps
    for (i in 0 until steps) {
        drawRoundRect(
            color = Color.Black.copy(alpha = (0.34f - i * 0.08f) * alpha),
            topLeft = Offset(i * step / 2f, i * step / 2f),
            size = Size(area.width - i * step, area.height - i * step),
            cornerRadius = CornerRadius(corner),
            style = Stroke(width = step * 2f),
        )
    }
}
