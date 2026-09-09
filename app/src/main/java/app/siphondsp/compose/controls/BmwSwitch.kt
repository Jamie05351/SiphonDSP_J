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
 * The app's glass ON/OFF switch. A chunky pill with a **gloss-black** body (no coloured fill),
 * a **recessed / inlaid** track, an ON/OFF label and a shell border in the status colour, a
 * soft status-coloured **glow** in the half the thumb has vacated, and a large **proud** sphere
 * thumb -- coloured green (ON) / red (OFF), radial "lit from above" fill, drop shadow onto the
 * track, blurred outer glow, crisp ring + border, top highlight arc.
 *
 * Colours read straight from [BmwDashboardSkin]'s `GLASS_SWITCH_*` constants (shared with the
 * View path's `GlassSwitchTrackDrawable` / `GlassSwitchThumbDrawable`); the geometry below is
 * local to Compose so restyling here doesn't disturb the not-yet-migrated View screens.
 *
 * Built on [toggleable] rather than Material3 `Switch` (which exposes no track/thumb drawing
 * slots). Stateless: the caller hoists [checked] / [onCheckedChange].
 */
@Composable
fun BmwSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentDescription: String? = null,
) {
    val progress by animateFloatAsState(
        targetValue = if (checked) 1f else 0f,
        animationSpec = tween(durationMillis = 160),
        label = "bmwSwitchProgress",
    )
    val desc = contentDescription

    Canvas(
        modifier = modifier
            .then(if (enabled) Modifier else Modifier.alpha(DisabledAlpha))
            .size(ComponentWidth, ComponentHeight)
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

// --- geometry (local to the Compose switch) --------------------------------------------------
private val ComponentWidth = 62.dp
private val ComponentHeight = 34.dp
private val TrackHeight = 20.dp        // thin, recessed -- the thumb rides proud of it
private val ThumbDiameter = 32.dp      // > TrackHeight, so it overhangs the track top & bottom
private val TrackBorderWidth = 1.6.dp
private val ThumbGlowWidth = 6.dp
private val ThumbShadowBlur = 5.dp
private val ThumbRingWidth = 1.2.dp
private val ThumbBorderWidth = 1.dp
private val HighlightArcWidth = 1.6.dp
private val SegmentGlowBlur = 7.dp
private const val LabelTextSizeSp = 12.5f
private const val LabelLetterSpacing = 0.03f
private val LabelBlurRadius = 3.dp
private const val DisabledAlpha = 0.4f

// --- colours (shared source of truth with the View drawables) --------------------------------
private val BodyTop = Color(0xFF262626)
private val BodyMid = Color(0xFF0E0E0E)
private val BodyBottom = Color(0xFF000000)
private val RecessShadow = Color(0xB3000000)   // inner top-edge shadow -> "inlaid"
private val RecessCatch = Color(0x33FFFFFF)    // bottom inner catch-light
private val OnColor = Color(BmwDashboardSkin.GLASS_SWITCH_ON_COLOR)
private val OffColor = Color(BmwDashboardSkin.GLASS_SWITCH_OFF_COLOR)
private val ThumbHighlight = Color(BmwDashboardSkin.GLASS_SWITCH_THUMB_HIGHLIGHT)

private fun Rect.insetBy(amount: Float) = Rect(left + amount, top + amount, right - amount, bottom - amount)

private fun DrawScope.drawTrack(progress: Float) {
    val borderPx = TrackBorderWidth.toPx()
    val trackH = TrackHeight.toPx()
    val top = (size.height - trackH) / 2f
    val trackRect = Rect(0f, top, size.width, top + trackH).insetBy(borderPx / 2f)
    if (trackRect.width <= 0f || trackRect.height <= 0f) return
    val corner = trackRect.height / 2f
    val status = lerp(OffColor, OnColor, progress)
    val clip = Path().apply { addRoundRect(RoundRect(trackRect, CornerRadius(corner))) }

    // Gloss-black body: vertical gradient, light at the very top down to pure black.
    drawRoundRect(
        brush = Brush.verticalGradient(
            0f to BodyTop, 0.55f to BodyMid, 1f to BodyBottom,
            startY = trackRect.top, endY = trackRect.bottom,
        ),
        topLeft = Offset(trackRect.left, trackRect.top),
        size = Size(trackRect.width, trackRect.height),
        cornerRadius = CornerRadius(corner),
    )

    clipPath(clip) {
        // "Inlaid": a blurred dark stroke hugging the inner edge (heaviest at the top) reads as
        // a recess; a thin catch-light along the bottom inner edge lifts the far lip.
        drawIntoCanvas { canvas ->
            val recess = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 3f * density
                maskFilter = BlurMaskFilter(3f * density, BlurMaskFilter.Blur.NORMAL)
                color = RecessShadow.toArgb()
            }
            canvas.nativeCanvas.drawRoundRect(
                trackRect.left, trackRect.top - 2f * density,
                trackRect.right, trackRect.bottom, corner, corner, recess,
            )
        }
        drawLine(
            color = RecessCatch,
            start = Offset(trackRect.left + corner, trackRect.bottom - density),
            end = Offset(trackRect.right - corner, trackRect.bottom - density),
            strokeWidth = density,
        )

        // Soft status-coloured glow filling the half the thumb has vacated. `on` -> thumb right,
        // glow + label on the left; `off` -> mirrored.
        val on = progress >= 0.5f
        val thumbTravel = size.width - ThumbDiameter.toPx()
        val thumbLeft = thumbTravel * progress
        val segLeft = if (on) trackRect.left else thumbLeft + ThumbDiameter.toPx()
        val segRight = if (on) thumbLeft else trackRect.right
        if (segRight - segLeft > corner) {
            drawIntoCanvas { canvas ->
                val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    maskFilter = BlurMaskFilter(SegmentGlowBlur.toPx(), BlurMaskFilter.Blur.NORMAL)
                    color = status.copy(alpha = 0.55f).toArgb()
                }
                canvas.nativeCanvas.drawRoundRect(
                    segLeft, trackRect.top + trackRect.height * 0.18f,
                    segRight, trackRect.bottom - trackRect.height * 0.18f,
                    corner, corner, glow,
                )
            }
            drawRoundRect(
                color = status.copy(alpha = 0.16f),
                topLeft = Offset(segLeft, trackRect.top),
                size = Size(segRight - segLeft, trackRect.height),
                cornerRadius = CornerRadius(corner),
            )
        }

        // ON / OFF label in the vacated half, status-coloured: blur-glow copy then a crisp copy.
        val label = if (on) "ON" else "OFF"
        val labelX = if (on)
            trackRect.left + (thumbLeft - trackRect.left) / 2f
        else
            (thumbLeft + ThumbDiameter.toPx() + trackRect.right) / 2f
        drawIntoCanvas { canvas ->
            val base = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textAlign = Paint.Align.CENTER
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                textSize = LabelTextSizeSp * density
                letterSpacing = LabelLetterSpacing
                color = status.toArgb()
            }
            val glow = Paint(base).apply {
                maskFilter = BlurMaskFilter(LabelBlurRadius.toPx(), BlurMaskFilter.Blur.NORMAL)
            }
            val baselineY = trackRect.center.y - (base.descent() + base.ascent()) / 2f
            canvas.nativeCanvas.drawText(label, labelX, baselineY, glow)
            canvas.nativeCanvas.drawText(label, labelX, baselineY, base)
        }
    }

    // Status-coloured shell border, plus a faint blurred bloom of it.
    drawIntoCanvas { canvas ->
        val bloom = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = borderPx
            maskFilter = BlurMaskFilter(3f * density, BlurMaskFilter.Blur.NORMAL)
            color = status.copy(alpha = 0.5f).toArgb()
        }
        canvas.nativeCanvas.drawRoundRect(
            trackRect.left, trackRect.top, trackRect.right, trackRect.bottom, corner, corner, bloom,
        )
    }
    drawRoundRect(
        color = status,
        topLeft = Offset(trackRect.left, trackRect.top),
        size = Size(trackRect.width, trackRect.height),
        cornerRadius = CornerRadius(corner),
        style = Stroke(borderPx),
    )
}

private fun DrawScope.drawThumb(progress: Float) {
    val d = ThumbDiameter.toPx()
    val travel = size.width - d
    val left = travel * progress
    val bounds = Rect(left, (size.height - d) / 2f, left + d, (size.height + d) / 2f)
    val glowPx = ThumbGlowWidth.toPx()
    val circle = bounds.insetBy(glowPx * 0.5f)
    if (circle.width <= 0f) return
    val cx = circle.center.x
    val cy = circle.center.y
    val r = circle.width / 2f
    val status = lerp(OffColor, OnColor, progress)
    val fillNear = lerp(status, Color.White, 0.5f)
    val fillEdge = lerp(status, Color.Black, 0.32f)

    drawIntoCanvas { canvas ->
        // Drop shadow onto the track.
        val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            maskFilter = BlurMaskFilter(ThumbShadowBlur.toPx(), BlurMaskFilter.Blur.NORMAL)
            color = Color(0x8C000000).toArgb()
        }
        canvas.nativeCanvas.drawCircle(cx, cy + 2f * density, r, shadow)
        // Blurred status-coloured outer glow.
        val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = glowPx
            maskFilter = BlurMaskFilter(glowPx, BlurMaskFilter.Blur.NORMAL)
            color = status.copy(alpha = 0.62f).toArgb()
        }
        canvas.nativeCanvas.drawCircle(cx, cy, r + glowPx * 0.5f, glow)
    }

    // Sphere: radial gradient lit from up-left.
    drawCircle(
        brush = Brush.radialGradient(
            0f to fillNear, 0.6f to status, 1f to fillEdge,
            center = Offset(cx - r * 0.28f, cy - r * 0.32f),
            radius = r * 1.15f,
        ),
        radius = r,
        center = Offset(cx, cy),
    )
    drawCircle(lerp(status, Color.White, 0.35f).copy(alpha = 0.85f), r, Offset(cx, cy), style = Stroke(ThumbRingWidth.toPx()))
    drawCircle(lerp(status, Color.Black, 0.25f).copy(alpha = 0.8f), r, Offset(cx, cy), style = Stroke(ThumbBorderWidth.toPx()))

    // Top highlight arc: 200deg start, 70deg sweep, round cap.
    val arc = Rect(cx - r * 0.55f, circle.top + r * 0.15f, cx + r * 0.55f, circle.top + r * 1.1f)
    drawArc(
        color = ThumbHighlight,
        startAngle = 200f,
        sweepAngle = 70f,
        useCenter = false,
        topLeft = Offset(arc.left, arc.top),
        size = Size(arc.width, arc.height),
        style = Stroke(width = HighlightArcWidth.toPx(), cap = StrokeCap.Round),
    )
}
