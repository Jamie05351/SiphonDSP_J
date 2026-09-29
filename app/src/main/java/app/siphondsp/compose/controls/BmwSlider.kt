package app.siphondsp.compose.controls

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Shared DSP slider in the glossy LED hardware style. The caller still supplies [accentColor],
 * so the existing Low/Mid/High/Headroom/Tilt/Delay colour contract remains authoritative and
 * generic controls continue to use `BmwDashboardSkin.SLIDER_DEFAULT_COLOR`.
 *
 * Reads as a level, not just a position:
 *  - the rail and LEDs light only between [origin] and the thumb, so the value is readable at a
 *    glance and the thumb no longer needs the whole rail to be lit;
 *  - a range that crosses zero (gain, tilt) defaults [origin] to 0 and gets a centre tick, so
 *    sign and size are obvious without reading the number;
 *  - while pressed the thumb lifts and, if [valueText] is set, a bubble shows the live value
 *    above it, clear of the finger.
 * Both new parameters are optional, so every existing call site keeps working.
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
    origin: Float = defaultSliderOrigin(valueRange),
    valueText: ((Float) -> String)? = null,
) {
    val safeValue = value.coerceIn(valueRange.start, valueRange.endInclusive)
    val span = valueRange.endInclusive - valueRange.start
    val fraction = if (span > 0f) ((safeValue - valueRange.start) / span).coerceIn(0f, 1f) else 0f
    val originFraction = if (span > 0f) ((origin - valueRange.start) / span).coerceIn(0f, 1f) else 0f
    val currentOnValueChange by rememberUpdatedState(onValueChange)
    val currentOnFinished by rememberUpdatedState(onValueChangeFinished)
    val layoutDirection = LocalLayoutDirection.current
    val measurer = rememberTextMeasurer()

    var pressed by remember { mutableStateOf(false) }
    var dragging by remember { mutableStateOf(false) }
    val press by animateFloatAsState(
        targetValue = if ((pressed || dragging) && enabled) 1f else 0f,
        animationSpec = tween(90),
        label = "sliderPress",
    )

    fun valueAt(rawFraction: Float): Float {
        val logical = if (layoutDirection == LayoutDirection.Rtl) 1f - rawFraction else rawFraction
        return sliderValueForProgress(valueRange.start + span * logical, valueRange, steps)
    }

    val inputModifier = if (enabled) {
        Modifier
            .pointerInput(valueRange, steps, layoutDirection) {
                detectTapGestures(
                    onPress = {
                        pressed = true
                        tryAwaitRelease()
                        pressed = false
                    },
                    onTap = { position ->
                        val inset = ThumbWidth.toPx() / 2f
                        val rawFraction = (position.x - inset) / (size.width - inset * 2f).coerceAtLeast(1f)
                        currentOnValueChange(valueAt(rawFraction))
                        currentOnFinished?.invoke()
                    },
                )
            }
            .pointerInput(valueRange, steps, layoutDirection) {
                detectHorizontalDragGestures(
                    onDragStart = { dragging = true },
                    onHorizontalDrag = { change, _ ->
                        change.consume()
                        val inset = ThumbWidth.toPx() / 2f
                        val rawFraction = (change.position.x - inset) / (size.width - inset * 2f).coerceAtLeast(1f)
                        currentOnValueChange(valueAt(rawFraction))
                    },
                    onDragEnd = {
                        dragging = false
                        currentOnFinished?.invoke()
                    },
                    onDragCancel = { dragging = false },
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
        val rtl = layoutDirection == LayoutDirection.Rtl
        val visualFraction = if (rtl) 1f - fraction else fraction
        val visualOrigin = if (rtl) 1f - originFraction else originFraction
        val bipolar = originFraction > 0.001f && originFraction < 0.999f

        val horizontalInset = ThumbWidth.toPx() / 2f
        val trackLeft = horizontalInset
        val trackRight = size.width - horizontalInset
        val trackWidth = (trackRight - trackLeft).coerceAtLeast(1f)
        val trackHeight = TrackHeight.toPx()
        val trackTop = size.height - trackHeight - BottomInset.toPx()
        val trackCentreY = trackTop + trackHeight / 2f
        val corner = trackHeight / 2f
        val trackRect = Rect(trackLeft, trackTop, trackRight, trackTop + trackHeight)

        // Recessed capsule with a metal hairline (same metal as the tile and screen bezels).
        drawRoundRect(
            color = Color.Black.copy(alpha = 0.72f),
            topLeft = Offset(trackRect.left, trackRect.top + 2.dp.toPx()),
            size = trackRect.size,
            cornerRadius = CornerRadius(corner),
        )
        drawRoundRect(
            brush = Brush.verticalGradient(
                0f to TrackTop, 0.45f to TrackMid, 1f to Color.Black,
                startY = trackRect.top, endY = trackRect.bottom,
            ),
            topLeft = trackRect.topLeft,
            size = trackRect.size,
            cornerRadius = CornerRadius(corner),
        )
        drawRoundRect(
            brush = Brush.linearGradient(
                listOf(MetalHi, MetalMid, MetalLo),
                start = trackRect.topLeft, end = trackRect.bottomRight,
            ),
            topLeft = Offset(trackRect.left + 0.5.dp.toPx(), trackRect.top + 0.5.dp.toPx()),
            size = Size(trackRect.width - 1.dp.toPx(), trackRect.height - 1.dp.toPx()),
            cornerRadius = CornerRadius(corner),
            style = Stroke(width = 1.dp.toPx()),
        )
        drawLine(
            color = Color.White.copy(alpha = 0.14f),
            start = Offset(trackLeft + corner * 0.70f, trackRect.top + 1.6.dp.toPx()),
            end = Offset(trackRight - corner * 0.70f, trackRect.top + 1.6.dp.toPx()),
            strokeWidth = 0.8.dp.toPx(),
            cap = StrokeCap.Round,
        )

        // Rail: dim full length, lit only from the origin to the thumb.
        val railInset = corner * 0.58f
        val railLeft = trackLeft + railInset
        val railWidthPx = (trackRight - railInset) - railLeft
        drawLine(
            color = accentColor.copy(alpha = 0.22f),
            start = Offset(railLeft, trackCentreY),
            end = Offset(railLeft + railWidthPx, trackCentreY),
            strokeWidth = 1.4.dp.toPx(),
            cap = StrokeCap.Round,
        )
        val litA = railLeft + railWidthPx * min(visualOrigin, visualFraction)
        val litB = railLeft + railWidthPx * max(visualOrigin, visualFraction)
        if (litB - litA > 0.5f) {
            drawLine(accentColor.copy(alpha = 0.22f), Offset(litA, trackCentreY), Offset(litB, trackCentreY), RailGlowWidth.toPx(), StrokeCap.Round)
            drawLine(accentColor, Offset(litA, trackCentreY), Offset(litB, trackCentreY), RailWidth.toPx(), StrokeCap.Round)
            drawLine(
                lerpToWhite(accentColor, 0.72f),
                Offset(litA, trackCentreY - 0.5.dp.toPx()), Offset(litB, trackCentreY - 0.5.dp.toPx()),
                0.7.dp.toPx(), StrokeCap.Round,
            )
        }
        if (bipolar) {
            val ox = railLeft + railWidthPx * visualOrigin
            drawLine(
                Color.White.copy(alpha = 0.42f),
                Offset(ox, trackTop + 3.dp.toPx()), Offset(ox, trackRect.bottom - 3.dp.toPx()),
                strokeWidth = 1.dp.toPx(),
            )
        }

        // LEDs: lit between origin and thumb, with the end LEDs fading in fractionally.
        val ledY = LedCentreY.toPx()
        val ledRadius = LedRadius.toPx()
        val lo = min(visualOrigin, visualFraction)
        val hi = max(visualOrigin, visualFraction)
        repeat(LedCount) { index ->
            val t = index / (LedCount - 1f)
            val x = trackLeft + trackWidth * t
            val lit = ((hi - t) * (LedCount - 1) + 1f).coerceIn(0f, 1f) *
                ((t - lo) * (LedCount - 1) + 1f).coerceIn(0f, 1f)
            val isOrigin = bipolar && kotlin.math.abs(t - visualOrigin) < 0.5f / (LedCount - 1)
            val c = Offset(x, ledY)
            if (lit > 0f) {
                drawCircle(accentColor.copy(alpha = 0.18f * lit), ledRadius * 2.15f, c)
                drawCircle(accentColor.copy(alpha = lit), ledRadius * 1.32f, c)
                drawCircle(lerpToWhite(accentColor, 0.88f).copy(alpha = lit), ledRadius * 0.60f, c)
                drawCircle(Color.White.copy(alpha = 0.72f * lit), ledRadius * 0.20f, Offset(x - ledRadius * 0.22f, ledY - ledRadius * 0.24f))
                if (isOrigin) drawCircle(Color.White.copy(alpha = 0.55f), ledRadius * 1.9f, c, style = Stroke(0.8.dp.toPx()))
            } else {
                drawCircle(SmokedEdge, ledRadius * (if (isOrigin) 1.6f else 1.28f), c)
                drawCircle(SmokedFill, ledRadius * (if (isOrigin) 1.25f else 1f), c)
            }
        }

        // Thumb (lifts slightly and glows harder while pressed).
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
        scale(1f + 0.07f * press, pivot = thumbCentre) {
            drawRoundRect(
                color = Color.Black.copy(alpha = 0.8f),
                topLeft = Offset(thumbRect.left, thumbRect.top + 2.dp.toPx()),
                size = thumbRect.size,
                cornerRadius = CornerRadius(thumbCorner),
            )
            drawRoundRect(
                brush = Brush.verticalGradient(
                    0f to ThumbTop, 0.5f to GlassMid, 1f to Color.Black,
                    startY = thumbRect.top, endY = thumbRect.bottom,
                ),
                topLeft = thumbRect.topLeft,
                size = thumbRect.size,
                cornerRadius = CornerRadius(thumbCorner),
            )
            drawRoundRect(
                color = accentColor.copy(alpha = 0.20f + 0.18f * press),
                topLeft = thumbRect.topLeft,
                size = thumbRect.size,
                cornerRadius = CornerRadius(thumbCorner),
                style = Stroke(width = (ThumbGlowWidth.toPx() + 4.dp.toPx() * press)),
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
                color = Color.White.copy(alpha = 0.30f),
                start = Offset(thumbInset.left + thumbInset.height * 0.45f, thumbInset.top + 1.dp.toPx()),
                end = Offset(thumbInset.right - thumbInset.height * 0.45f, thumbInset.top + 1.dp.toPx()),
                strokeWidth = 0.8.dp.toPx(),
                cap = StrokeCap.Round,
            )
            // Three grip ticks, so the thumb reads as something to hold.
            val tickColor = lerpToWhite(accentColor, 0.86f)
            for (dx in floatArrayOf(-4f, 0f, 4f)) {
                drawLine(
                    color = tickColor,
                    start = Offset(thumbCentre.x + dx.dp.toPx(), thumbCentre.y - 3.2.dp.toPx()),
                    end = Offset(thumbCentre.x + dx.dp.toPx(), thumbCentre.y + 3.2.dp.toPx()),
                    strokeWidth = 1.6.dp.toPx(),
                    cap = StrokeCap.Round,
                )
            }
        }

        // Value bubble above the thumb while pressed, sitting over the LED row.
        if (valueText != null && press > 0.01f) {
            val layout = measurer.measure(
                valueText(safeValue),
                TextStyle(fontSize = CarUi.MinDenseText, fontWeight = FontWeight.Bold, color = Color.White.copy(alpha = press)),
            )
            val bw = layout.size.width + 14.dp.toPx()
            val bh = 20.dp.toPx()
            val bx = (thumbCentre.x - bw / 2f).coerceIn(0f, (size.width - bw).coerceAtLeast(0f))
            val bubbleTop = 0f
            drawRoundRect(BubbleFill.copy(alpha = press), Offset(bx, bubbleTop), Size(bw, bh), CornerRadius(5.dp.toPx()))
            drawRoundRect(accentColor.copy(alpha = press), Offset(bx, bubbleTop), Size(bw, bh), CornerRadius(5.dp.toPx()), style = Stroke(1.2.dp.toPx()))
            val caret = Path().apply {
                moveTo(thumbCentre.x - 4.dp.toPx(), bubbleTop + bh - 0.5.dp.toPx())
                lineTo(thumbCentre.x + 4.dp.toPx(), bubbleTop + bh - 0.5.dp.toPx())
                lineTo(thumbCentre.x, bubbleTop + bh + 4.dp.toPx())
                close()
            }
            drawPath(caret, accentColor.copy(alpha = press))
            drawText(layout, topLeft = Offset(bx + (bw - layout.size.width) / 2f, bubbleTop + (bh - layout.size.height) / 2f))
        }
    }
}

private fun lerpToWhite(color: Color, amount: Float): Color =
    androidx.compose.ui.graphics.lerp(color, Color.White, amount)

/** A range that crosses zero (gain, tilt) fills from 0; anything else fills from its start. */
internal fun defaultSliderOrigin(range: ClosedFloatingPointRange<Float>): Float =
    if (range.start < 0f && range.endInclusive > 0f) 0f else range.start

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
private val ThumbEdgeWidth = 1.5.dp
private val LedCentreY = 7.dp
private val LedRadius = 2.25.dp
private const val LedCount = 25
private const val DisabledAlpha = 0.4f

private val TrackTop = Color(0xFF2C2F36)
private val TrackMid = Color(0xFF0E1014)
private val ThumbTop = Color(0xFF5A5D68)
private val GlassMid = Color(0xFF14161B)
private val SmokedFill = Color(0xFF07080B)
private val SmokedEdge = Color(0xFF2E3138)
private val BubbleFill = Color(0xFF090B0E)
private val MetalHi = Color(0xFF8E9194)
private val MetalMid = Color(0xFF45474A)
private val MetalLo = Color(0xFF222426)
