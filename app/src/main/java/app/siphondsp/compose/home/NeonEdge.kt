package app.siphondsp.compose.home

import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.toRect
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath

/**
 * The launcher's one border style, taken from the DSP's chain tiles ([StageCard]): a solid
 * [EdgeWidth] line in [colour] and, while [glow] (0..1), a soft halo just outside it. The halo is
 * kept outside the shape, so a see-through face isn't tinted by it. Apply before the clip and
 * background, as the halo draws beyond the bounds.
 */
@Composable
internal fun Modifier.neonEdge(corner: Float, colour: Color, glow: Float = 1f): Modifier {
    val s = LocalHomeScale.current
    return drawBehind {
        if (glow <= 0f) return@drawBehind
        val spread = s.dp(EdgeHalo).toPx()
        val radius = s.dp(corner).toPx()
        val face = Path().apply { addRoundRect(RoundRect(size.toRect(), CornerRadius(radius))) }
        clipPath(face, ClipOp.Difference) {
            for (step in 1..3) {
                val o = spread * step / 3f
                drawRoundRect(
                    colour.copy(alpha = EdgeHaloAlpha * glow / step),
                    topLeft = Offset(-o, -o), size = Size(size.width + 2 * o, size.height + 2 * o),
                    cornerRadius = CornerRadius(radius + o),
                )
            }
        }
    }.border(s.dp(EdgeWidth), colour, RoundedCornerShape(s.dp(corner)))
}

/** The chain tiles' edge, in design units: the line's width, and how far and how strongly it glows. */
internal const val EdgeWidth = 2.5f
internal const val EdgeHalo = 14f
internal const val EdgeHaloAlpha = 0.10f
