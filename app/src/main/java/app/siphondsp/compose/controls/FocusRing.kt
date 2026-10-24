package app.siphondsp.compose.controls

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.ui.graphics.graphicsLayer
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
private val KnobStrokeWidth = 4.dp
private val KnobGlowWidth = 12.dp

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
 * that purple all over, where a purple ring couldn't be seen. With [grow] the focused control
 * (ring included) grows by 8%, past its bounds: only for a control with room around it, not one
 * that fills its parent or sits in a clipped one, where the ring's outer edge would be cut off.
 */
fun Modifier.knobFocusRing(cornerRadius: Dp = 6.dp, withinChildren: Boolean = false, grow: Boolean = false): Modifier =
    composed {
        var focused by remember { mutableStateOf(false) }
        // In the car a thin ring was hard to find at a glance, so the ring has a dark band inside it
        // that shows on light wallpaper too, and a control with room around it also grows a little.
        val scale by animateFloatAsState(if (focused && grow) KnobGrow else 1f, tween(KnobGrowMs), label = "knob focus")
        onFocusChanged { focused = if (withinChildren) it.hasFocus else it.isFocused }
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .drawWithContent {
                drawContent()
                if (focused) drawKnobRing(cornerRadius)
            }
    }

private const val KnobGrow = 1.08f
private const val KnobGrowMs = 120
private val KnobShadowWidth = 3.dp

/**
 * The knob ring, inside the control's edge (parents such as the DSP bar and the drawer's grid clip
 * anything outside): a white glow and stroke, with a dark band just inside the stroke.
 */
private fun DrawScope.drawKnobRing(cornerRadius: Dp) {
    val strokePx = KnobStrokeWidth.toPx()
    val shadowPx = KnobShadowWidth.toPx()
    val corner = cornerRadius.toPx()
    val inset = strokePx / 2f
    val ringSize = Size(size.width - strokePx, size.height - strokePx)
    val band = strokePx + shadowPx / 2f
    drawRoundRect(
        Color.Black, androidx.compose.ui.geometry.Offset(band, band), Size(size.width - band * 2, size.height - band * 2),
        CornerRadius((corner - band).coerceAtLeast(0f)), style = Stroke(shadowPx), alpha = 0.6f,
    )
    val topLeft = androidx.compose.ui.geometry.Offset(inset, inset)
    drawRoundRect(KnobRingColor, topLeft, ringSize, CornerRadius(corner), style = Stroke(KnobGlowWidth.toPx()), alpha = 0.35f)
    drawRoundRect(KnobRingColor, topLeft, ringSize, CornerRadius(corner), style = Stroke(strokePx))
}

private fun DrawScope.drawFocusRing(
    cornerRadius: Dp,
    color: Color = RingColor,
    width: Dp = StrokeWidth,
    glow: Dp = GlowWidth,
) {
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
        style = Stroke(glow.toPx()),
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
