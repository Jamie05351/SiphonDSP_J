package app.siphondsp.compose.controls

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.siphondsp.view.BmwDashboardSkin

/**
 * Toggle in the same hardware language as the old slider: a recessed slot with a metal hairline and a
 * machined thumb, instead of a saturated pill.
 *
 * State is carried by small, precise things rather than large colour: the thumb's edge ring, one
 * lamp on its face, and a thin rail that lights from the left up to the thumb when on. The track
 * stays dark and the labels stay neutral, so ON no longer floods the control with neon and OFF
 * doesn't read as an alarm (it is the same colour at reduced intensity). Callers such as polarity
 * can still pass their own [onColor]/[offColor] and labels.
 */
@Composable
fun BmwSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentDescription: String? = null,
    onColor: Color = Color(BmwDashboardSkin.GLASS_SWITCH_ON_COLOR),
    offColor: Color = Color(BmwDashboardSkin.GLASS_SWITCH_OFF_COLOR),
    onLabel: String = "ON",
    offLabel: String = "OFF",
    width: Dp = ComponentWidth,
) {
    val progress by animateFloatAsState(
        targetValue = if (checked) 1f else 0f,
        animationSpec = tween(durationMillis = 160),
        label = "bmwSwitchProgress",
    )
    val status = if (checked) onColor else offColor
    val interactionSource = remember { MutableInteractionSource() }

    Canvas(
        modifier = modifier
            .alpha(if (enabled) 1f else DisabledAlpha)
            .size(width, ComponentHeight)
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                onValueChange = onCheckedChange,
            )
            .bmwFocusRing(interactionSource, cornerRadius = TrackHeight / 2)
            .then(
                if (contentDescription != null) {
                    Modifier.semantics { this.contentDescription = contentDescription }
                } else {
                    Modifier
                },
            ),
    ) {
        // Reduced intensity for OFF keeps the state colour but stops it shouting.
        val lit = if (checked) 1f else OffIntensity
        // Art rects can be shorter than the switch's 48dp touch height; shrink the slot to fit.
        val trackH = minOf(TrackHeight.toPx(), size.height - 2.dp.toPx())
        val track = Rect(0f, (size.height - trackH) / 2f, size.width, (size.height + trackH) / 2f)
        val trackCorner = trackH / 2f

        // Recessed slot with a metal hairline (same metal as the slider and bezels).
        drawRoundRect(
            color = Color.Black.copy(alpha = 0.72f),
            topLeft = Offset(track.left, track.top + 2.dp.toPx()),
            size = track.size,
            cornerRadius = CornerRadius(trackCorner),
        )
        drawRoundRect(
            brush = Brush.verticalGradient(
                0f to TrackTop, 0.45f to TrackMid, 1f to Color.Black,
                startY = track.top, endY = track.bottom,
            ),
            topLeft = track.topLeft,
            size = track.size,
            cornerRadius = CornerRadius(trackCorner),
        )
        drawRoundRect(
            brush = Brush.linearGradient(
                listOf(MetalHi, MetalMid, MetalLo),
                start = track.topLeft, end = track.bottomRight,
            ),
            topLeft = Offset(track.left + 0.5.dp.toPx(), track.top + 0.5.dp.toPx()),
            size = Size(track.width - 1.dp.toPx(), track.height - 1.dp.toPx()),
            cornerRadius = CornerRadius(trackCorner),
            style = Stroke(width = 1.dp.toPx()),
        )
        drawLine(
            color = Color.White.copy(alpha = 0.14f),
            start = Offset(track.left + trackCorner * 0.75f, track.top + 1.6.dp.toPx()),
            end = Offset(track.right - trackCorner * 0.75f, track.top + 1.6.dp.toPx()),
            strokeWidth = 0.8.dp.toPx(),
            cap = StrokeCap.Round,
        )

        // Thumb geometry: seated inside the slot with an even margin, like a hardware slider.
        val pad = 3.dp.toPx()
        val thumbW = ThumbWidth.toPx()
        val thumbH = minOf(ThumbHeight.toPx(), trackH - 6.dp.toPx())
        val travel = size.width - thumbW - pad * 2f
        val thumbLeft = pad + travel * progress
        val thumb = Rect(thumbLeft, (size.height - thumbH) / 2f, thumbLeft + thumbW, (size.height + thumbH) / 2f)
        val thumbCorner = thumbH / 2f
        val cy = size.height / 2f

        // Rail along the bottom of the slot (clear of the label): dim full length, lit from the
        // left edge up to the thumb when on.
        val railY = track.bottom - 6.dp.toPx()
        val railLeft = trackCorner * 0.75f + pad
        val railRight = size.width - trackCorner * 0.75f - pad
        drawLine(Color.White.copy(alpha = 0.08f), Offset(railLeft, railY), Offset(railRight, railY), 1.4.dp.toPx(), StrokeCap.Round)
        val litEnd = thumb.center.x
        if (litEnd - railLeft > 1f && progress > 0.02f) {
            drawLine(status.copy(alpha = 0.20f * progress), Offset(railLeft, railY), Offset(litEnd, railY), 5.dp.toPx(), StrokeCap.Round)
            drawLine(status.copy(alpha = progress), Offset(railLeft, railY), Offset(litEnd, railY), 1.8.dp.toPx(), StrokeCap.Round)
        }

        // Label: on the free side, neutral, no glow.
        val label = if (checked) onLabel else offLabel
        val labelCentreX = if (checked) {
            (track.left + thumb.left) / 2f
        } else {
            (thumb.right + track.right) / 2f
        }
        drawContext.canvas.nativeCanvas.apply {
            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.White.copy(alpha = if (checked) 0.78f else 0.62f).toArgb()
                textSize = LabelTextSize.toPx()
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                letterSpacing = 0.06f
                textAlign = android.graphics.Paint.Align.CENTER
            }
            drawText(label, labelCentreX, cy - (paint.ascent() + paint.descent()) / 2f, paint)
        }

        // Thumb: machined glass, state-coloured edge, one lamp.
        drawRoundRect(
            color = Color.Black.copy(alpha = 0.82f),
            topLeft = Offset(thumb.left, thumb.top + 2.dp.toPx()),
            size = thumb.size,
            cornerRadius = CornerRadius(thumbCorner),
        )
        drawRoundRect(
            brush = Brush.verticalGradient(
                0f to ThumbTop, 0.5f to ThumbMid, 1f to Color.Black,
                startY = thumb.top, endY = thumb.bottom,
            ),
            topLeft = thumb.topLeft,
            size = thumb.size,
            cornerRadius = CornerRadius(thumbCorner),
        )
        drawRoundRect(
            color = status.copy(alpha = 0.14f * lit),
            topLeft = thumb.topLeft,
            size = thumb.size,
            cornerRadius = CornerRadius(thumbCorner),
            style = Stroke(width = 4.dp.toPx()),
        )
        drawRoundRect(
            color = status.copy(alpha = 0.5f + 0.4f * lit),
            topLeft = thumb.topLeft,
            size = thumb.size,
            cornerRadius = CornerRadius(thumbCorner),
            style = Stroke(width = 1.25.dp.toPx()),
        )
        val inset = Rect(thumb.left + 3.5.dp.toPx(), thumb.top + 3.5.dp.toPx(), thumb.right - 3.5.dp.toPx(), thumb.bottom - 3.5.dp.toPx())
        drawRoundRect(
            brush = Brush.verticalGradient(
                listOf(Color(0xFF3D4048), Color(0xFF090A0D)),
                startY = inset.top, endY = inset.bottom,
            ),
            topLeft = inset.topLeft,
            size = inset.size,
            cornerRadius = CornerRadius(inset.height / 2f),
        )
        drawLine(
            color = Color.White.copy(alpha = 0.28f),
            start = Offset(inset.left + inset.height * 0.45f, inset.top + 1.dp.toPx()),
            end = Offset(inset.right - inset.height * 0.45f, inset.top + 1.dp.toPx()),
            strokeWidth = 0.8.dp.toPx(),
            cap = StrokeCap.Round,
        )
        // Grip ticks either side of the lamp.
        for (dx in floatArrayOf(-1f, 1f)) {
            val x = thumb.center.x + dx * thumbW * 0.30f
            drawLine(
                color = Color.White.copy(alpha = 0.22f),
                start = Offset(x, cy - 3.dp.toPx()),
                end = Offset(x, cy + 3.dp.toPx()),
                strokeWidth = 1.5.dp.toPx(),
                cap = StrokeCap.Round,
            )
        }
        // Lamp.
        val lamp = Offset(thumb.center.x, cy)
        drawCircle(status.copy(alpha = 0.22f * lit), 7.dp.toPx(), lamp)
        drawCircle(status.copy(alpha = lit), 3.5.dp.toPx(), lamp)
        drawCircle(lerp(status, Color.White, 0.85f).copy(alpha = lit), 1.5.dp.toPx(), lamp)
    }
}

private val ComponentWidth = 100.dp
private val ComponentHeight = CarUi.MinTouch
private val TrackHeight = 34.dp
private val ThumbWidth = 46.dp
private val ThumbHeight = 26.dp
private val LabelTextSize = 14.dp
private const val OffIntensity = 0.55f
private const val DisabledAlpha = 0.4f

private val TrackTop = Color(0xFF2C2F36)
private val TrackMid = Color(0xFF0E1014)
private val ThumbTop = Color(0xFF5A5D68)
private val ThumbMid = Color(0xFF14161B)
private val MetalHi = Color(0xFF8E9194)
private val MetalMid = Color(0xFF45474A)
private val MetalLo = Color(0xFF222426)
