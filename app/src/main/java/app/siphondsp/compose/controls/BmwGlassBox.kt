package app.siphondsp.compose.controls

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.unit.dp
import app.siphondsp.view.BmwDashboardSkin

/**
 * Compose port of `BmwSkinDrawables.kt`'s `GlassBoxDrawable` -- the dark glass rounded box that
 * backs every boxed slider-row title and value readout in the View dashboard. Translucent
 * top-to-bottom fill gradient, a diagonal light sweep in the top-left corner, top glint / bottom
 * shadow edge lines, and (when [showBorder]) a border flat-tinted to [accentColor] (the row's
 * slider colour). Values 1:1 with `BmwDashboardSkin.GLASS_BOX_*` / `GLASS_FILL_*` constants.
 */
fun Modifier.bmwGlassBox(accentColor: Color, showBorder: Boolean = true): Modifier = this.drawBehind {
    val strokePx = GlassBoxStrokeWidth.toPx()
    val full = Rect(0f, 0f, size.width, size.height)
    val rect = if (showBorder) {
        Rect(full.left + strokePx / 2f, full.top + strokePx / 2f, full.right - strokePx / 2f, full.bottom - strokePx / 2f)
    } else {
        full
    }
    if (rect.width <= 0f || rect.height <= 0f) return@drawBehind
    val corner = CornerRadius(GlassBoxCornerRadius.toPx())
    val w = rect.width

    drawRoundRect(
        brush = Brush.linearGradient(
            0f to GlassFillTop, 0.4f to GlassFillMid, 1f to GlassFillBottom,
            start = Offset(rect.left, rect.top),
            end = Offset(rect.right, rect.bottom),
        ),
        topLeft = Offset(rect.left, rect.top),
        size = Size(rect.width, rect.height),
        cornerRadius = corner,
    )

    val clip = Path().apply { addRoundRect(RoundRect(rect, corner)) }
    clipPath(clip) {
        val sheen = Path().apply {
            moveTo(rect.left, rect.top)
            lineTo(rect.left + w * 0.55f, rect.top)
            lineTo(rect.left + w * 0.22f, rect.bottom)
            lineTo(rect.left, rect.bottom)
            close()
        }
        drawPath(
            sheen,
            brush = Brush.linearGradient(
                listOf(GlassSheenNear, GlassSheenFar),
                start = Offset(rect.left, rect.top),
                end = Offset(rect.left + w * 0.5f, rect.bottom),
            ),
        )
        val edge = 1.dp.toPx()
        drawRect(GlassTopGlint, topLeft = Offset(rect.left, rect.top), size = Size(rect.width, edge))
        drawRect(GlassBottomGlint, topLeft = Offset(rect.left, rect.bottom - edge), size = Size(rect.width, edge))
    }

    if (showBorder) {
        drawRoundRect(
            color = accentColor,
            topLeft = Offset(rect.left, rect.top),
            size = Size(rect.width, rect.height),
            cornerRadius = corner,
            style = Stroke(strokePx),
        )
    }
}

internal val GlassBoxCornerRadius = BmwDashboardSkin.GLASS_BOX_CORNER_RADIUS_DP.dp
private val GlassBoxStrokeWidth = BmwDashboardSkin.GLASS_BOX_STROKE_WIDTH_DP.dp

private val GlassFillTop = Color(BmwDashboardSkin.GLASS_FILL_TOP)
private val GlassFillMid = Color(BmwDashboardSkin.GLASS_FILL_MID)
private val GlassFillBottom = Color(BmwDashboardSkin.GLASS_FILL_BOTTOM)
private val GlassSheenNear = Color(BmwDashboardSkin.GLASS_SHEEN_NEAR)
private val GlassSheenFar = Color(BmwDashboardSkin.GLASS_SHEEN_FAR)
private val GlassTopGlint = Color(BmwDashboardSkin.GLASS_BOX_TOP_GLINT_COLOR)
private val GlassBottomGlint = Color(BmwDashboardSkin.GLASS_BOX_BOTTOM_GLINT_COLOR)
