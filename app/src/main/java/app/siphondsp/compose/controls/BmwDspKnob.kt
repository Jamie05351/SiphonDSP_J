package app.siphondsp.compose.controls

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.progressSemantics
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

private const val KnobStartAngle = 135f
private const val KnobSweepAngle = 270f
private const val KnobLedCount = 25

/**
 * Generic compressor/DSP knob in the same black-glass LED language as [BmwSlider]. It keeps the
 * caller's [accentColor], snaps to [step], previews while dragged and commits on release.
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
) {
    val safeValue = value.coerceIn(valueRange.start, valueRange.endInclusive)
    val span = valueRange.endInclusive - valueRange.start
    val fraction = if (span > 0f) ((safeValue - valueRange.start) / span).coerceIn(0f, 1f) else 0f
    val currentOnPreview by rememberUpdatedState(onPreview)
    val currentOnCommit by rememberUpdatedState(onCommit)
    var dragValue by remember(value) { mutableFloatStateOf(safeValue) }

    fun valueFor(position: Offset, width: Int, height: Int): Float {
        val f = dspKnobFractionForPoint(position.x, position.y, width.toFloat(), height.toFloat())
        val raw = valueRange.start + span * f
        return snapToStep(raw, valueRange, step)
    }

    val inputModifier = if (enabled) {
        Modifier
            .pointerInput(valueRange, step) {
                detectTapGestures { position ->
                    val next = valueFor(position, size.width, size.height)
                    dragValue = next
                    currentOnPreview(next)
                    currentOnCommit(next)
                }
            }
            .pointerInput(valueRange, step) {
                detectDragGestures(
                    onDrag = { change, _ ->
                        change.consume()
                        val next = valueFor(change.position, size.width, size.height)
                        dragValue = next
                        currentOnPreview(next)
                    },
                    onDragEnd = { currentOnCommit(dragValue) },
                    onDragCancel = { currentOnCommit(dragValue) },
                )
            }
    } else {
        Modifier
    }

    Canvas(
        modifier = modifier
            .size(diameter)
            .alpha(if (enabled) 1f else 0.4f)
            .progressSemantics(safeValue, valueRange, stepsFor(valueRange, step))
            .focusProperties { canFocus = false }
            .then(inputModifier),
    ) {
        val side = size.minDimension
        val centre = center
        val knobRadius = side * 0.34f
        val ledOrbit = side * 0.44f
        val ledRadius = side * 0.0175f
        val activeLed = ((KnobLedCount - 1) * fraction).roundToInt()

        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Color(0xFF4B4E58), Color(0xFF111318), Color.Black),
                center = Offset(centre.x - knobRadius * 0.30f, centre.y - knobRadius * 0.34f),
                radius = knobRadius * 1.45f,
            ),
            radius = knobRadius,
            center = centre,
        )
        drawCircle(
            color = Color(0xFF363941),
            radius = knobRadius,
            center = centre,
            style = Stroke(width = side * 0.024f),
        )
        drawCircle(
            color = accentColor.copy(alpha = 0.74f),
            radius = knobRadius * 0.91f,
            center = centre,
            style = Stroke(width = side * 0.007f),
        )

        repeat(KnobLedCount) { index ->
            val ledFraction = index / (KnobLedCount - 1f)
            val angle = KnobStartAngle + KnobSweepAngle * ledFraction
            val ledCentre = pointOnCircle(centre, ledOrbit, angle)
            if (index <= activeLed) {
                drawCircle(accentColor.copy(alpha = 0.18f), ledRadius * 2.2f, ledCentre)
                drawCircle(accentColor, ledRadius * 1.34f, ledCentre)
                drawCircle(androidx.compose.ui.graphics.lerp(accentColor, Color.White, 0.88f), ledRadius * 0.62f, ledCentre)
            } else {
                drawCircle(Color(0xFF363941), ledRadius * 1.28f, ledCentre)
                drawCircle(Color(0xFF07080B), ledRadius, ledCentre)
            }
        }

        val markerAngle = KnobStartAngle + KnobSweepAngle * fraction
        drawLine(
            color = Color(0xFFFFF2D0),
            start = pointOnCircle(centre, knobRadius * 0.72f, markerAngle),
            end = pointOnCircle(centre, knobRadius * 0.91f, markerAngle),
            strokeWidth = side * 0.022f,
            cap = StrokeCap.Round,
        )
    }
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
