package app.siphondsp.compose.controls

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * Shared DSP slider in the glossy LED hardware style. The caller still supplies [accentColor],
 * so the existing Low/Mid/High/Headroom/Tilt/Delay colour contract remains authoritative and
 * generic controls continue to use `BmwDashboardSkin.SLIDER_DEFAULT_COLOR`.
 */
@Composable
fun BmwSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    accentColor: Color,
    modifier: Modifier = Modifier,
    steps: Int = 0,
    enabled: Boolean = true,
    onValueChangeFinished: (() -> Unit)? = null,
) {
    val safeValue = value.coerceIn(valueRange.start, valueRange.endInclusive)
    val span = valueRange.endInclusive - valueRange.start
    val fraction = if (span > 0f) ((safeValue - valueRange.start) / span).coerceIn(0f, 1f) else 0f
    val currentOnValueChange by rememberUpdatedState(onValueChange)
    val currentOnFinished by rememberUpdatedState(onValueChangeFinished)
    val layoutDirection = LocalLayoutDirection.current

    fun valueAt(rawFraction: Float): Float {
        val logical = if (layoutDirection == LayoutDirection.Rtl) 1f - rawFraction else rawFraction
        return sliderValueForProgress(valueRange.start + span * logical, valueRange, steps)
    }

    val inputModifier = if (enabled) {
        Modifier
            .pointerInput(valueRange, steps, layoutDirection) {
                detectTapGestures { position ->
                    val inset = ThumbWidth.toPx() / 2f
                    val rawFraction = (position.x - inset) / (size.width - inset * 2f).coerceAtLeast(1f)
                    currentOnValueChange(valueAt(rawFraction))
                    currentOnFinished?.invoke()
                }
            }
            .pointerInput(valueRange, steps, layoutDirection) {
                detectHorizontalDragGestures(
                    onHorizontalDrag = { change, _ ->
                        change.consume()
                        val inset = ThumbWidth.toPx() / 2f
                        val rawFraction = (change.position.x - inset) / (size.width - inset * 2f).coerceAtLeast(1f)
                        currentOnValueChange(valueAt(rawFraction))
                    },
                    onDragEnd = { currentOnFinished?.invoke() },
                )
            }
    } else {
        Modifier
    }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(ControlHeight)
            .alpha(if (enabled) 1f else DisabledAlpha)
            .semantics(mergeDescendants = true) {
                progressBarRangeInfo = ProgressBarRangeInfo(safeValue, valueRange, steps)
                if (enabled) {
                    setProgress { targetValue ->
                        val next = sliderValueForProgress(targetValue, valueRange, steps)
                        if (next == safeValue) {
                            false
                        } else {
                            currentOnValueChange(next)
                            currentOnFinished?.invoke()
                            true
                        }
                    }
                } else {
                    disabled()
                }
            }
            .focusProperties { canFocus = false }
            .then(inputModifier),
    ) {
        val horizontalInset = ThumbWidth.toPx() / 2f
        val trackLeft = horizontalInset
        val trackRight = size.width - horizontalInset
        val trackWidth = (trackRight - trackLeft).coerceAtLeast(1f)
        val trackHeight = TrackHeight.toPx()
        val trackTop = size.height - trackHeight - BottomInset.toPx()
        val trackCentreY = trackTop + trackHeight / 2f
        val corner = trackHeight / 2f
        val trackRect = Rect(trackLeft, trackTop, trackRight, trackTop + trackHeight)

        drawRoundRect(
            color = Color.Black.copy(alpha = 0.72f),
            topLeft = Offset(trackRect.left, trackRect.top + 2.dp.toPx()),
            size = trackRect.size,
            cornerRadius = CornerRadius(corner),
        )
        drawRoundRect(
            color = accentColor.copy(alpha = 0.08f),
            topLeft = Offset(trackRect.left - 2.dp.toPx(), trackRect.top - 2.dp.toPx()),
            size = androidx.compose.ui.geometry.Size(trackRect.width + 4.dp.toPx(), trackRect.height + 4.dp.toPx()),
            cornerRadius = CornerRadius(corner + 2.dp.toPx()),
            style = Stroke(width = 3.dp.toPx()),
        )
        drawRoundRect(
            brush = Brush.verticalGradient(
                colors = listOf(GlassHighlight, GlassMid, Color.Black),
                startY = trackRect.top,
                endY = trackRect.bottom,
            ),
            topLeft = trackRect.topLeft,
            size = trackRect.size,
            cornerRadius = CornerRadius(corner),
        )
        drawRoundRect(
            color = SmokedEdge,
            topLeft = trackRect.topLeft,
            size = trackRect.size,
            cornerRadius = CornerRadius(corner),
            style = Stroke(width = 1.dp.toPx()),
        )
        drawLine(
            color = Color.White.copy(alpha = 0.22f),
            start = Offset(trackLeft + corner * 0.70f, trackRect.top + 1.2.dp.toPx()),
            end = Offset(trackRight - corner * 0.70f, trackRect.top + 1.2.dp.toPx()),
            strokeWidth = 0.8.dp.toPx(),
            cap = StrokeCap.Round,
        )

        val railInset = corner * 0.58f
        drawLine(
            color = accentColor.copy(alpha = 0.20f),
            start = Offset(trackLeft + railInset, trackCentreY),
            end = Offset(trackRight - railInset, trackCentreY),
            strokeWidth = RailGlowWidth.toPx(),
            cap = StrokeCap.Round,
        )
        drawLine(
            color = accentColor,
            start = Offset(trackLeft + railInset, trackCentreY),
            end = Offset(trackRight - railInset, trackCentreY),
            strokeWidth = RailWidth.toPx(),
            cap = StrokeCap.Round,
        )
        drawLine(
            color = lerpToWhite(accentColor, 0.72f),
            start = Offset(trackLeft + railInset, trackCentreY - 0.5.dp.toPx()),
            end = Offset(trackRight - railInset, trackCentreY - 0.5.dp.toPx()),
            strokeWidth = 0.7.dp.toPx(),
            cap = StrokeCap.Round,
        )

        val activeLed = ((LedCount - 1) * fraction).roundToInt()
        val ledY = LedCentreY.toPx()
        val ledRadius = LedRadius.toPx()
        repeat(LedCount) { index ->
            val x = trackLeft + trackWidth * index / (LedCount - 1f)
            val active = if (layoutDirection == LayoutDirection.Rtl) {
                index >= LedCount - 1 - activeLed
            } else {
                index <= activeLed
            }
            if (active) {
                drawCircle(accentColor.copy(alpha = 0.18f), ledRadius * 2.15f, Offset(x, ledY))
                drawCircle(accentColor, ledRadius * 1.32f, Offset(x, ledY))
                drawCircle(lerpToWhite(accentColor, 0.88f), ledRadius * 0.60f, Offset(x, ledY))
                drawCircle(Color.White.copy(alpha = 0.72f), ledRadius * 0.20f, Offset(x - ledRadius * 0.22f, ledY - ledRadius * 0.24f))
            } else {
                drawCircle(SmokedEdge, ledRadius * 1.28f, Offset(x, ledY))
                drawCircle(SmokedFill, ledRadius, Offset(x, ledY))
            }
        }

        val visualFraction = if (layoutDirection == LayoutDirection.Rtl) 1f - fraction else fraction
        val thumbCentre = Offset(trackLeft + trackWidth * visualFraction, trackCentreY)
        val thumbWidth = ThumbWidth.toPx()
        val thumbHeight = ThumbHeight.toPx()
        val thumbRect = Rect(
            thumbCentre.x - thumbWidth / 2f,
            thumbCentre.y - thumbHeight / 2f,
            thumbCentre.x + thumbWidth / 2f,
            thumbCentre.y + thumbHeight / 2f,
        )
        val thumbCorner = thumbHeight / 2f

        drawRoundRect(
            color = Color.Black.copy(alpha = 0.78f),
            topLeft = Offset(thumbRect.left, thumbRect.top + 2.dp.toPx()),
            size = thumbRect.size,
            cornerRadius = CornerRadius(thumbCorner),
        )
        drawRoundRect(
            brush = Brush.verticalGradient(
                colors = listOf(GlassHighlight, GlassMid, Color.Black),
                startY = thumbRect.top,
                endY = thumbRect.bottom,
            ),
            topLeft = thumbRect.topLeft,
            size = thumbRect.size,
            cornerRadius = CornerRadius(thumbCorner),
        )
        drawRoundRect(
            color = accentColor.copy(alpha = 0.20f),
            topLeft = thumbRect.topLeft,
            size = thumbRect.size,
            cornerRadius = CornerRadius(thumbCorner),
            style = Stroke(width = ThumbGlowWidth.toPx()),
        )
        drawRoundRect(
            color = accentColor,
            topLeft = thumbRect.topLeft,
            size = thumbRect.size,
            cornerRadius = CornerRadius(thumbCorner),
            style = Stroke(width = ThumbEdgeWidth.toPx()),
        )
        val thumbInset = Rect(
            thumbRect.left + 4.dp.toPx(),
            thumbRect.top + 4.dp.toPx(),
            thumbRect.right - 4.dp.toPx(),
            thumbRect.bottom - 4.dp.toPx(),
        )
        drawRoundRect(
            brush = Brush.verticalGradient(
                colors = listOf(Color(0xFF3D4048), Color(0xFF090A0D)),
                startY = thumbInset.top,
                endY = thumbInset.bottom,
            ),
            topLeft = thumbInset.topLeft,
            size = thumbInset.size,
            cornerRadius = CornerRadius(thumbInset.height / 2f),
        )
        drawLine(
            color = Color.White.copy(alpha = 0.34f),
            start = Offset(thumbInset.left + thumbInset.height * 0.45f, thumbInset.top + 1.dp.toPx()),
            end = Offset(thumbInset.right - thumbInset.height * 0.45f, thumbInset.top + 1.dp.toPx()),
            strokeWidth = 0.8.dp.toPx(),
            cap = StrokeCap.Round,
        )
        drawLine(
            color = lerpToWhite(accentColor, 0.86f),
            start = Offset(thumbCentre.x - thumbWidth * 0.20f, thumbCentre.y),
            end = Offset(thumbCentre.x + thumbWidth * 0.20f, thumbCentre.y),
            strokeWidth = ThumbInsetWidth.toPx(),
            cap = StrokeCap.Round,
        )
    }
}

private fun lerpToWhite(color: Color, amount: Float): Color =
    androidx.compose.ui.graphics.lerp(color, Color.White, amount)

internal fun sliderValueForProgress(
    targetValue: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
): Float {
    val clamped = targetValue.coerceIn(valueRange.start, valueRange.endInclusive)
    if (steps <= 0) return clamped
    val span = valueRange.endInclusive - valueRange.start
    if (span <= 0f) return valueRange.start
    val intervals = steps + 1
    val fraction = (clamped - valueRange.start) / span
    val steppedFraction = (fraction * intervals).roundToInt() / intervals.toFloat()
    return valueRange.start + span * steppedFraction
}

private val ControlHeight = 42.dp
private val TrackHeight = 17.dp
private val BottomInset = 2.dp
private val RailWidth = 2.dp
private val RailGlowWidth = 6.dp
private val ThumbWidth = 38.dp
private val ThumbHeight = 20.dp
private val ThumbGlowWidth = 5.dp
private val ThumbEdgeWidth = 1.4.dp
private val ThumbInsetWidth = 2.2.dp
private val LedCentreY = 7.dp
private val LedRadius = 2.25.dp
private const val LedCount = 25
private const val DisabledAlpha = 0.4f

private val GlassHighlight = Color(0xFF4B4E58)
private val GlassMid = Color(0xFF111318)
private val SmokedFill = Color(0xFF07080B)
private val SmokedEdge = Color(0xFF363941)
