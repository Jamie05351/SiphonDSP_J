package app.siphondsp.compose.controls

import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.siphondsp.view.BmwDashboardSkin

private val RingColor = Color(BmwDashboardSkin.SLIDER_DEFAULT_COLOR)
private val StrokeWidth = 2.dp
private val GlowWidth = 5.dp
private val KnobRingColor = Color.White
private val KnobStrokeWidth = 3.dp

/**
 * The hardware D-pad/rotary "you are here" indicator -- visually the same cyan glow + stroke
 * rounded-rect as the sidebar's [BmwDashboardSkin.sidebarTileFocusRingDrawable]
 * ([app.siphondsp.view.BmwSkinDrawables.TileFocusRingDrawable]), ported to Compose so every
 * clickable control shows a real, deliberate focus state instead of the default (too-subtle-to-
 * notice-on-this-dark-chrome) ripple. Draws nothing while unfocused, so touch use is unaffected.
 */
fun Modifier.bmwFocusRing(interactionSource: InteractionSource, cornerRadius: Dp = 6.dp): Modifier =
    composed {
        val focused by interactionSource.collectIsFocusedAsState()
        drawWithContent {
            drawContent()
            if (focused) drawFocusRing(cornerRadius)
        }
    }

/**
 * A ring like [bmwFocusRing], for a control whose interaction source isn't at hand: it follows the
 * focus of the next focusable modifier in the chain, so put it before the clickable. With
 * [withinChildren] it lights while anything inside has focus instead (for a wrapper whose
 * focusable part is a child). It is white, not the DSP pages' purple: the home screen is lit in
 * that purple all over, where a purple ring couldn't be seen.
 */
fun Modifier.knobFocusRing(cornerRadius: Dp = 6.dp, withinChildren: Boolean = false): Modifier =
    composed {
        var focused by remember { mutableStateOf(false) }
        onFocusChanged { focused = if (withinChildren) it.hasFocus else it.isFocused }
            .drawWithContent {
                drawContent()
                if (focused) drawFocusRing(cornerRadius, KnobRingColor, KnobStrokeWidth)
            }
    }

private fun DrawScope.drawFocusRing(cornerRadius: Dp, color: Color = RingColor, width: Dp = StrokeWidth) {
    val strokePx = width.toPx()
    val inset = strokePx / 2f
    val corner = CornerRadius(cornerRadius.toPx())
    val topLeft = androidx.compose.ui.geometry.Offset(inset, inset)
    val ringSize = Size(size.width - strokePx, size.height - strokePx)
    drawRoundRect(
        color = color,
        topLeft = topLeft,
        size = ringSize,
        cornerRadius = corner,
        style = Stroke(GlowWidth.toPx() + strokePx),
        alpha = 0.35f,
    )
    drawRoundRect(
        color = color,
        topLeft = topLeft,
        size = ringSize,
        cornerRadius = corner,
        style = Stroke(strokePx),
    )
}
