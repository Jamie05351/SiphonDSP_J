package app.siphondsp.compose.controls

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.siphondsp.R
import kotlin.math.atan2
import kotlin.math.roundToInt

// Matches the -210..30 (240 degree) sweep used by the sidebar Gains & Delay icon's tick gauge --
// same visual language across the app. 0 degrees is 3 o'clock, positive is clockwise.
private const val StartAngle = -210f
private const val EndAngle = 30f

/**
 * Semi-circular gain gauge: a faded full-sweep track with a solid [accentColor] arc traced from
 * the low end up to the current value, and a speaker-cone face in the centre. Drag anywhere on
 * the dial to set the value (absolute pointer-angle -> value, the standard radial-knob mapping);
 * exact entry still goes through the existing tap-to-type value box this replaces in the caller,
 * not through the dial itself, so a mis-grab can't cost precision.
 */
@Composable
fun BmwGainKnob(
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    step: Float,
    accentColor: Color,
    onPreview: (Float) -> Unit,
    onCommit: (Float) -> Unit,
    modifier: Modifier = Modifier,
    diameter: Dp = 72.dp,
) {
    val fraction = ((value - range.start) / (range.endInclusive - range.start)).coerceIn(0f, 1f)
    var dragValue by remember(value) { mutableFloatStateOf(value) }

    fun valueForAngle(deg: Float): Float {
        // atan2 returns (-180, 180]; the gauge's -210 start wraps past -180, so anything reported
        // above EndAngle is really the wrapped tail of the sweep (or, past StartAngle, the bottom
        // dead-zone gap) -- shift it back by a full turn before clamping into range.
        val normalized = if (deg > EndAngle) deg - 360f else deg
        val clamped = normalized.coerceIn(StartAngle, EndAngle)
        val f = (clamped - StartAngle) / (EndAngle - StartAngle)
        val raw = range.start + f * (range.endInclusive - range.start)
        if (step <= 0f) return raw.coerceIn(range.start, range.endInclusive)
        val snapped = range.start + ((raw - range.start) / step).roundToInt() * step
        return snapped.coerceIn(range.start, range.endInclusive)
    }

    Box(
        modifier = modifier.size(diameter),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(range, step) {
                    detectDragGestures(
                        onDrag = { change, _ ->
                            change.consume()
                            val cx = size.width / 2f
                            val cy = size.height / 2f
                            val deg = Math.toDegrees(
                                atan2(
                                    (change.position.y - cy).toDouble(),
                                    (change.position.x - cx).toDouble(),
                                ),
                            ).toFloat()
                            val next = valueForAngle(deg)
                            dragValue = next
                            onPreview(next)
                        },
                        onDragEnd = { onCommit(dragValue) },
                        onDragCancel = { onCommit(dragValue) },
                    )
                },
        ) {
            val strokeW = size.minDimension * 0.12f
            val inset = strokeW / 2f + 2.dp.toPx()
            val arcSize = Size(size.width - inset * 2f, size.height - inset * 2f)
            val topLeft = Offset(inset, inset)

            drawArc(
                color = accentColor.copy(alpha = 0.28f),
                startAngle = StartAngle,
                sweepAngle = EndAngle - StartAngle,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokeW, cap = StrokeCap.Round),
            )
            drawArc(
                color = accentColor,
                startAngle = StartAngle,
                sweepAngle = (EndAngle - StartAngle) * fraction,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokeW, cap = StrokeCap.Round),
            )
        }

        Image(
            painter = painterResource(R.drawable.bmw_speaker_cone),
            contentDescription = null,
            modifier = Modifier
                .padding(diameter * 0.2f)
                .fillMaxSize(),
        )
    }
}
