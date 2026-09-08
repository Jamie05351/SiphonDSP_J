package app.siphondsp.compose.controls

import android.graphics.BlurMaskFilter
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.siphondsp.view.BmwDashboardSkin

/**
 * Faithful Compose recreation of the app's glass ON/OFF switch -- mirrors [BmwDashboardSkin]'s
 * `GlassSwitchTrackDrawable` and `GlassSwitchThumbDrawable` (see BmwSkinDrawables.kt)
 * dimension-for-dimension and colour-for-colour: gradient track with a diagonal glass sheen, an
 * ON/OFF label glowing in the half the thumb has vacated, a status-coloured shell border, and a
 * glowing red/green sphere thumb (radial fill, blurred outer glow, crisp ring, top highlight arc).
 *
 * Unlike [BmwSlider], which leans on Material3 `Slider`'s `track`/`thumb` slots, Material3's
 * `Switch` exposes no track-drawing slot and no thumb-shape slot (only `thumbContent` + colours),
 * so this is built directly on [toggleable] -- the same division the View system uses, where the
 * glass look comes from replacing `MaterialSwitch`'s `thumbDrawable`/`trackDrawable` wholesale
 * and the widget is just a hit target + state animator.
 *
 * Stateless: the caller hoists [checked] / [onCheckedChange]. Colours read straight from
 * [BmwDashboardSkin]'s `GLASS_SWITCH_*` constants so the View system and Compose stay in sync.
 */
@Composable
fun BmwSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentDescription: String? = null,
) {
    // Thumb slide + red/green crossfade. The View version animates the thumb via MaterialSwitch
    // and hard-flips the label on the state change; matched here (label flips at the midpoint).
    val progress by animateFloatAsState(
        targetValue = if (checked) 1f else 0f,
        animationSpec = tween(durationMillis = 150),
        label = "bmwSwitchProgress",
    )
    val desc = contentDescription

    Canvas(
        modifier = modifier
            .then(if (enabled) Modifier else Modifier.alpha(DisabledAlpha))
            .size(TrackWidth, TrackHeight)
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            )
            .then(
                if (desc != null) Modifier.semantics { this.contentDescription = desc } else Modifier
            ),
    ) {
        drawTrack(progress)
        drawThumb(progress)
    }
}

// Dimensions, 1:1 with BmwDashboardSkin's GLASS_SWITCH_* dp constants.
private val TrackWidth = BmwDashboardSkin.GLASS_SWITCH_TRACK_WIDTH_DP.dp
private val TrackHeight = BmwDashboardSkin.GLASS_SWITCH_TRACK_HEIGHT_DP.dp
private val TrackBorderWidth = BmwDashboardSkin.GLASS_SWITCH_TRACK_BORDER_WIDTH_DP.dp
private val ThumbGlowWidth = 3.dp
private val ThumbBorderWidth = 1.dp
private val ThumbRingWidth = 1.dp
private val HighlightArcWidth = 1.6.dp
private const val LabelTextSizeSp = 14f
private const val LabelLetterSpacing = 0.03f
private val LabelBlurRadius = 3.dp
private const val DisabledAlpha = 0.4f

// Colours, 1:1 with BmwDashboardSkin's GLASS_SWITCH_* Int constants wrapped as Compose Colors --
// same single source of truth BmwColors.kt uses.
private val TrackFillTop = Color(BmwDashboardSkin.GLASS_SWITCH_TRACK_FILL_TOP)
private val TrackFillMid = Color(BmwDashboardSkin.GLASS_SWITCH_TRACK_FILL_MID)
private val TrackFillBottom = Color(BmwDashboardSkin.GLASS_SWITCH_TRACK_FILL_BOTTOM)
private val TrackRimColor = Color(BmwDashboardSkin.GLASS_SWITCH_TRACK_RIM_COLOR)
private val TrackSheenNear = Color(BmwDashboardSkin.GLASS_SWITCH_TRACK_SHEEN_NEAR)
private val TrackSheenFar = Color(BmwDashboardSkin.GLASS_SWITCH_TRACK_SHEEN_FAR)
private val OnColor = Color(BmwDashboardSkin.GLASS_SWITCH_ON_COLOR)
private val OffColor = Color(BmwDashboardSkin.GLASS_SWITCH_OFF_COLOR)
private val ThumbHighlight = Color(BmwDashboardSkin.GLASS_SWITCH_THUMB_HIGHLIGHT)
private val ThumbOnFillNear = Color(BmwDashboardSkin.GLASS_SWITCH_THUMB_ON_FILL_NEAR)
private val ThumbOnFillFar = Color(BmwDashboardSkin.GLASS_SWITCH_THUMB_ON_FILL_FAR)
private val ThumbOnBorder = Color(BmwDashboardSkin.GLASS_SWITCH_THUMB_ON_BORDER)
private val ThumbOnGlow = Color(BmwDashboardSkin.GLASS_SWITCH_THUMB_ON_GLOW)
private val ThumbOnRing = Color(BmwDashboardSkin.GLASS_SWITCH_THUMB_ON_RING)
private val ThumbOffFillNear = Color(BmwDashboardSkin.GLASS_SWITCH_THUMB_OFF_FILL_NEAR)
private val ThumbOffFillFar = Color(BmwDashboardSkin.GLASS_SWITCH_THUMB_OFF_FILL_FAR)
private val ThumbOffBorder = Color(BmwDashboardSkin.GLASS_SWITCH_THUMB_OFF_BORDER)
private val ThumbOffGlow = Color(BmwDashboardSkin.GLASS_SWITCH_THUMB_OFF_GLOW)
private val ThumbOffRing = Color(BmwDashboardSkin.GLASS_SWITCH_THUMB_OFF_RING)

/** Local inset-by-delta helper -- same reason BmwSlider.kt defines its own: keep the geometry
 *  math explicit and version-proof rather than relying on a particular Compose UI Rect API. */
private fun Rect.insetBy(amount: Float) = Rect(left + amount, top + amount, right - amount, bottom - amount)

private fun DrawScope.drawTrack(progress: Float) {
    val borderPx = TrackBorderWidth.toPx()
    val trackRect = Rect(0f, 0f, size.width, size.height).insetBy(borderPx / 2f)
    if (trackRect.width <= 0f || trackRect.height <= 0f) return
    val corner = trackRect.height / 2f
    val w = trackRect.width
    val statusColor = lerp(OffColor, OnColor, progress)

    // Fill: TL -> BR linear gradient, #111 / #090909 / black at 0 / 0.28 / 1.
    drawRoundRect(
        brush = Brush.linearGradient(
            0f to TrackFillTop, 0.28f to TrackFillMid, 1f to TrackFillBottom,
            start = Offset(trackRect.left, trackRect.top),
            end = Offset(trackRect.right, trackRect.bottom),
        ),
        topLeft = Offset(trackRect.left, trackRect.top),
        size = Size(trackRect.width, trackRect.height),
        cornerRadius = CornerRadius(corner),
    )

    // Static diagonal glass sheen over the left third, clipped to the rounded track.
    val sheen = Path().apply {
        moveTo(trackRect.left + w * 0.06f, trackRect.top)
        lineTo(trackRect.left + w * 0.30f, trackRect.top)
        lineTo(trackRect.left + w * 0.20f, trackRect.bottom)
        lineTo(trackRect.left, trackRect.bottom)
        close()
    }
    val clip = Path().apply { addRoundRect(RoundRect(trackRect, CornerRadius(corner))) }
    clipPath(clip) {
        drawPath(
            sheen,
            brush = Brush.linearGradient(
                listOf(TrackSheenNear, TrackSheenFar),
                start = Offset(trackRect.left + w * 0.06f, trackRect.top),
                end = Offset(trackRect.left + w * 0.20f, trackRect.bottom),
            ),
        )
    }

    // ON/OFF label in the half the thumb has vacated -- a blurred glow copy then a crisp copy,
    // in the current status colour. nativeCanvas because DrawScope has no text or mask-filter
    // API and this must match the drawable's raw-Paint drawText 1:1.
    val on = progress >= 0.5f
    val label = if (on) "ON" else "OFF"
    val labelX = trackRect.left + w * if (on) 0.27f else 0.73f
    drawIntoCanvas { canvas ->
        val base = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textSize = LabelTextSizeSp * density
            letterSpacing = LabelLetterSpacing
            color = statusColor.toArgb()
        }
        val glow = Paint(base).apply {
            maskFilter = BlurMaskFilter(LabelBlurRadius.toPx(), BlurMaskFilter.Blur.NORMAL)
        }
        val baselineY = trackRect.center.y - (base.descent() + base.ascent()) / 2f
        canvas.nativeCanvas.drawText(label, labelX, baselineY, glow)
        canvas.nativeCanvas.drawText(label, labelX, baselineY, base)
    }

    // Rim highlight then the status-coloured shell border.
    drawRoundRect(
        color = TrackRimColor,
        topLeft = Offset(trackRect.left, trackRect.top),
        size = Size(trackRect.width, trackRect.height),
        cornerRadius = CornerRadius(corner),
        style = Stroke(borderPx * 0.75f),
    )
    drawRoundRect(
        color = statusColor,
        topLeft = Offset(trackRect.left, trackRect.top),
        size = Size(trackRect.width, trackRect.height),
        cornerRadius = CornerRadius(corner),
        style = Stroke(borderPx),
    )
}

private fun DrawScope.drawThumb(progress: Float) {
    val glowPx = ThumbGlowWidth.toPx()
    // Thumb bounds: a track-height square that travels the remaining track width. The visible
    // sphere sits inside with `glowWidth` of margin for the blur, exactly as the drawable insets.
    val travel = size.width - size.height
    val boundsLeft = travel * progress
    val bounds = Rect(boundsLeft, 0f, boundsLeft + size.height, size.height)
    val circle = bounds.insetBy(glowPx)
    if (circle.width <= 0f || circle.height <= 0f) return
    val cx = circle.center.x
    val cy = circle.center.y
    val r = circle.width / 2f

    val fillNear = lerp(ThumbOffFillNear, ThumbOnFillNear, progress)
    val fillFar = lerp(ThumbOffFillFar, ThumbOnFillFar, progress)
    val glowColor = lerp(ThumbOffGlow, ThumbOnGlow, progress)
    val ringColor = lerp(ThumbOffRing, ThumbOnRing, progress)
    val borderColor = lerp(ThumbOffBorder, ThumbOnBorder, progress)

    // Blurred outer glow ring -- nativeCanvas; DrawScope can't blur a stroke.
    drawIntoCanvas { canvas ->
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = glowPx
            maskFilter = BlurMaskFilter(glowPx, BlurMaskFilter.Blur.NORMAL)
            color = glowColor.toArgb()
        }
        canvas.nativeCanvas.drawCircle(cx, cy, r + glowPx / 2f, p)
    }

    // Radial fill: centre biased 0.42 down the sphere, radius = sphere radius.
    drawCircle(
        brush = Brush.radialGradient(
            listOf(fillNear, fillFar),
            center = Offset(cx, circle.top + circle.height * 0.42f),
            radius = r,
        ),
        radius = r,
        center = Offset(cx, cy),
    )
    drawCircle(ringColor, r, Offset(cx, cy), style = Stroke(ThumbRingWidth.toPx()))
    drawCircle(borderColor, r, Offset(cx, cy), style = Stroke(ThumbBorderWidth.toPx()))

    // Top highlight arc: 200deg start, 70deg sweep, round cap.
    val arcRect = Rect(cx - r * 0.55f, circle.top + r * 0.15f, cx + r * 0.55f, circle.top + r * 1.1f)
    drawArc(
        color = ThumbHighlight,
        startAngle = 200f,
        sweepAngle = 70f,
        useCenter = false,
        topLeft = Offset(arcRect.left, arcRect.top),
        size = Size(arcRect.width, arcRect.height),
        style = Stroke(width = HighlightArcWidth.toPx(), cap = StrokeCap.Round),
    )
}
