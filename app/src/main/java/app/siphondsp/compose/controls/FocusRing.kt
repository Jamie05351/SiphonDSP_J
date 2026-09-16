package app.siphondsp.compose.controls

import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.runtime.getValue
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

private val RingColor = Color(BmwDashboardSkin.LIGHT_BLUE_BRIGHT)
private val StrokeWidth = 2.dp
private val GlowWidth = 5.dp

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
            if (!focused) return@drawWithContent
            val strokePx = StrokeWidth.toPx()
            val inset = strokePx / 2f
            val corner = CornerRadius(cornerRadius.toPx())
            val topLeft = androidx.compose.ui.geometry.Offset(inset, inset)
            val ringSize = Size(size.width - strokePx, size.height - strokePx)
            drawRoundRect(
                color = RingColor,
                topLeft = topLeft,
                size = ringSize,
                cornerRadius = corner,
                style = Stroke(GlowWidth.toPx()),
                alpha = 0.35f,
            )
            drawRoundRect(
                color = RingColor,
                topLeft = topLeft,
                size = ringSize,
                cornerRadius = corner,
                style = Stroke(strokePx),
            )
        }
    }
