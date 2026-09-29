package app.siphondsp.compose.controls

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

private const val KnobStartAngle = 135f
private const val KnobSweepAngle = 270f
private const val KnobLedCount = 33
private const val KnobCenterDeadZoneFraction = 0.12f

/**
 * Generic compressor/DSP knob: machined-chrome bezel, near-black brushed face with fixed (light-locked)
 * highlights, and a smooth-filling LED ring in the caller's [accentColor]. Only the pointer rotates,
 * because a symmetric brushed face gives no cue otherwise, and rotating the highlights would look
 * wrong. Snaps to [step], previews while dragged and commits on release.
 */
@Composable
fun BmwDspKnob(
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    step: Float,
    accentColor: Color,
    onPreview: (Float) -> Unit,
    onCommit: (Float) -> Unit,
    modifier: Modifier = Modifier,
    diameter: Dp = 104.dp,
    enabled: Boolean = true,
    accessibilityLabel: String? = null,
) {
    val safeValue = value.coerceIn(valueRange.start, valueRange.endInclusive)
    val span = valueRange.endInclusive - valueRange.start
    val fraction = if (span > 0f) ((safeValue - valueRange.start) / span).coerceIn(0f, 1f) else 0f
    val currentOnPreview by rememberUpdatedState(onPreview)
    val currentOnCommit by rememberUpdatedState(onCommit)
    var dragValue by remember(value) { mutableFloatStateOf(safeValue) }
    val semanticSteps = stepsFor(valueRange, step)

    fun valueFor(position: Offset, width: Int, height: Int): Float? {
        val f = dspKnobFractionForInteractivePoint(
            position.x,
            position.y,
            width.toFloat(),
            height.toFloat(),
        ) ?: return null
        val raw = valueRange.start + span * f
        return snapToStep(raw, valueRange, step)
    }

    val inputModifier = if (enabled) {
        Modifier
            .pointerInput(valueRange, step) {
                detectTapGestures { position ->
                    valueFor(position, size.width, size.height)?.let { next ->
                        dragValue = next
                        currentOnPreview(next)
                        currentOnCommit(next)
                    }
                }
            }
            .pointerInput(valueRange, step) {
                var dragHasPreview = false
                detectDragGestures(
                    onDragStart = { dragHasPreview = false },
                    onDrag = { change, _ ->
                        change.consume()
                        valueFor(change.position, size.width, size.height)?.let { next ->
                            dragValue = next
                            dragHasPreview = true
                            currentOnPreview(next)
                        }
                    },
                    onDragEnd = {
                        if (dragHasPreview) currentOnCommit(dragValue)
                        dragHasPreview = false
                    },
                    onDragCancel = {
                        dragHasPreview = false
                    },
                )
            }
    } else {
        Modifier
    }

    // Read through State so a value/accent change only invalidates the draw phase and the cached
    // brushes (which depend on size alone) survive a drag.
    val fractionState = rememberUpdatedState(fraction)
    val accentState = rememberUpdatedState(accentColor)

    Spacer(
        modifier = modifier
            .size(diameter)
            .alpha(if (enabled) 1f else 0.4f)
            .semantics(mergeDescendants = true) {
                accessibilityLabel?.let { contentDescription = it }
                progressBarRangeInfo = ProgressBarRangeInfo(safeValue, valueRange, semanticSteps)
                if (enabled) {
                    setProgress { targetValue ->
                        val next = snapToStep(targetValue, valueRange, step)
                        if (next == safeValue) {
                            false
                        } else {
                            dragValue = next
                            currentOnPreview(next)
                            currentOnCommit(next)
                            true
                        }
                    }
                } else {
                    disabled()
                }
            }
            .focusProperties { canFocus = false }
            .then(inputModifier)
            .drawWithCache {
                val brushes = KnobBrushes(size.minDimension / 2f, Offset(size.width / 2f, size.height / 2f))
                onDrawBehind { drawKnob(brushes, fractionState.value, accentState.value) }
            },
    )
}

/** Size-dependent brushes, built once per layout size by [drawWithCache]. */
internal class KnobBrushes(val radius: Float, val centre: Offset) {
    val shadow = Brush.radialGradient(
        colors = listOf(Color.Black.copy(alpha = 0.85f), Color.Transparent),
        center = Offset(centre.x, centre.y + radius * 0.04f),
        radius = radius * 0.86f,
    )
    val chrome = Brush.sweepGradient(
        0.00f to Color(0xFF2A282A), 0.10f to Color(0xFFB9B6B8), 0.22f to Color(0xFF3A383A),
        0.35f to Color(0xFFE6E4E6), 0.48f to Color(0xFF232123), 0.60f to Color(0xFF8A8789),
        0.72f to Color(0xFF1A181A), 0.85f to Color(0xFFCFCDCF), 1.00f to Color(0xFF2A282A),
        center = centre,
    )
    val face = Brush.radialGradient(
        0f to Color(0xFF1B1C1E), 1f to Color(0xFF070708),
        center = Offset(centre.x - radius * 0.12f, centre.y - radius * 0.14f),
        radius = radius * 0.85f,
    )
    // Two opposite conic lobes: the anisotropic highlight of brushed metal. Fixed to the light.
    val lobes = Brush.sweepGradient(
        0.00f to Color.Transparent, 0.08f to Color.White.copy(alpha = 0.20f), 0.16f to Color.Transparent,
        0.50f to Color.Transparent, 0.58f to Color.White.copy(alpha = 0.20f), 0.66f to Color.Transparent,
        1.00f to Color.Transparent,
        center = centre,
    )
    val dish = Brush.linearGradient(
        colors = listOf(Color(0xFF050506), Color(0xFF26282A)),
        start = Offset(centre.x - radius * 0.4f, centre.y - radius * 0.4f),
        end = Offset(centre.x + radius * 0.4f, centre.y + radius * 0.4f),
    )
}

internal fun DrawScope.drawKnob(b: KnobBrushes, fraction: Float, accent: Color) {
    val c = b.centre
    val r = b.radius

    // LED ring. Brightness fades in fractionally so the ring fills smoothly rather than in steps.
    val ledOff = lerp(accent, Color.Black, 0.82f)
    val ledHot = lerp(accent, Color.White, 0.30f)
    val ledRadius = r * 0.032f
    repeat(KnobLedCount) { index ->
        val t = index / (KnobLedCount - 1f)
        val p = pointOnCircle(c, r * 0.93f, KnobStartAngle + KnobSweepAngle * t)
        val lit = (fraction * (KnobLedCount - 1) - index + 1f).coerceIn(0f, 1f)
        if (lit > 0f) {
            drawCircle(accent.copy(alpha = 0.18f * lit), ledRadius * 3.2f, p)
            drawCircle(accent.copy(alpha = 0.35f * lit), ledRadius * 1.9f, p)
        }
        drawCircle(lerp(ledOff, ledHot, lit), ledRadius, p)
    }

    // Body
    drawCircle(b.shadow, radius = r * 0.86f, center = Offset(c.x, c.y + r * 0.04f))
    drawCircle(b.chrome, radius = r * 0.79f, center = c)
    drawCircle(Color(0xFF050505), radius = r * 0.715f, center = c)
    drawCircle(b.face, radius = r * 0.70f, center = c)
    drawCircle(b.lobes, radius = r * 0.70f, center = c)

    // Fine concentric machining grooves
    var g = 0.14f
    var i = 0
    while (g < 0.69f) {
        drawCircle(
            color = Color.White.copy(alpha = if (i % 2 == 0) 0.045f else 0.02f),
            radius = r * g,
            center = c,
            style = Stroke(width = r * 0.004f),
        )
        g += 0.035f
        i++
    }

    // Concave centre dish
    drawCircle(b.dish, radius = r * 0.44f, center = c)
    drawCircle(Color.White.copy(alpha = 0.10f), radius = r * 0.44f, center = c, style = Stroke(width = r * 0.006f))

    // Pointer: the only part that rotates
    val angle = KnobStartAngle + KnobSweepAngle * fraction
    val inner = pointOnCircle(c, r * 0.52f, angle)
    val outer = pointOnCircle(c, r * 0.66f, angle)
    drawLine(accent.copy(alpha = 0.35f), inner, outer, strokeWidth = r * 0.07f, cap = StrokeCap.Round)
    drawLine(Color(0xFFE8E8F0), inner, outer, strokeWidth = r * 0.028f, cap = StrokeCap.Round)
}

private fun pointOnCircle(centre: Offset, radius: Float, degrees: Float): Offset {
    val radians = degrees * PI.toFloat() / 180f
    return Offset(
        x = centre.x + cos(radians) * radius,
        y = centre.y + sin(radians) * radius,
    )
}

private fun stepsFor(range: ClosedFloatingPointRange<Float>, step: Float): Int =
    if (step > 0f) (((range.endInclusive - range.start) / step).roundToInt() - 1).coerceAtLeast(0) else 0

/** Pure geometry seam used by the JVM tests and the pointer handler above. */
internal fun dspKnobFractionForPoint(x: Float, y: Float, width: Float, height: Float): Float {
    val cx = width / 2f
    val cy = height / 2f
    var angle = Math.toDegrees(atan2((y - cy).toDouble(), (x - cx).toDouble())).toFloat()
    if (angle < 0f) angle += 360f
    val sweepAngle = when {
        angle in 45f..135f && angle >= 90f -> KnobStartAngle
        angle in 45f..135f -> KnobStartAngle + KnobSweepAngle
        angle < 45f -> angle + 360f
        else -> angle
    }
    return ((sweepAngle - KnobStartAngle) / KnobSweepAngle).coerceIn(0f, 1f)
}

/** Ignores the centre, where a pointer has no meaningful angle around the knob. */
internal fun dspKnobFractionForInteractivePoint(
    x: Float,
    y: Float,
    width: Float,
    height: Float,
): Float? {
    val dx = x - width / 2f
    val dy = y - height / 2f
    val deadZoneRadius = minOf(width, height) * KnobCenterDeadZoneFraction
    if (dx * dx + dy * dy <= deadZoneRadius * deadZoneRadius) return null
    return dspKnobFractionForPoint(x, y, width, height)
}
